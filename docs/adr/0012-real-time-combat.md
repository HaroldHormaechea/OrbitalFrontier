# ADR 0012 — Real-time combat: pure seeded model, transient vs. persisted split, shared deterministic RNG primitive

- **Status:** Accepted
- **Date:** 2026-06-08
- **Refines:** [ADR 0006](0006-determinism-and-playthrough-harness.md) (record/replay determinism), [ADR 0011](0011-missions.md) (string-hash → LCG seeded RNG; regenerate-don't-persist), [ADR 0002](0002-persistence-sqlite-migrations.md) / [ADR 0003](0003-persistence-access-layer-sqldelight.md) (persistence + sequential migrations), [ADR 0010](0010-crew-and-turret-operability.md) (crew gates turrets), [ADR 0005](0005-movement-integration.md) (movement params composition), [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable). Realizes use-case 13 (real-time combat).

## Context

`PROJECT_BRIEF.md` → in_scope #4 (encounters) and `docs/design/combat.md` (real-time decided) call for
ship-to-ship combat: fixed/forward weapons fired via an action control, **auto-aim turrets that require
crew** (UC11), a **per-section/component damage model** driving a HUD schematic, **rule-based enemy AI**
(no ML) with data-driven difficulty, **forgiving destruction** (respawn at the last docked station with a
penalty — no permadeath), **flee/disengage**, and **natural + mission-spawned** encounters. UC13 AC#7/#8
require the combat logic to be **pure, deterministic and JVM-testable**, with a recorded playthrough that
spawns a hostile, fires, destroys it and asserts the kill + sectional damage. This is the heaviest UC.

Five forces shape the design:

- **The byte-identical / determinism contract** (ADR 0006). Combat must replay bit-for-bit, and adding it
  must not perturb any pre-UC13 number — every existing fixture must still replay exactly.
- **Two RNG surfaces now exist** (mission instancing + combat). They must share one primitive or they will
  silently diverge, and combat's RNG must be a *value* threaded through an immutable snapshot (not a
  `var`) so the whole combat state compares structurally for replay.
- **Most of combat is runtime, not save-data.** Hostiles, projectiles and the combat RNG are regenerated
  from a seeded encounter; persisting them would bloat the save and pin a stale fight. But the player's
  durable consequences (section damage, last dock) must survive a save.
- **Box2D / engine purity** (ADR 0001/0006). The combat model must stay engine-free so it runs headlessly
  in replay — it is added to the `NoBox2DGuardTest` import scan.
- **Combat-mission type and shields are not in scope.** The model must leave a clean seam for a future
  combat-mission spawn and not commit to shields.

## Options considered

| Decision | Options | Chosen — why |
|---|---|---|
| Combat RNG | (a) `Random`/seeded platform RNG; (b) reuse mission's private `MissionRng`; (c) **extract a shared `common/DeterministicRng` primitive (fnv1a, lcgAdvance, boundedInt, floatFromState) and build a functional `CombatRng` value class on it** | **(c)** — the only determinism-safe and non-duplicating choice. `MissionRng` is refactored onto the same primitive **byte-for-byte unchanged** (UC12 fixtures prove it), and `CombatRng` is a `@JvmInline value class` over a `Long` returning `(value, nextRng)`, so combat state is a comparable immutable snapshot. |
| What persists | (a) persist the whole encounter; (b) **persist only durable player state (per-ship section damage + last docked station); regenerate hostiles/projectiles/RNG from the seeded encounter** | **(b)** — mirrors ADR 0011's regenerate-don't-persist. A mid-combat save reloads with combat cleared; section damage and the respawn point survive. Compact and replay-stable. |
| Section damage shape | (a) a fixed HP field per section; (b) **`Map<ShipSection,Int>` of current HP, absent = full (pristine)** | **(b)** — exactly the `fieldDepletion` pattern: the empty map is undamaged (byte-identical default), max HP is the derived stat `ShipStats.sectionHp` (never stored), and the canonical form is unique. |
| Turret targeting order | (a) nearest by distance; (b) `hashCode`/identity; (c) **total order: ascending distance² then ascending monotonic `HostileId`** | **(c)** — a *total* order with no `hashCode`/identity/list-position dependence is what keeps targeting byte-stable on replay. Distance is compared squared (no `sqrt` rounding). |
| Hit-location pick | (a) fixed/round-robin; (b) **RNG-weighted over a cumulative-weight list keyed by `ShipSection.name`** | **(b)** — weighted realism while staying deterministic; keying by the stable enum *name* (never ordinal) survives enum reordering. |
| Player destruction | (a) permadeath/game-over; (b) **respawn at last docked station with a partial cargo-loss penalty, full repair, combat cleared** | **(b)** — the brief mandates forgiving, no permadeath. Credits/progression untouched; only a cargo slice and the interruption. |
| Combat collections | (a) `HashMap`/`HashSet`; (b) **ordered `List`s only (hostiles sorted by id, projectiles by id)** | **(b)** — hash-based iteration order is nondeterministic; the model uses only ordered lists and monotonic id allocators. |
| Schema | (a) reuse a table; (b) **new additive `ship_section_damage` table + `game_state.last_docked_station_id`, v10→v11** | **(b)** — additive `CREATE TABLE` + `ALTER TABLE ADD COLUMN`, minSdk-24-safe, mirrors every prior UC. A migrated save reads back undamaged with no recorded dock. |

## Decision

A new pure, engine-free `combat` package holds the model: `CombatRng` (functional value class over the
shared `common/DeterministicRng`), `ShipSection` + `SectionDamage` (current-HP map, absent = pristine),
`Weapon` (`FixedWeapon` / crew-gated `Turret`), `Hostile` + `HostileArchetype`/`HostileArchetypes`
catalog (rule-based `AiBehavior`, data-driven `DifficultyTier`), `Projectile`, `CombatState` (id-sorted
hostiles/projectiles, threaded `rngState`, monotonic id allocators, player cooldowns; `NONE` sentinel),
`FireAction`, `CombatParams` (all `[TUNE]`), and the behaviour — `TargetingPriority` (total order),
`DamageModel` (name-keyed weighted pick), `EnemyAi`, `CombatLimitedMovement` (engine→speed, ===base when
pristine), `EncounterSpawner` (edge-triggered outside→inside crossing, seeded `"encounter:$zone:$tick"`,
plus a thin `missionSpawn` hook), `Respawn`, and **`Combat.step`** — the single shared tick the device
loop and the replay harness both call. `step` on an inactive `CombatState` returns the **same** instances,
advances no RNG and emits no events — the byte-identical anchor for pre-UC13 fixtures.

`ShipStats` gains `sectionHp`/`sectionHpMap`/`weaponLoadout` (derived, never stored); `OwnedShip` gains a
persisted `sectionDamage` (+ `withSectionDamage`, re-clamped in `withLoadout`); `WorldState` gains a
**transient** `combat` (never row-persisted) and a **persisted** `lastDockedStation`. Persistence goes
v10→v11: a new `ship_section_damage` table (full-snapshot per ship, like cargo) + `game_state
.last_docked_station_id`, with `SaveVersion.CURRENT` == `Schema.version` == 11.

**Scope held to the 8 ACs:** four sections (HULL/ENGINE/TURRET/WEAPON), two AI behaviours
(AGGRESSIVE / FLEE_WHEN_DAMAGED), nearest-only targeting, a thin mission-spawn hook. **Shields are
omitted** (HULL + sections only). The **full combat-mission type is deferred** to the `missionSpawn`
hook. A **mid-combat save regenerates** transient hostiles (combat reloads cleared). The **`MissionRng`
refactor is byte-identical** (UC12 fixtures are the regression guard). All weapon/damage/AI numbers are
`[TUNE]` placeholders.

## Consequences

- **Easier:** combat is fully JVM-testable and replay-reproducible; one RNG primitive backs both mission
  and combat (no drift); the transient/persisted split keeps saves compact; the seeded edge-triggered
  spawner makes the flee→outrun→re-enter loop work without a re-ambush bug; `CombatLimitedMovement`
  stacks on fuel limiting without touching the movement model.
- **Harder / follow-on:** every new combat number is a balance knob (`[TUNE]`); a new `ShipSection`
  is a migration (the name is the persisted key); the combat-mission type, shields, loot/bounty payout
  and richer AI remain to design (each a future UC/ADR, flagged in `docs/design/combat.md`).
- **Reversibility:** the model is pure and isolated; behaviour changes are local edits to the `combat`
  package. The schema change is additive and minSdk-safe; superseding it means another forward migration,
  never a destructive edit (ADR 0002).
