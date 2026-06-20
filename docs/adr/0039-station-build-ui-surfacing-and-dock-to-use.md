# ADR 0039 — Station build UI, world-surfacing & dock-to-use (owned stations)

- **Status:** Accepted
- **Date:** 2026-06-20

## Context

UC15 (ADR 0014) built the owned-station **sim + persistence** only: a player can found/expand a
personal station while docked at a build-capable station, and the `owned_station` / `station_module`
rows persist the choice + module state + anchor sector. But ADR 0014 explicitly **deferred** the
build/edit UI, world surfacing, placement, and dock-to-use, and left **placement an open question**.
The result was a station you could build but never see or use: `StationHubScreen` BUILD fired a default
`FoundStation(firstModule)` directly, owned stations were never rendered/minimapped, and
`OwnedStation.availableFunctions()` (COMMERCE/RETROFIT) was a seam with no consumer.

UC51 lands the missing surface: a build/edit screen (AC#1), world + minimap surfacing with labels
(AC#2, composing with UC24), dock-to-use of the modules' functions (AC#3), persistence of build/
placement/module state across save/reload (AC#4), and a playthrough that builds a module-bearing
station and uses one of its functions (AC#5). Binding constraints: minSdk-24 (no `java.time` in prod,
no SQLite UPSERT), SOLID (`docs/coding-guidelines.md`), and the determinism / record-replay harness
(ADR 0006) — every existing fixture must stay byte-identical.

## Options considered

| Option | For | Against |
|---|---|---|
| **Project owned stations as synthetic `Station` POIs at render/dock time; no schema bump** | Reuses every existing renderer / `MapLabels` / `Docking` / trade+outfit desk / hub with zero changes (a synthetic `Station` is a transponder carrying markets); honors ADR 0014 (never authored into the fixed graph); placement a pure function of id ⇒ re-derived on load, no new column; strictly additive ⇒ zero fixture regen | One more indirection (`effectivePois = sector.pois + projection`) the render/dock/market paths must thread through |
| Author owned stations into the live `Sector` graph | Renderers see them with no projection | Violates ADR 0014 (owned stations are player state, not authored data); mutates the fixed graph at runtime; breaks the "save stores position-in-graph, not a copy of the graph" model |
| Add a persisted placement column (schema v23) | Placement explicit in the save | Unnecessary bump + migration + regenerated `.db` + tripwire-test churn for data that is a pure function of (anchor, id); contradicts the ADR 0007 "reconstruct, don't persist" philosophy |
| Fork a dedicated owned-station hub screen | Simple per-screen logic | Duplicates refuel/mission/trade/outfit wiring (pitfall #4); two hubs to keep in sync |

## Decision

**Project, don't author; compose, don't fork; no schema bump.**

- **Projection.** A pure `OwnedStationProjection` turns each `OwnedStation` anchored in a sector into a
  synthetic `Station` POI (namespaced `PoiId` `owned-station-<id>`, derived position, displayName
  "Outpost N", a commerce trade desk iff a COMMERCE module is installed, a retrofit outfit desk iff a
  RETROFIT module is installed, **all other station capabilities off**). The world/minimap/overlay
  renderers, `MapLabels`, `Docking`, the docked trade/outfit desks and the hub routing all consume
  `effectivePois = sector.pois + projection` and a single `resolveDocked(...)` lookup — **zero renderer
  changes**. An owned station is a transponder, so it shows unconditionally on the minimap **and** the
  zoomed overlay (challenger #4) and labels via UC24.
- **No schema bump (v22 stays).** Build choices + module state + anchor sector are already persisted by
  v22. **Placement is a pure deterministic function of (anchor sector, station id)** re-derived
  byte-identically on reload, and owned markets are reconstructed from authored data (ADR 0007/0008),
  so neither needs a column. `SaveVersion.CURRENT` stays **22**; no new `.sqm` / `.db`; the v22→23
  tripwire tests are untouched.
- **Placement (the ADR 0014 open question — decided).** A **fixed per-sector lattice fanned by station
  id**, based near `(0, -600)` (`OwnedStationPlacement`). The lattice maps slot (= the globally-unique
  station id) to `(column = id % COLUMNS, row = id / COLUMNS)`, which is **injective**, so positions are
  pairwise-distinct for arbitrary N (challenger #1). The fan sits well south of Alpha Station (`0, 600`)
  and clear of the `alpha-raider-picket` zone (`900, 0`, r260) east of centre. All offsets are `[TUNE]`.
- **Build UI.** A new `StationBuildScreen` renders the pure `StationBuildMenu` (a found-station option
  per module + an expansion `BuildModule` option per owned station × module, each with a cost preview +
  affordability from the shared pure `StationBuildCost.canAfford`, now the single source of truth the
  `StationBuilder` deducts against). CONFIRM routes to the existing `PlayScreen.build` (pure
  `StationBuilder`) — the gate stays in the resolver; the screen is a thin view.
- **Compose, don't fork.** Owned stations reuse the single `StationHubScreen`, gated by a new
  `HubService` enum + `enabledServices` set. The **default is the full set**, so an authored station's
  button set is byte-for-byte unchanged (guard-pinned); an owned station passes only the
  COMMERCE→TRADE / RETROFIT→OUTFIT services its modules expose (`OwnedStationServices.hubServices`) +
  UNDOCK — no duplicated refuel/mission/crew/shipyard/fleet/disembark services (pitfall #4).
- **Lockstep.** `OwnedStationProjection.resolveDocked` and the `Docking` `extraStations` overloads are
  the single pure functions reused by both the device `PlayScreen` and the test-set `Simulation`.

## Consequences

- **Determinism preserved (primary).** Strictly additive + default-off: with zero owned stations the
  projection is empty ⇒ `effectivePois == sector.pois` and the docked lookup is unchanged ⇒ every
  existing fixture is byte-identical. No existing fixture builds *and docks at* an owned station (only
  `uc15-station` founds one, never docks), so all existing replays are byte-identical; the new
  `uc51-owned-station` fixture is the only new-path replay.
- **Save/reload (AC#4).** A built station's placement + markets re-derive identically after a
  persistence cycle (pure function of id + reconstructed authored data), demonstrated by a
  save→reload placement-equality assertion (challenger #2).
- **Synthetic `PoiId` in `dockedStation`/`lastDockedStation`** persists/reloads fine; the save can
  resume docked at an owned station (`resolveDockedStation` resolves the projection). `resolveRespawnLocation`
  won't find an owned synthetic id and falls back to the start sector — **respawn-at-owned-station is
  deferred** (safe).
- **`[TUNE]`** owned-market prices/parts and placement offsets are provisional placeholders.
- **Explicitly still deferred** (recorded so scope stays disciplined): station **defense**, **passive
  income**, **crew-staffing**, and **teardown/removal** of an owned station; per-seed placement
  variance; respawn-at-owned-station.
