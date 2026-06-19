# ADR 0033 — Richer enemy AI & spawn director

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC45 deepens combat beyond the single-archetype, single-hostile encounters the MVP shipped with.
Combat is the deterministic core (UC13, [ADR 0012](0012-real-time-combat.md)): the pure `Combat.step`
is called by both `PlayScreen.stepCombatOnce` on device and the test-set `sim.Simulation.step` mirror,
and combat replay stability is enforced by the per-UC replay tests (`Uc13/41/42/43…ReplayTest`) plus
the `PlaythroughFixtureTest` regenerate-and-compare guard.

The load-bearing constraint is that **combat state is transient** ([ADR 0012](0012-real-time-combat.md),
[ADR 0030](0030-loot-and-salvage-economy.md)): `StateSnapshotDto` omits hostiles/projectiles/combat-RNG,
so no field added to `CombatState`/`Hostile`/`Projectile`/`HostileArchetype`/`EncounterZone` changes any
committed save/fixture JSON. The real stability contract is therefore behavioural: **do not change the
behaviour or RNG-consumption of the existing RAIDER / SCAVENGER / INDEPENDENT_MARAUDER in their recorded
encounters.** Every decision below is engineered around opt-in defaults so existing archetypes and zones
are provably non-perturbing.

UC45 wants: multiple zones across the sectors with configurable hostile composition (count/type); richer
AI (engage, retreat-and-regroup, weakest-section targeting); a spawn director that scales difficulty by a
defined input; and all of it pure and seed-deterministic for replay stability.

## Options considered

| Option | For | Against |
|---|---|---|
| **Opt-in additive AI/zone/director (chosen)** | New behaviours/zones/scaling are off by default → existing archetypes, zones and RNG streams are byte-identical; no schema bump | A little more surface (new flags, a composition list, a scaling policy type) |
| Restructure `EnemyAi.decide` / `EncounterZone` outright | Cleaner single model | Risk of perturbing the existing AGGRESSIVE/FLEE branches and the one-`nextFloat`-per-hostile spawn RNG order → breaks recorded replays |
| Reputation-driven difficulty input | Thematically ties combat to standing | A faction-scaled director needs faction-affiliated archetypes, breaking `CombatReputationTest`'s exact-affiliation assertion; harder to seed deterministically |

## Decision

Five additive, opt-in decisions, all pure and seed-deterministic (AC#4):

1. **Opt-in AI behaviours.** Add `AiBehavior.RETREAT_AND_REGROUP` (a memoryless, distance-driven branch:
   while damaged, run while inside `regroupRange`, then re-engage from that standoff distance) as an
   *additive* branch in `EnemyAi.decide` — the existing AGGRESSIVE and FLEE_WHEN_DAMAGED branches are left
   byte-identical. New archetype fields `regroupRange` (default `0f`) and `targetsWeakestSection`
   (default `false`) keep every existing archetype unchanged.

2. **Deterministic, direct-apply weakest-section targeting — "weakest-at-fire-time".** A
   `targetsWeakestSection` archetype stamps the player's weakest section (lowest current-HP fraction,
   tie-broken by ascending `ShipSection.name` — the same stable ordering `DamageModel` uses; see
   `WeakestSection`) onto its projectile **at fire time** (not re-chosen mid-flight). A stamped hostile hit
   applies its damage **directly** to that section with **no RNG draw**; every non-targeting (i.e. all
   existing) hostile shot keeps the RNG-weighted `DamageModel` path, so the RNG stream stays byte-identical
   for existing encounters.

3. **Progression-driven, opt-in spawn director.** A new pure `SpawnDirector.scale(base, scaling,
   progression)` scales a zone's composition per its `SpawnScaling` policy. `SpawnScaling.None` (the default
   on every existing zone, natural and bounty) returns the base unchanged for any input; `ByProgression`
   appends a bounded number of reinforcement hostiles. The difficulty input is **player progression** —
   `ProgressionLevel.of(installedUpgradeCount)`, derived from the active ship's loadout — chosen over
   reputation so new archetypes stay unaligned (keeping `CombatReputationTest` green) and difficulty seeds
   deterministically from the fixture's loadout. The device (`PlayScreen.runCombat`) and the replay mirror
   (`sim.Simulation.step`) compute progression identically (lockstep).

4. **`EncounterZone` composition list.** `EncounterZone.composition: List<HostileSpawn>` is the source of
   truth (count/type per entry, AC#1), with a backward-compatible secondary constructor
   `(…, archetypeId, hostileCount)` and computed `archetypeId`/`hostileCount` accessors so every existing
   zone literal, the bounty path, and `EncounterSpawnerTest` compile and behave identically. The spawner
   iterates entries with one `nextFloat` bearing-draw per hostile — a single-entry composition draws the
   RNG byte-identically to the pre-UC45 spawner. `missionSpawn` (bounty path) stays single-archetype.

5. **Transient state — no schema bump.** All new AI/spawn state is transient (encounter state is never
   serialized); the director's input is derived from the already-persisted loadout. Schema stays at v19.

`ProgressionLevel` takes the **installed-upgrade count (`Int`)**, not the `outfit.Loadout`, because `outfit`
already depends on `combat`; importing `outfit` into `combat` would close a dependency cycle. The call
sites pass `loadout.allInstalled().size`.

Formations / flanking are **out of scope** (no AC requires them); the AC pitfall mentions formations only as
an auto-aim stress concern, which the existing distance²-then-id total order in `TargetingPriority` already
handles.

## Consequences

- Three natural encounter zones now exist across all three sectors (Alpha picket, the new Beta
  `beta-regroup-picket`, and Gamma's `gamma-independent-marauder` + the new multi-archetype, scaled
  `gamma-marauder-pack`), each with configurable composition (AC#1). New zones are authored geometrically
  disjoint from every existing fixture's flight path so no recorded replay is perturbed (CONDITION #1).
- New archetypes (`REGROUP_MARAUDER`, `PRECISION_RAIDER`) are deliberately **unaligned** (`factionId = null`)
  so `CombatReputationTest`'s exact-affiliation assertion stays green (CONDITION #2).
- Combat stays pure and seed-deterministic (AC#4); existing combat replays remain byte-identical because
  every new path is opt-in.
- The "weakest-at-fire-time" semantic is a deliberate MVP simplification: a stamped shot is not re-targeted
  if the player's weakest section changes in flight. Cheap, deterministic, and good enough for the MVP fight.
- All spawn/AI numbers (`regroupRange`, archetype stats, `ProgressionLevel.THRESHOLDS`, scaling caps) are
  `[TUNE]` placeholders; balancing is a later pass.
