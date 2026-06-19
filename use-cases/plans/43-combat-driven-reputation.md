---
plan_for: use-cases/43-combat-driven-reputation.md
work_branch: feat/uc-43-combat-driven-reputation
team: orbital-frontier-uc-43
approved: 2026-06-19
---

# UC-43 — Combat-driven reputation — FINAL APPROVED PROPOSAL (analyst↔challenger agreed)

TARGET_DIR=`/workspace/OrbitalFrontier`. Profiles: none. Largely integration, as ADR 0013 predicted — the `Reputation.with` seam exists; only a combat call site + a hostile→faction mapping are missing. APPROVED in 2 rounds.

## Analysis — what already exists (verified against source)
- **Reputation model (UC14):** `faction.Reputation` (pure `Map<FactionId,Int>`, neutral=0=absent) with `with(faction, delta, min, max)` clamp. Carried save-wide on `WorldState.reputation`, mirrored on `sim.SimulationState.reputation`. Persisted (v12 `reputation` table, non-neutral rows only) AND already in the replay snapshot (`StateSnapshotDto.reputation`/`ReputationDto`). Read by `ReputationGate.isAvailable` at 3 sites (board, radio, simulation).
- **Reputation changes today** only via the two pure resolvers `Missions.resolve` (turn-in grant) / `Missions.advance` (courier-fail loss), through `Reputation.with` + `ReputationParams.{missionCompleteDelta,courierFailDelta,min,max}`.
- **Combat seam is genuinely empty:** `BountyTracking.applyKills` accepts `reputation`+`reputationParams` but threads them UNCHANGED (the documented UC43 seam) and only matches bounty zones — wrong scope for "every faction kill", so combat reputation is a SEPARATE concern, not folded into applyKills.
- **Kill path (lockstep, project rule #1):** `PlayScreen.stepCombatOnce` and `sim.Simulation` build, in lockstep, a `List<DestroyedHostile>` (id, archetypeId, position) from `CombatEvent.HostileDestroyed` for salvage — reused here. `Simulation` threads `bountyReputation` into BOTH the destroyed-path (~L635) and normal-path (~L671) returns.
- **No hostile→faction mapping exists** — `HostileArchetype` and `EncounterZone` carry no faction. This is the core new work. RAIDER is the only archetype any authored zone spawns (natural picket + bounty zone); SCAVENGER is authored but spawned by no zone.
- **Notifications (UC35):** `GameNotifications` → `NotificationKind` (severity+coalescable) → device-only `NotificationRenderer` (colors by *severity*, not kind; `notify` is engine-free, scanned wholesale by `NotifyPurityGuardTest`). `forCombatEvent` maps every kill to null and a test asserts only LEFT_COMBAT surfaces — so the rep toast MUST be enqueued separately at the kill site.
- **No relationship graph:** `Faction` has only id/displayName/color → AC#2 = single-faction MVP.

## Proposed Solution
**Model: archetype→faction (a hostile's faction is intrinsic to the ship type), negative per-kill delta.** Decisions recorded in a new ADR 0031.

**Production code (developer):**
1. `core/src/main/kotlin/com/orbitalfrontier/faction/ReputationParams.kt` — add `combatKillDelta: Int = DEFAULT_COMBAT_KILL_DELTA` (default e.g. −5, `require(<=0)` like courierFailDelta). Pinnable per playthrough.
2. `core/src/main/kotlin/com/orbitalfrontier/combat/Hostile.kt` — add `factionId: FactionId? = null` to `HostileArchetype` (combat→faction; acyclic, faction never imports combat, faction is engine-free so purity guards unaffected). RAIDER & SCAVENGER stay **null** (unaligned ⇒ no rep effect ⇒ neutral pitfall + all committed fixtures incl. UC41's "reputation unchanged" stay byte-identical). Add ONE new faction-affiliated archetype (`independents`-affiliated, e.g. INDEPENDENT_MARAUDER) to `HostileArchetypes` + `all`/`byId`.
3. `core/src/main/kotlin/com/orbitalfrontier/combat/CombatReputation.kt` — NEW pure resolver. `applyKills(destroyed: List<DestroyedHostile>, reputation, params): CombatReputationResult`. Per kill: resolve archetype via `HostileArchetypes.byId`, read `factionId`; null ⇒ skip; else `reputation.with(faction, params.combatKillDelta, min, max)`. Returns new reputation + per-faction *actual* applied deltas (new.valueFor−old.valueFor; 0 ⇒ no toast). Same-instance no-op when no faction kill (mirrors Salvage's contract).
4. `core/src/main/kotlin/com/orbitalfrontier/combat/LootTable.kt` — **optional**: add a loot profile for the new archetype (else falls back to `DEFAULT`, verified L103). Not required by any AC.
5. `core/src/main/kotlin/com/orbitalfrontier/world/MvpSectorMap.kt` — add ONE `EncounterZone` spawning the faction archetype. **TOP IMPLEMENTATION RISK:** must be authored at coordinates/sector **no existing fixture traverses** (mirrors the existing natural/bounty disjointness note) or it perturbs existing replays.
6. `core/src/main/kotlin/com/orbitalfrontier/notify/NotificationKind.kt` — add `REPUTATION_CHANGED(WARNING, coalescable=true)` (renderer keys off severity ⇒ no renderer change).
7. `core/src/main/kotlin/com/orbitalfrontier/notify/GameNotifications.kt` — add `reputationChanged(factionName: String, delta: Int)` builder (ASCII e.g. "TRADE LEAGUE -5"); takes a String to keep `notify` decoupled from `faction`. `forCombatEvent` UNCHANGED.
8. `core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt` — in `stepCombatOnce`, at the existing kill block: after the bounty fold (returns reputation unchanged), call `CombatReputation.applyKills(destroyed, reputation, reputationParams)`, update the `reputation` var, enqueue one `GameNotifications.reputationChanged(Factions.byId(f)?.displayName ?: f.value, delta)` per actual change (device only). Autosave already covered by the existing `hostile-destroyed` event.

**Production docs (developer):**
9. `docs/adr/0031-combat-driven-reputation.md` (NEW, from `_TEMPLATE.md`) — records: attribution = archetype→faction (intrinsic to ship type, not spawn location; rejected zone→faction); direction = negative-on-kill (clamped at min −100; `independents` choice means kills can lock the Gamma-junkyard ≥10 gate — deliberate AC#3 behavior); single-faction MVP (no relationship graph; seam noted for future UC); no schema bump.
10. `docs/adr/README.md` — add the ADR-0031 index row.
11. `docs/design/combat.md` — short note: combat kills feed faction reputation via the `Reputation.with` seam (see ADR 0031).

**Lockstep mirror + DTO (developer, in test source per project rule #1):**
12. `core/src/test/kotlin/com/orbitalfrontier/sim/Simulation.kt` — apply the SAME `CombatReputation.applyKills` to `bountyReputation` after the bounty fold; both returns already consume that var. No notifications (headless — replay asserts the standing delta, not the toast).
13. `core/src/test/kotlin/com/orbitalfrontier/playthrough/Playthrough.kt` — add `combatKillDelta` to `ReputationParamsDto` with `@EncodeDefault(NEVER)` + domain default (class already `@OptIn(ExperimentalSerializationApi)`); update `from()`/`toReputationParams()`. The only new serialized field; keeps all committed fixtures byte-identical (omitted on disk; old artifacts decode via the default). `SimulationState.reputation` already exists — no change.

## Files Affected
**Production code (developer):** faction/ReputationParams.kt, combat/Hostile.kt, combat/CombatReputation.kt (new), combat/LootTable.kt (optional), world/MvpSectorMap.kt, notify/NotificationKind.kt, notify/GameNotifications.kt, screen/PlayScreen.kt.
**Production docs (developer):** docs/adr/0031-combat-driven-reputation.md (new), docs/adr/README.md, docs/design/combat.md.
**Lockstep mirror + DTO (developer, in test source per rule #1):** sim/Simulation.kt, playthrough/Playthrough.kt.
**Test code (qa):** combat/CombatReputationTest.kt (new: faction kill applies delta; null-archetype no effect; floor clamp; same-instance no-op); faction reputation/params test (combatKillDelta validation); notify/GameNotificationsTest.kt (+NotificationQueueTest if it enumerates kinds) for the new builder/kind; playthrough/Uc43CombatReputationReplayTest.kt (new) + committed fixture JSON + PlaythroughRecorder builder + PlaythroughFixtures registration (UC43 const + ALL map) — destroys a faction ship and asserts the standing delta persists across the snapshot round-trip (AC#5). Existing ReputationPersistenceTest already covers reputation save/reload (AC#3). QA must confirm the PlaythroughFixtureTest regenerate-and-compare guard stays green across ALL committed fixtures (disjointness check).

## Risks & Considerations
- **Fixture stability (top risk):** hinges on RAIDER/SCAVENGER staying faction-null and the new zone being disjoint from all recorded paths. RAIDER is the only zone-spawned archetype today; UC41 asserts "reputation unchanged" — preserved. Regenerate-and-compare guard is the safety net.
- **AC#2:** no relationship model ⇒ single-faction MVP (stated in ADR + code comment).
- **Direction/magnitude [TUNE]:** negative; clamps at min −100; premium gates (≥10) stay locked when standing drops — AC#3 "reflected wherever read" holds.
- **Faction choice:** `independents` (fringe, naturally hostile). New factions would need no schema change (slug-keyed, graceful unknown-slug handling) but none is added here.

**Challenger status: APPROVED** — all 5 ACs, all 3 pitfalls, all 7 project-specific enforcement points (seam reuse, Simulation lockstep, determinism, fixture stability, no-schema-bump persistence, neutral/notification handling, UC41/42 independence) satisfied; no outstanding Critical/Major issues. Cleared for the developer to implement against this plan.
