---
plan_for: use-cases/51-station-building-ui-and-surfacing.md
work_branch: feat/uc-51-station-building-ui
team: orbital-frontier-uc-51
approved: 2026-06-20
---

# UC-51 (Station-building UI, world surfacing & dock-to-use) — FINAL approved proposal (challenger approved; 4 Minor recommendations folded in). Gate: `./gradlew :core:ktlintCheck :core:test`. No active profiles.

## Analysis
UC-15 (ADR 0014) built the station **sim + persistence** only; the build screen, world surfacing, and dock-to-use were explicitly deferred. Current state verified: `StationHubScreen` BUILD fires a default `FoundStation(first module)` (`OrbitalFrontierGame.kt:514`); owned stations are player state on `WorldState.stations` (`StationRegistry`, each `OwnedStation` = id + anchor `SectorId` + modules), NOT authored POIs, so they don't render/minimap and aren't dockable; `availableFunctions()` (COMMERCE/RETROFIT) is an unused seam. Persistence is **schema v22**; `owned_station(slot_id,id,sector)` + `station_module(...)` already persist build choices + module state + anchor sector (no placement column). Surfacing infra is reusable: `Station` is `Transponder`/`Named`/`ContactKind.STATION`; the minimap/overlay/world renderers key off `sector.pois`; `MapLabels.shouldLabel` labels STATION (UC24); `Docking` keys off `sector.stations`; trade/outfit desks use `station.market`/`station.outfitMarket`.

## Core design — "project, don't author; compose, don't fork"
Owned stations stay player state. A **pure projection** turns each `OwnedStation` anchored in a sector into a synthetic `Station` POI at render+dock time. Since a synthetic `Station` is a STATION transponder carrying markets, the existing renderers, `MapLabels`, `Docking`, the docked trade/outfit composition, the desks, and the hub routing all work on it with **zero renderer changes** — fed `effectivePois = sector.pois + projection`. Honors ADR 0014 (never authored into the fixed graph) while satisfying AC#2/#3.

### Schema — NO bump (v22 stays)
AC#4 is met by existing v22 rows (build choices + module state + anchor sector). **Placement is a pure deterministic function of (anchor sector, station id)** → re-derived byte-identically on reload, so no column — the same way positions/markets are derived, not persisted. `SaveVersion.CURRENT` stays 22; no new `.sqm`/`.db`; the v22→23 tripwire tests (`SqlDelightSettingsRepositoryTest`, `SaveMigrationTest`) are untouched. Recorded in the new ADR.

### Placement (ADR 0014 open question — decided)
**Fixed per-sector slot, fanned by station id**, base near `(0,-600)` (clear of Alpha at `(0,600)` and the `alpha-raider-picket` zone east of centre). `[TUNE]`. Pure `OwnedStationPlacement.position(station)`. **[challenger #1]** The fan is deterministically bounded so for arbitrary N the positions are pairwise-distinct and clear of authored POIs/zones; an `OwnedStationPlacement` unit test asserts (a) pairwise-distinct positions for N≥3 in one sector and (b) clearance from Alpha + the picket zone. Rule recorded in the ADR.

## Proposed Solution

### New pure core classes (`core/src/main/kotlin/com/orbitalfrontier/station/`, engine-free, unit-tested directly)
1. **OwnedStationPlacement** — deterministic, bounded, distinct, collision-free slot (above).
2. **OwnedStationMarkets** — authored default `StationMarket` (commerce desk) + `OutfitMarket` (retrofit desk); reconstructed on load, never persisted (ADR 0007/0008). `[TUNE]` but **non-empty** so AC#5's "uses one of its functions" is real end-to-end.
3. **OwnedStationProjection** — `stationsIn(sectorId, registry, catalog)` → synthetic `Station` per owned station (deterministic namespaced `PoiId` `owned-station-<id>`, derived position, displayName "Outpost N", `market`=commerce desk iff COMMERCE in `availableFunctions`, `outfitMarket`=retrofit desk iff RETROFIT, all capability flags off so it offers ONLY its module functions); `poisIn(sector,…)` = `sector.pois + stationsIn`; `resolveDocked(world, currentSector, registry, dockedStation, catalog)` → authored station first else owned projection (single pure fn reused by PlayScreen + sim → lockstep).
4. **OwnedStationServices** — `hubServices(functions): Set<HubService>` mapping COMMERCE→TRADE, RETROFIT→OUTFIT.
5. **StationBuildMenu** — pure build/edit-screen state (AC#1): from (catalog, registry, credits, cargo, buildsStations) → options each with label, `StationBuildOrder` (FoundStation **and** BuildModule for expansions), `StationBuildCost`, `affordable`. Extract shared pure `StationBuildCost.canAfford(credits, cargo)` and refactor `StationBuilder.deduct` (`StationBuilder.kt:163`) to use it — single source of truth, **behavior-preserving for `uc15-station`** [challenger rec].

### New GL screen (`core/src/main/kotlin/com/orbitalfrontier/screen/`, source-anchored guard)
6. **StationBuildScreen** — build/edit UI (AC#1): renders `StationBuildMenu` + cost preview + affordability; CONFIRM fires `onBuild(order)` → existing `PlayScreen.build` (`:2545`, unchanged). Mirrors `StationHubScreen`.

### Modified production
7. **StationHubScreen.kt** — **[challenger #3]** add new `HubService` enum + `enabledServices: Set<HubService>` param; gate **every** service button on it (today TRADE/OUTFIT/SHIPS/CREW/FLEET/MISSIONS are added unconditionally, only BUILD gated). Default = full set → regular stations' exact current button set preserved (pinned by a guard test). Owned station passes only {TRADE/OUTFIT as applicable} + UNDOCK (pitfall #4 — no duplicated refuel/mission services). Build-capable regular stations' BUILD routes to `StationBuildScreen` instead of the default order.
8. **OrbitalFrontierGame.kt** — `resolveDockedStation` (`:470`) uses `OwnedStationProjection.resolveDocked`; `openStationHub` (`:476`) passes restricted `enabledServices` + routes TRADE/OUTFIT to `openTradeDesk`/`openOutfitDesk` on the projected station; BUILD wiring (`:510`) opens `StationBuildScreen`.
9. **PlayScreen.kt** — feed `effectivePois` to the 3 renderers (`:1230`,`:1281`,`:1364`) for AC#2; resolve docking against effective stations (`:961`) so owned stations are dockable (AC#3); `dockedStation` may hold a synthetic owned `PoiId`. **[challenger #4]** a just-built owned station is treated as **revealed on both surfaces** (it's a transponder → minimap unconditional; ensure the overlay path also treats owned projections as revealed, since the player built it).
10. **world/Docking.kt** — add pure `availableStation(world, currentSector, shipPosition, extraStations)` overload (unit-tested); PlayScreen + sim use it.

### Modified sim (TEST source set — lockstep mirror, default-off)
11. **sim/Simulation.kt** — dock resolution (`:486`) passes owned-station projections for `nextSector`; docked-station lookup (`:240`) uses `OwnedStationProjection.resolveDocked` so a docked owned station yields its commerce/retrofit markets for Trading/Outfitting; missions/hire/build naturally no-op there (not authored, flags off). **Zero owned ⇒ projection empty ⇒ effectivePois==sector.pois, docked lookup unchanged ⇒ every existing fixture byte-identical.**

## Files Affected
**Production code (developer):** new `station/OwnedStationPlacement.kt`, `OwnedStationMarkets.kt`, `OwnedStationProjection.kt`, `OwnedStationServices.kt`, `StationBuildMenu.kt`; edit `station/StationBuilder.kt` + `station/StationModule.kt` (`canAfford`); new `screen/StationBuildScreen.kt`; edit `screen/StationHubScreen.kt`, `app/OrbitalFrontierGame.kt`, `screen/PlayScreen.kt`, `world/Docking.kt`; new `docs/adr/0039-station-build-ui-surfacing-and-dock-to-use.md` + update `docs/design/station-building.md` status (developer authorized for docs/**).
**Test code (qa):** unit tests for every new pure class + Docking overload + `canAfford` + **placement distinctness/clearance [challenger #1]**; source guards `Uc51StationBuildSourceTest` + `Uc51OwnedStationHubSourceTest` + a hub default-button-set guard [challenger #3] (precedent `Uc44CombatHudSourceTest`/`Uc40EconomyFeedbackSourceTest`); `Uc51StationSurfacingReplayTest` + a **new** `uc51-owned-station` fixture in `PlaythroughFixtures` (build commerce hub at Alpha → undock → fly to derived placement → dock → sell a resource at its commerce desk → assert ownership + COMMERCE used + credits/cargo changed + deterministic across two runs) + committed JSON resource (guarded by `PlaythroughFixtureTest`). **[challenger #2]** add an explicit **save→reload round-trip** assertion that the owned station re-derives to the same position after a persistence cycle (directly demonstrates AC#4 "placement persists").

## Risks & Considerations
- **Determinism (primary):** strictly additive/default-off. Proof obligation for QA: NO existing fixture builds/docks at an owned station (verified — only `uc15-station` founds one, never docks at it) ⇒ all existing replays byte-identical; `uc51-owned-station` is the only new-path fixture.
- **Synthetic PoiId in `dockedStation`/`lastDockedStation`:** persists/reloads fine; `resolveRespawnLocation` (`PlayScreen.kt:1940`) won't find an owned synthetic id → falls back to start sector. Safe; respawn-at-owned-station deferred (noted in ADR).
- **Placement & owned markets `[TUNE]`:** QA verifies dockable + clear of Alpha/zones, and that the commerce desk is non-empty.
- **Scope guard:** defense, passive income, crew-staffing, teardown remain **explicitly deferred** — recorded in ADR 0039.

## ORCHESTRATOR ROLE-SPLIT NOTE (overrides the analyst's listing sim/Simulation.kt under Test/qa)
Per the established lockstep convention, the **DEVELOPER** authors the test-source lockstep mirror `core/src/test/kotlin/com/orbitalfrontier/sim/Simulation.kt` (so PlayScreen's dock/projection wiring and its mirror change together). QA owns ALL other test files (the pure-class unit tests, source guards, the new `uc51-owned-station` fixture in `PlaythroughFixtures.kt`, the replay test, and the save→reload round-trip test).

## Challenger verdict
**APPROVED** (no Critical/Major). Verified: schema already persists owned_station+station_module (no bump — rejected an unnecessary v23); availableFunctions is a real unused seam; synthetic-Station projection reuses renderers/MapLabels/Docking with zero renderer changes (honors ADR 0014); scope disciplined (defense/income/crew/teardown deferred); single StationHubScreen reused (no fork); logic in pure core classes; determinism preserved (no existing fixture docks at an owned station). 4 Minors folded in: placement distinctness/clearance test; save/reload placement-equality test; HubService enum gating all hub buttons while preserving the regular-station set; owned stations revealed for labels on both map surfaces.
