# Design Note — Combat & Encounters

- **Status:** in-progress (UC13 implemented — real-time combat MVP; combat missions, shields, loot still deferred)
- **Last updated:** 2026-06-08
- **Related:** PROJECT_BRIEF.md → in_scope #4 (encounters), core_gameplay_loop (Earn); [ADR 0012](../adr/0012-real-time-combat.md) (the binding decisions); [ship-and-controls.md](ship-and-controls.md) (turrets/crew, sectional damage); [missions.md](missions.md) (combat missions = later phase); [ADR 0006](../adr/0006-determinism-and-playthrough-harness.md) (determinism), [ADR 0010](../adr/0010-crew-and-turret-operability.md) (crew gates turrets)

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
- On destruction the player reappears at their last station, minus some cargo.

## Data & state

- **Transient (never persisted):** the live `WorldState.combat` (`CombatState`) — hostiles, projectiles,
  combat RNG, monotonic id allocators, player cooldowns. Regenerated from the seeded encounter on load;
  a mid-combat save reloads with combat cleared.
- **Persisted (durable consequences):** each ship's `OwnedShip.sectionDamage` (new `ship_section_damage`
  table, full-snapshot per ship like cargo; absent section = pristine) and `WorldState.lastDockedStation`
  (`game_state.last_docked_station_id`). Schema v10→v11 (additive; see ADR 0012 / ADR 0002).
- Max section HP and the weapon fit are **derived** stats (`ShipStats`), never stored.

## Dependencies & interactions

- Reads ship stats from `outfit/ShipStats` (section HP, weapons) and crew from `OwnedShip` (turret gate).
- Shares the seeded RNG primitive `common/DeterministicRng` with mission instancing (ADR 0011) — the
  `MissionRng` refactor onto it is byte-identical (UC12 fixtures are the guard).
- The pure model is engine-free (added to `NoBox2DGuardTest`); `PlayScreen` paces `Combat.step` off
  accumulated `dt` (fixed sub-tick), while the replay harness steps it at a fixed `dt`.
- Movement: `CombatLimitedMovement` composes on top of `FuelLimitedMovement` without touching the model.

## Open questions

_Deferred (each a future UC/ADR — do not resolve silently in code):_

- **Combat-mission type** — only the thin `missionSpawn` hook exists; the full mission flow (objectives,
  bounty payout) is unbuilt.
- **Shields / armour** — intentionally omitted; the MVP is HULL + sections only.
- **Loot / salvage / bounty economy** — destroyed hostiles drop nothing yet; combat does not feed economy.
- **Richer AI** (formations, retreat-and-regroup, targeting the player's weakest section) and **difficulty
  scaling** by a spawn director.
- **Balancing** — every weapon/HP/AI number is a `[TUNE]` placeholder.

## References

Star Valor / Starsector combat feel; libGDX Box2D collision (device-tier only — the model stays pure).
