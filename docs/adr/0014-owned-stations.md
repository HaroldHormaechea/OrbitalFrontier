# ADR 0014 — Player-owned stations: player state in WorldState, station-build capability flag, additive v13 persistence

- **Status:** Accepted
- **Date:** 2026-06-08

## Context

UC15 (the lowest-priority, post-MVP **stretch** use case) lets the player **build and own
multiple personal stations** from modular pieces, paid for with credits and/or mined
resources. Each module exposes a **function** (commerce, retrofit); the player can own
several stations (AC#3); ownership + layout must persist via an **additive** save migration
that does not break existing saves (AC#4 — the UC04 save schema was designed to extend to
this); the logic must be **pure and JVM-testable** (AC#5); and a recorded playthrough (UC02)
must build a module and assert ownership + the module's function availability (AC#6).

This is explicitly the smallest coherent design for the six ACs. The brief lists
station-building as a non-goal/stretch (`PROJECT_BRIEF.md` → non_goals #2,
`core_gameplay_loop` "Improve, deeper"), and `docs/design/station-building.md` flags the
module catalog, placement, defense and economics as deferred/open. The hard constraints are
the determinism invariant (ADR 0006 — pure resolvers, byte-identical replay, a pre-UC15 save
must replay unchanged) and the additive-migration rule (ADR 0002 / 0003). The brief lists no
`## Profiles`.

The open design questions are: (1) where owned stations live in the state model; (2) how the
build action is gated and surfaced; (3) how the module catalog relates to the save; (4) how a
station is identified deterministically; and (5) how much device UI to build now.

## Options considered

| Option | For | Against |
|---|---|---|
| **Owned stations are player state on `WorldState` (a `StationRegistry`, the Fleet analogue); module catalog is authored data resolved on load** | Mirrors the proven fleet/outfitting model (ADR 0008): pure value, `EMPTY` default → pre-UC15 saves byte-identical; catalog retune in one place; saved station stores only module-id slugs | One more save-wide field + two additive tables |
| Owned stations as new authored `Poi` subtypes in the sector graph | Reuses the world/minimap plumbing | Player-built state is **not** authored map data; would entangle the fixed sector graph with mutable player state and break the "rebuild graph from `MvpSectorMap`" model |
| Persist the full module (function + cost) per row | Self-contained rows | Duplicates authored data into the save; a cost/function retune would not reach old saves; diverges from the upgrade-id-slug precedent |
| Build the dedicated device build/edit screen now | Complete UX | Out of scope for a lowest-priority stretch UC; AC#6 exercises only the sim; large UI surface for no AC |
| A new `StationKind` for build-capable stations | Explicit | Wrong axis — building is a **capability** a station has (like `hiresCrew`), not a station *kind*; a kind change would ripple through docking/hub/markets (the same reasoning as ADR 0008's junkyard-as-kind) |

## Decision

1. **Owned stations are save-wide player state on `WorldState.stations`** — a
   `station.StationRegistry` (sorted-unique `OwnedStation` list, the `ship.Fleet` analogue),
   defaulting to `StationRegistry.EMPTY`. A fresh game and every pre-UC15 save read back with
   **zero** owned stations, so the snapshot stays byte-identical and a pre-UC15 playthrough
   replays unchanged. An `OwnedStation` is a pure value: a `StationId`, the anchor `SectorId`,
   and a **gap-tolerant** `Map<Int, StationModuleId>` of built modules (the `outfit.Loadout`
   analogue). It derives `availableFunctions` (AC#2) from its modules through the catalog.

2. **The module catalog is authored data, reconstructed on load — never persisted** (the
   `UpgradeCatalog` / `ShipRoster` precedent, ADR 0007/0008). `StationModuleCatalog.MVP` holds
   the buildable `StationModule`s (id slug, `StationFunction`, display name, `StationBuildCost`
   of credits + resources). A saved station stores only the module **id slug** per slot; an
   unknown slug resolves to null and is **skipped with a WARN** on load (never stranded). All
   costs/the module set are `[TUNE]`.

3. **Building is a pure resolver, gated on a station capability flag.** `StationBuilder.resolve`
   (the `FleetResolver` / `Outfitting` analogue) is a side-effect-free function of
   (registry, credits, cargo, build-capability, sector, order) returning a
   `StationBuildResult`; a no-op returns its inputs unchanged (`changed = false`). Two orders:
   `FoundStation(moduleType)` allocates the next id, anchors a new station in the docked
   station's sector with that module in slot 0, and deducts the cost (the `BuyShip` analogue);
   `BuildModule(stationId, moduleType)` snaps a module onto an owned station's lowest free slot
   (the `BuyInstall` analogue). Both are gated on the docked station's new
   **`Station.buildsStations`** flag — a station **capability** (the `hiresCrew` analogue), NOT
   a new `StationKind`. In the MVP one station (Alpha Station, the start sector) sets it true.

4. **Deterministic id allocation.** `StationRegistry.nextStationId()` is pure `max(id) + 1`
   (0 when empty), the `Fleet.nextShipId` analogue — **no** global counter and **no** time
   source, so id allocation is replay-stable.

5. **Atomic affordability.** A build cost is credits **and/or** a bill of mined resources drawn
   from the active ship's cargo. The resolver checks the credit price **and every** resource
   line up front and deducts **all-or-nothing**; any single shortfall is a no-op
   (`changed = false`) with no partial deduction.

6. **Persistence is additive (schema v12 → v13).** Two new tables: `owned_station(id PK,
   sector)` and `station_module(station_id, slot_index, module_type, PK(station_id,
   slot_index))`. A station's modules are a **full-snapshot** rewrite (delete-then-INSERT,
   minSdk-24-safe) like `ship_upgrade`; the `owned_station` row is upserted. Stations only ever
   **grow** in the MVP (AC#3) — they are never removed — so there is **no** delete-station
   query/path. The `12.sqm` migration creates the two empty tables, so a migrated pre-UC15 save
   reads back with zero owned stations. `SaveVersion.CURRENT` is bumped to 13 and must equal the
   generated schema version (the init-check fails fast otherwise).

7. **No dedicated build UI yet (deferred).** AC#6 exercises only the sim side. The device wires
   `build` as a hub **action** for parity — a `BUILD` row on `StationHubScreen` (shown only at a
   build-capable station) routes to `PlayScreen.build`, which folds the pure resolver's result
   back (post-build cargo onto the active ship only when changed). The hub action fires a default
   `FoundStation` order; the full build/edit screen (module choice, expansion, world placement)
   is **explicitly deferred**.

### Explicitly deferred (recorded as hooks; NOT built in UC15)

- **World surfacing / placement** of owned stations (where they sit in a sector, on the
  minimap, whether dockable). An `OwnedStation` carries only its anchor `SectorId` today.
- **Docking at / using** an owned station's functions (a commerce module opening a trade desk,
  a retrofit module opening an outfitting desk). `availableFunctions` is the seam; no consumer
  wires it into the world yet.
- **Defense** (ties to combat, UC13), **passive economics / income**, **crew staffing**, and
  **station removal/teardown**. The module catalog stays `[TUNE]`.
- **The dedicated build/edit screen** (see decision 7).

These are recorded so a later UC can pick them up without re-deriving the model; none is
required by UC15's acceptance criteria.

## Consequences

- **Determinism preserved.** The builder is pure and returns same instances on a no-op; id
  allocation is `max+1` (no counter/clock); the catalog is authored data resolved on load. A
  pre-UC15 fixture replays byte-for-byte (stations default `EMPTY`), and a UC15 fixture can
  assert the found→own→function-available transition deterministically.
- **Cheap, compact, forward-compatible saves.** Additive migration keeps every prior save
  loadable; only built stations/modules are rows; an unknown module slug degrades gracefully.
- **Reversible / extensible.** New modules or functions are pure authored-data edits; a future
  `BuildModule`-driven expansion, world placement, or defense layer slots onto the existing
  seams (`StationRegistry`, `OwnedStation.availableFunctions`, `Station.buildsStations`) without
  reworking the model.
- **Bounded scope.** The smallest coherent surface for the six ACs; the large deferred surface
  (UI, world surfacing, defense, economics) is documented, not built.
