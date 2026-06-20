---
plan_for: use-cases/54-additional-poi-types.md
work_branch: feat/uc-54-additional-poi-types
team: orbital-frontier-uc-54
approved: 2026-06-20
---

# UC-54 — Additional POI types (derelicts, distress signals, hazards) — FINAL, challenger-APPROVED (no Critical/Major; 3 Minor conditions folded in). Gate: `./gradlew :core:ktlintCheck :core:test`.

## Solution summary
Add three additive `Poi` subtypes to the **MVP map, Beta sector**, at coordinates provably disjoint from every committed fixture's Beta path. Reuse (not fork) UC-10 detection, UC-13/45 spawning, UC-42 loot, and the UC-06/10 seed+delta persistence pattern. **Detection split (AC#3):** distress + hazard = transponder-visible; derelict = scan-only. **One hazard effect (AC#2):** per-tick fuel drain `[TUNE]`, clamped ≥0 (the `Fuel.speedFactor` floor at `floorSpeedFraction` guarantees no strand — challenger verified in code). Procedural-generator placement is **out of scope** (documented follow-up; the replay harness can't inject a non-MVP world — `ReplayRunner` builds `Simulation` with default `MvpSectorMap.build()`).

## New POI types (Open/Closed subtypes)
- `world/Derelict.kt` — `Poi, Contact` (NOT `Transponder`), scan-only; `salvageRadius`; `contactKind=DERELICT`.
- `world/DistressSignal.kt` — `Poi, Transponder`; `triggerRadius`; `contactKind=DISTRESS`.
- `world/HazardZone.kt` — `Poi, Transponder`; `radius` + fuel-drain-per-tick `[TUNE]`; `contactKind=HAZARD`.
- `world/Transponder.kt` (EDIT) — add `ContactKind.{DERELICT,DISTRESS,HAZARD}`.
- `world/Sector.kt` (EDIT) — `derelicts`/`distressSignals`/`hazardZones` views (mirror `hiddenContacts`).

## Detection (reuse UC-10)
Generalize `world/Scanning.kt` `contactsInRange`/`resolve` to reveal **any `Contact` that is not a `Transponder`** in range (today hard-filtered to `HiddenContact`). HiddenContact behavior unchanged; scan-only `Derelict` reveals into the same monotonic `revealedContacts` set. Distress/hazard auto-show (Transponder).

## Shared pure resolvers (wired into PlayScreen + Simulation in LOCKSTEP; same-instance no-op when not interacting)
- `world/DerelictSalvage.kt` + `ScavengeAction {NONE,SCAVENGE}`: SCAVENGE within `salvageRadius` of an **un-consumed** derelict ⇒ roll loot, fill cargo, mark consumed.
  - **[Condition 1]** Loot source: extract `LootTable.roll(loot: ArchetypeLoot, seedKey)` core (the existing `roll(archetypeId, seedKey)` already does `lootFor(archetypeId)` then rolls — trivial, zero-risk extraction) and author a first-class `LootTable.DERELICT: ArchetypeLoot` (resources/parts, `[TUNE]`). Roll keyed `"derelict:$id"`. **No fake hostile archetype.**
  - **[Condition 2]** Cargo fill: extract the multi-resource, `ResourceType`-ordinal-order, capacity-respecting fill loop now inside `Salvage.collect` into ONE pure shared helper (e.g. `Salvage.fillCargo(cargo, resources) → (cargo, accepted, overflow)`, over `Cargo.add`). BOTH `Salvage.collect` and `DerelictSalvage` call it. **Duplicating the fill = Major reuse violation — do not.**
- `world/DistressEvent.kt`: edge-triggered (outside→inside `triggerRadius`, suppressed while `combat.active`) on an un-consumed signal; branch **reward XOR ambush** via `DeterministicRng.fnv1a("distress:$id")` (fresh namespace). Reward ⇒ fold credits/cargo (via the shared fill helper); ambush ⇒ `EncounterSpawner.missionSpawn(zoneId="distress:$id", seed=tick)`. Marks consumed.
- `world/HazardEffect.kt`: per-tick fuel drain while inside `radius`; same-instance no-op outside. Pure, no RNG, no persistence.

## Persistence (AC#4) — one new save-wide delta `consumedPois: Set<PoiId>` (mirrors `revealedContacts` exactly)
- `world/WorldState.kt` + `sim/SimulationState.kt` (EDIT): add `consumedPois: Set<PoiId> = emptySet()`.
- `playthrough/Playthrough.kt` `StateSnapshotDto` (EDIT): `@EncodeDefault(NEVER) consumedPois: List<String> = emptyList()` (sorted slugs) — pre-UC54 fixtures omit the key ⇒ byte-identical.
- **Schema bump v23 → v24** (confirmed no generic key-value delta table exists — `revealed_contact`/`field_deposit` are purpose-built; new table justified):
  1. `save/migrations/23.sqm` (NEW): `CREATE TABLE consumed_poi (slot_id INTEGER NOT NULL, poi_id TEXT NOT NULL, PRIMARY KEY(slot_id, poi_id));` + `UPDATE meta SET save_version = 24 WHERE id = 0;` — additive, minSdk-24-safe, **NO UPSERT** (precedent `20.sqm`).
  2. `save/OrbitalFrontier.sq` (EDIT): same `CREATE TABLE` + `selectConsumedPoisForSlot` / `insertConsumedPoi` (INSERT OR IGNORE) / `deleteAllConsumedPoisForSlot`; update header comment.
  3. Regenerate baseline `sqldelight/databases/24.db` via `./gradlew :core:generateMainOrbitalFrontierSchema` (verifyMigrations=true) — commit it.
  4. `save/SaveVersion.kt`: `CURRENT = 24L`.
  5. `save/SqlDelightGameStateRepository.kt`: `loadConsumedPois` (mirror `loadRevealedContacts` ~line 625), insert loop in save (mirror revealed ~lines 306-308), add `deleteAllConsumedPoisForSlot` to clear/overwrite path (~line 524).

## Lockstep mirror (DEVELOPER-owned, under src/test — brief invariant #3)
Add `scavengeAction` param (default NONE) to `Simulation.step` + the three resolver calls in the in-flight branch (after mining/scan, around the combat spawn block); thread `consumedPois`. Mirror identically in `screen/PlayScreen.kt`. Plus `playthrough/ReplayRunner.kt` (`scavengeActionFor` + pass param) and `playthrough/InputEvent.kt` (`ScavengeEvent`). Defaults keep every existing call site/fixture byte-identical.

## UI (invariant #7)
New `ContactKind` glyph cases in `render/MinimapRenderer.kt` + `render/MapOverlayRenderer.kt` (`when(contactKind)`); **mandatory** new cases in `render/WorldGlyphs.kt` (exhaustive `when(poi)`, no `else` — compile-forced). Result messages reuse the UC-35/40 notification seam. Pin screen wiring with source-anchored `Uc54PoiSurfacingSourceTest` (precedents `Uc44CombatHudSourceTest`, `Uc40EconomyFeedbackSourceTest`).

## Docs
`docs/adr/0042-additional-poi-types.md` (NEW): taxonomy, distress branch rule, hazard effect, seed+`consumedPois` delta, zero-regen Beta-placement decision. `docs/design/world-and-sector.md` (EDIT): flip the "Later: derelicts/…" note to implemented.

## Files Affected
**Production (developer):** `world/Derelict.kt`, `world/DistressSignal.kt`, `world/HazardZone.kt`, `world/DerelictSalvage.kt`, `world/DistressEvent.kt`, `world/HazardEffect.kt` (new); `world/Transponder.kt`, `world/Scanning.kt`, `world/Sector.kt`, `world/MvpSectorMap.kt`, `world/WorldState.kt`, `combat/LootTable.kt`, `combat/Salvage.kt` (edit); `save/OrbitalFrontier.sq`, `save/migrations/23.sqm`, `save/SaveVersion.kt`, `save/SqlDelightGameStateRepository.kt`, `sqldelight/databases/24.db`; `screen/PlayScreen.kt`, `render/WorldGlyphs.kt`, `render/MinimapRenderer.kt`, `render/MapOverlayRenderer.kt`; `docs/adr/0042-*.md`, `docs/design/world-and-sector.md`.
**Developer-owned lockstep mirror (src/test):** `sim/Simulation.kt`, `sim/SimulationState.kt`, `playthrough/ReplayRunner.kt`, `playthrough/InputEvent.kt`, `playthrough/Playthrough.kt`.
**Test (QA):** `DerelictSalvageTest`, `DistressEventTest` (BOTH branches), `HazardEffectTest` (incl. assert `floorSpeedFraction > 0`), generalized `ScanningTest`, `ConsumedPoiPersistenceTest`; tripwires `SqlDelightSettingsRepositoryTest` (23L→24L, lines 69/79) + `SaveMigrationTest` (new v23→v24 step asserting literal 24 + consumed_poi round-trip); **AC#5 fixture** — builder in `PlaythroughFixtures.ALL`, committed `uc54-additional-poi.json`, `Uc54AdditionalPoiReplayTest` (Beta: scavenge derelict, trigger distress, traverse hazard) **[Condition 3]** asserting the concrete branch outcome its authored id resolves to (reward ⇒ credits/cargo delta; ambush ⇒ `combat.active`+hostile). Role agents MUST NOT edit `USE_CASES.md`.

## Binding risks / proof obligations
1. **ZERO existing-fixture regen (overriding):** disjoint Beta placement; action-/geometry-gated triggers; fresh RNG namespaces; empty defaults + `@EncodeDefault(NEVER)` + additive empty table. **QA MUST empirically replay the full fixture set** — enumerate `PlaythroughFixtures.ALL` + every `Uc*ReplayTest` + `NamedPlaythroughReplayTest` (~22, no hard-coded count) and confirm unchanged end-states/bytes.
2. **`encounterZones(alpha).single()`** (UC-42): distress ambush uses `missionSpawn` and is **NOT** added to `ENCOUNTER_ZONES`; distress lives in Beta — Alpha's list untouched.
3. **Hazard no-brick:** drain clamped ≥0; `floorSpeedFraction>0` floor lets the ship limp out (verified).
4. **DTO byte-stability:** `consumedPois` MUST be `@EncodeDefault(NEVER)` (codec sets global `encodeDefaults=true`).

## Challenger verdict
**APPROVED** (no Critical/Major). Verified against code: detection seam, Simulation MVP-map default + no worldSeed in ReplayRunner, revealed_contact persistence precedent, SaveVersion.CURRENT=23L + migration cadence, Fuel.speedFactor floor (non-bricking hazard), exhaustive WorldGlyphs when(poi), LootTable/Salvage/EncounterSpawner/DeterministicRng reuse seams. 3 Minor conditions folded in: (1) named DERELICT loot archetype (no fake hostile); (2) SHARED cargo-fill helper (duplication = Major QA fail); (3) AC#5 fixture asserts the concrete distress branch outcome. Recommendations: enumerate the real ~22 fixture set (not a hard-coded count); confirm floorSpeedFraction>0; confirmed no existing generic delta table before the bump.
