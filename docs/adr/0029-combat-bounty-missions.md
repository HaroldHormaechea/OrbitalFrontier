# ADR 0029 — Combat / bounty missions

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC41 ties the existing real-time combat system (UC13, ADR 0012) to the mission board (UC12, ADR 0011).
Before this change `MissionType` had only `MINING` and `COURIER`; `EncounterSpawner` carried a "thin
mission-spawn hook" (`missionSpawn`) that nothing called, and combat had no objective/payout flow.

A bounty must: be offered on the board **and** via radio (AC#1), spawn/track the contracted hostiles
through the encounter system (AC#2), pay out on completion (AC#3), surface progress on the HUD objective
line (AC#4), and be exercised by a recorded playthrough (AC#5). Two design questions had no precedent in
the codebase and are recorded here:

1. **How does a bounty complete?** Other missions turn in at a station (mining: quota in hold; courier:
   parcel at destination). Combat has no station seam.
2. **What happens on failure?** Courier missions have a tick timer + penalty; mining/bounty do not.

Hard constraint (ADR 0005 / 0006): the game has a record/replay determinism harness. Any combat wiring
must be added to **both** the device orchestrator (`screen/PlayScreen`) and the test-set orchestrator
mirror (`sim/Simulation`) in lockstep, or live and replay diverge.

## Options considered

| Option | For | Against |
|---|---|---|
| **Auto-complete + pay on final kill (chosen)** | Matches the combat fantasy (the kill *is* the completion); no backtracking to a station; one attribution key (the zone) drives spawn, match and payout | Payout happens mid-flight, not at a desk — a new completion path distinct from station turn-in |
| Return-to-station turn-in (like mining/courier) | Reuses the existing `TurnIn` order + station seam | Tedious backtracking after a fight; needs a "kills banked" state that is itself the completion anyway; worse fit for AC#3 |
| Failure timer on bounties (like courier) | Symmetry with courier | No abandon order exists today; a punitive timer on a free-roam combat contract fights the "roam, earn" loop (brief core gameplay loop); mining (the other quota mission) has no timer either |

## Decision

Add `MissionType.BOUNTY`. A bounty is one authored world contract targeting a **dedicated** encounter
zone (its own id, geometrically disjoint from the natural `ENCOUNTER_ZONES`) plus a kill quota.

- **Single canonical key.** The authored `targetZoneId` is, by construction, the offer id key
  (`"bounty:<targetZoneId>"`), the spawn zone id handed to `EncounterSpawner.missionSpawn`, **and** the
  kill-attribution key (a match against `CombatState.zoneId`). One string, so the three never disagree.
- **Offered on board AND radio (AC#1).** `MissionGenerator` emits the **same** canonical id from both
  `boardOffers` and `radioOffers`; the `takenIds` filter dedups it across the two surfaces.
- **Spawn (AC#2).** While a bounty is ACTIVE, the orchestrator edge-spawns the contracted hostiles when
  the player crosses outside→inside the target zone (the same edge-trigger natural encounters use), via
  the previously-unused `missionSpawn` hook. Suppressed while a fight is already active.
- **Auto-complete + pay (AC#3).** Player kills whose pre-step `CombatState.zoneId` matches an ACTIVE
  bounty's `targetZoneId` raise a persisted `killProgress` (capped at `killTarget`); reaching
  `killTarget` flips the mission `COMPLETED` and adds `rewardCredits`. **No manual turn-in**
  (`Missions.tryComplete` returns null for `BOUNTY`), and **no failure timer / abandon** (mining-style).
- **HUD (AC#4).** The objective line shows `"Bounty <killProgress>/<killTarget>"`; the displayed field
  *is* the completion field, so readout and completion never disagree.

The kill→progress fold is a new pure resolver, `BountyTracking.applyKills(...) : MissionResult`, the
combat-mission analogue of `Missions.resolve`/`advance` — side-effect-free, no engine types, same-instance
no-op. Reputation is threaded through it unchanged today; granting combat reputation is the **UC43 seam**
(the signature already carries `reputation`/`reputationParams`).

### Determinism (the lockstep)

The crossing-spawn and the `applyKills` fold are added identically to `PlayScreen.runCombat` /
`stepCombatOnce` **and** `Simulation.step`. Each orchestrator reuses its existing spawn-seed convention
(`combatSpawnTick` on device, `state.tick` in the sim), mirroring the natural-encounter spawner exactly.
No new RNG, no `java.time`. The bounty offer's reward is `rewardBase + rewardPerKill × killTarget` —
fully deterministic, no RNG — so the offer is byte-stable across runs. The offer is appended **last** in
the generator and is independently keyed, so it never perturbs the bytes of the existing mining/courier/
premium offers.

### Persistence (v18 → v19)

The `mission` table gains three additive, defaulted columns — `target_zone_id TEXT`,
`kill_target INTEGER NOT NULL DEFAULT 0`, `kill_progress INTEGER NOT NULL DEFAULT 0` — via `18.sqm`
(`ALTER TABLE ADD COLUMN ×3`, minSdk-24-safe, no UPSERT; the table is a full delete-then-insert snapshot
per slot). `SaveVersion.CURRENT = 19`; `databases/19.db` is the regenerated baseline (`verifyMigrations`
green). A pre-UC41 mission row reads the new columns back at `null / 0 / 0` — exactly a non-bounty
mission's defaults — so old saves load byte-for-byte unchanged.

### `[TUNE]` values

- `BountyParams.rewardBase = 500`, `rewardPerKill = 300` (a kill-1 bounty pays 800cr).
- MVP map: Alpha Station contracts a kill-1 RAIDER bounty on the `bounty-alpha-raider` zone at
  `(0, 1400)` r220 — open space well north of the start cluster, disjoint from the `alpha-raider-picket`
  natural zone east of centre.

## Consequences

- The dormant `missionSpawn` hook is now live; mission-driven encounters share the natural-encounter model
  (only the trigger differs). Adding more bounties is data-only (`BOUNTY_CONTRACTS` + `BOUNTY_TARGET_ZONES`).
- A new completion path (auto-pay) exists beside station turn-in; future mission types choose either.
- The kill-attribution-by-zone scheme assumes bounty zones stay disjoint from natural zones — enforced by
  authoring discipline (separate lists) and documented on `BOUNTY_TARGET_ZONES`.
- **Replay fixtures.** New bounty fields default to non-bounty values and the snapshot DTO must keep them
  out of the on-disk bytes (`@EncodeDefault(NEVER)`) so pre-UC41 playthroughs stay byte-identical.
- Combat reputation is deliberately deferred to UC43; the `BountyTracking` signature already exposes the
  seam, so wiring it later is additive.
