# Design Note — Combat & Encounters

- **Status:** in-progress (UC13 real-time combat MVP + UC33 destruction screen + UC41 combat-bounty missions + UC42 loot/salvage implemented; shields still deferred)
- **Last updated:** 2026-06-19
- **Related:** PROJECT_BRIEF.md → in_scope #4 (encounters), core_gameplay_loop (Earn); [ADR 0012](../adr/0012-real-time-combat.md) (the binding combat decisions); [ADR 0022](../adr/0022-ship-destruction-screen.md) (the destruction/game-over screen); [ship-and-controls.md](ship-and-controls.md) (turrets/crew, sectional damage); [missions.md](missions.md) (combat missions = later phase); [ADR 0006](../adr/0006-determinism-and-playthrough-harness.md) (determinism), [ADR 0010](../adr/0010-crew-and-turret-operability.md) (crew gates turrets)

## Summary

Real-time ship-to-ship combat during roaming and missions. The player fires a **fixed/forward weapon**
along hull facing via a FIRE control and is backed by **auto-aim turrets** that need crew (UC11). Hits
land on a **per-section damage model** (HULL / ENGINE / TURRET / WEAPON) shown as a HUD ship schematic —
an engine hit slows the ship, a turret/weapon hit disables that mount, a hull hit at 0 destroys the ship.
**Rule-based enemy ships** (no ML) engage with data-driven difficulty. Destruction is **forgiving**:
respawn at the last docked station with a partial cargo-loss penalty (no permadeath). The player can
**flee** by outrunning hostiles past their leash. Encounters are **natural** (edge-triggered on entering
an authored zone) or **mission-spawned** (a thin hook). All combat logic is **pure, seeded and
JVM-testable** so playthroughs replay bit-for-bit (UC02).

## Goals

- Satisfying top-down combat that is fair on touch and scales with ship upgrades/crew.
- Fully deterministic, headless-replayable combat (damage, targeting, AI are pure functions of seeded
  state) — no engine/Box2D/wall-clock dependency in the model.
- Forgiving stakes (no permadeath) that fit the approachable MVP.

## Mechanics / ideas

- **Weapons.** Every ship has a built-in **fixed/forward weapon** (`FireAction.FIRE`, fires along hull
  facing) and one built-in **auto-aim turret** (crew-gated). `ShipStats.weaponLoadout(type, loadout)`
  derives the fit; each `WEAPONS`-slot part adds another fixed weapon. Per-mount cooldowns gate the rate.
  All numbers are `[TUNE]` (e.g. fixed: dmg 6, cd 0.5s, speed 420, range 640).
- **Turrets — auto-aim, crew-gated (UC11/ADR 0010).** A turret acquires the **priority target** and
  fires without a player action, but only if enough crew operate it (`Turret.requiredCrew`, via
  `TurretOperability`). Targeting priority is a **total order**: ascending distance², tie-broken by
  ascending monotonic `HostileId` — never `hashCode`/identity, so it is replay-stable.
- **Sectional damage.** `SectionDamage = Map<ShipSection,Int>` of *current* HP; an absent section is
  pristine (full HP). Max HP is the derived stat `ShipStats.sectionHp` (HULL 100 +25/HULL_PLATING,
  ENGINE 40, TURRET 30, WEAPON 30 — `[TUNE]`), never stored. A hit's section is chosen by an
  **RNG-weighted** pick over a cumulative-weight list keyed by `ShipSection.name` (HULL weighted
  heaviest). Effects: HULL→destruction at 0; ENGINE→speed scaled by `CombatLimitedMovement`
  (floor `minEngineSpeedFactor`); TURRET/WEAPON→that mount disabled at 0.
- **Enemy AI (no ML).** `AiBehavior.AGGRESSIVE` closes and fires within `engageRange`;
  `FLEE_WHEN_DAMAGED` runs once its hull drops below `fleeHullFraction`. Stats live on
  `HostileArchetype` (`HostileArchetypes` catalog: RAIDER, SCAVENGER), so difficulty is data-driven.
- **Destruction & respawn (no permadeath).** Hull at 0 → `Respawn` relocates the ship to
  `lastDockedStation`, jettisons `respawnCargoLossFraction` of the hold (deterministically, in
  `ResourceType` order), fully repairs sections and clears combat. Credits/progression untouched.
- **Flee / disengage.** A hostile past its `leashRange` breaks off (the outrun path); changing sector or
  docking clears `CombatState` to `NONE`.
- **Encounters.** Natural encounters are **edge-triggered**: `EncounterSpawner.naturalSpawn` fires once on
  the **outside→inside** crossing of an authored `EncounterZone` (so outrunning and leaving, then
  re-entering, can ambush again, but lingering after a cleared fight does not). Seeded
  `"encounter:$zoneId:$spawnTick"`. A `missionSpawn` hook injects the same shape for combat missions.

## Player-facing behavior

- A held **FIRE** button in the action cluster (fires while down, cooldown-gated). Turrets fire
  automatically once crew is aboard.
- A **HUD ship schematic** (`ShipSchematicRenderer`) shows per-section health bars (green→amber→red); an
  "IN COMBAT" HUD cue appears while a fight is live. Hostiles and projectiles draw in world space
  (`HostileRenderer`).
- **Destruction / game-over screen (UC33, [ADR 0022](../adr/0022-ship-destruction-screen.md)).** A hull
  hit that drops the ship to 0 no longer silently teleports the player home. Instead the simulation
  **halts** and a modal **destruction screen** appears: a dim tap-swallowing backdrop, a "SHIP DESTROYED"
  title, and three consequence lines — **cargo lost** (the jettisoned units), the **credit/insurance
  penalty** ("Insurance: covered — 0 credits" in the MVP; no permadeath, no credit loss), and the
  **respawn location** (the station's name, or "Alpha Reach" at the game-start fallback). A single
  **CONTINUE** button returns control at the respawn station, where the ship already sits. The flight
  controls and the HUD pause button hide while the screen is up, and a held stick/FIRE is neutralised so
  it can't sit live under the frozen overlay (mirrors the pause overlay, UC32).
- The freeze reuses the UC32 pattern: a pure `render/DestructionState` gate read once per frame, **nested
  under** the pause gate, so a pending destruction skips the whole `advanceSimulation(dt)` while
  `renderFrame` keeps drawing the frozen scene. The consequence itself is the pure, libGDX-free
  `combat/DestructionSummary` (cargo lost, credit penalty, respawn-location name), built by
  `DestructionSummary.from(RespawnResult, name)` — so a JVM test drives a destruction and asserts the
  summary without any GL (AC#5). The `Respawn` rule (relocate, jettison `respawnCargoLossFraction`,
  repair, clear combat) is unchanged; the screen only surfaces and confirms it.

## Data & state

- **Transient (never persisted):** the live `WorldState.combat` (`CombatState`) — hostiles, projectiles,
  combat RNG, monotonic id allocators, player cooldowns. Regenerated from the seeded encounter on load;
  a mid-combat save reloads with combat cleared.
- **Persisted (durable consequences):** each ship's `OwnedShip.sectionDamage` (new `ship_section_damage`
  table, full-snapshot per ship like cargo; absent section = pristine) and `WorldState.lastDockedStation`
  (`game_state.last_docked_station_id`). Schema v10→v11 (additive; see ADR 0012 / ADR 0002).
- **Destruction persistence (UC33) — no schema change.** The respawn is a complete world mutation
  (position, sector, lightened cargo, repaired sections, cleared combat) committed **before** the player
  taps CONTINUE, and written **durably at the moment of destruction** via the new
  `AutosaveController.onCriticalEvent("respawn")` (enqueue + `saveExecutor.flush()`, the event-driven
  analogue of `onPauseOrExit`). A crash/close on the consequence screen therefore reloads the
  **post-respawn** state; because combat is transient, no encounter reloads and the cargo-loss penalty
  applies exactly once (AC#4). No new tables or migration — the existing world snapshot already carries
  everything (see [ADR 0022](../adr/0022-ship-destruction-screen.md)).
- Max section HP and the weapon fit are **derived** stats (`ShipStats`), never stored.

## Dependencies & interactions

- Reads ship stats from `outfit/ShipStats` (section HP, weapons) and crew from `OwnedShip` (turret gate).
- Shares the seeded RNG primitive `common/DeterministicRng` with mission instancing (ADR 0011) — the
  `MissionRng` refactor onto it is byte-identical (UC12 fixtures are the guard).
- The pure model is engine-free (added to `NoBox2DGuardTest`); `PlayScreen` paces `Combat.step` off
  accumulated `dt` (fixed sub-tick), while the replay harness steps it at a fixed `dt`.
- Movement: `CombatLimitedMovement` composes on top of `FuelLimitedMovement` without touching the model.
- **Loot / salvage economy (UC42)** — a destroyed hostile drops **transient salvage** (`combat/SalvageDrop`):
  credits + resources rolled by the seed-deterministic `combat/LootTable` (keyed `salvage:$zoneId:$hostileId`,
  drawing the shared `DeterministicRng` — **no** combat-RNG draws, so combat fixtures stay byte-identical).
  The player collects it by **proximity** (`Salvage.collect`, radius `CombatParams.salvagePickupRadius`):
  resources flow to cargo (capacity-respecting partial fill; overflow left behind + a CARGO-FULL toast),
  credits to the wallet. Salvage credits **stack with bounty rewards** as a distinct source (no double-count).
  Salvage is transient world state excluded from the save (reconstructs empty on reload, like combat —
  ADR 0012); see [ADR 0030](../adr/0030-loot-and-salvage-economy.md). This is the combat→Earn link of the loop.

## Open questions

_Deferred (each a future UC/ADR — do not resolve silently in code):_

- **Combat-mission type** — bounty contracts are built (UC41: `missionSpawn` + auto-pay on the contracted
  kill, [ADR 0029](../adr/0029-combat-bounty-missions.md)); richer combat-mission flows (escort, objectives
  beyond a kill quota) remain deferred.
- **Shields / armour** — intentionally omitted; the MVP is HULL + sections only.
- **Richer AI** (formations, retreat-and-regroup, targeting the player's weakest section) and **difficulty
  scaling** by a spawn director.
- **Balancing** — every weapon/HP/AI number is a `[TUNE]` placeholder.

## References

Star Valor / Starsector combat feel; libGDX Box2D collision (device-tier only — the model stays pure).
