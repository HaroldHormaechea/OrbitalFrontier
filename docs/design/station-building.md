# Design Note — Station Building (post-MVP stretch; UC15 sim + UC51 build UI / surfacing / dock-to-use built)

- **Status:** in-progress — the **sim model + persistence** (UC15, ADR 0014) AND the **build/edit UI, world-surfacing + placement, and docking-to-use** (UC51, ADR 0039) are built; **defense, passive economics, crew-staffing, teardown/removal, and respawn-at-owned-station** stay deferred. Placement was the ADR 0014 open question — **decided** in UC51 (a fixed per-sector lattice fanned by station id near `(0,-600)`, re-derived deterministically on load, no save column).
- **Last updated:** 2026-06-20
- **Related:** PROJECT_BRIEF.md → non_goals #2 (stretch, lowest priority), core_gameplay_loop (Improve, deeper); **[ADR 0014](../adr/0014-owned-stations.md)** (the sim/persistence decision) + **[ADR 0039](../adr/0039-station-build-ui-surfacing-and-dock-to-use.md)** (build UI, surfacing, placement, dock-to-use); [economy-and-resources.md](economy-and-resources.md) (credits/resources cost, commerce), [upgrades-and-progression.md](upgrades-and-progression.md) (retrofit), [world-and-sector.md](world-and-sector.md) (where stations sit), [save-and-persistence.md](save-and-persistence.md) (additive v13 migration)

## Summary

An "Improve, deeper" layer: the player builds and grows **multiple personal stations** used
for **commerce, retrofit, and more**, from **modular snap-together pieces**, paid for with
**credits and/or mined resources**. Single-player and offline like the rest of the game (no
networked base-building). The lowest-priority stretch UC — UC15 builds the **smallest coherent
slice** that satisfies the six acceptance criteria: a pure, deterministic, JVM-testable
ownership + build model that persists additively. The richer surfacing (UI, world placement,
using a built station's functions, defense, economics) is deliberately deferred — see
[ADR 0014](../adr/0014-owned-stations.md).

## Goals

- A long-term progression/credit/resource sink beyond ship upgrades, with no multiplayer.
- Player-owned infrastructure that plugs into the existing economy (commerce) and
  outfitting (retrofit) systems.

## Mechanics (UC15 — built)

The model lives in the pure `com.orbitalfrontier.station` package (no engine types, fully
JVM-testable, AC#5) — the station analogue of the `ship` (fleet) + `outfit` packages:

- **Multiple owned stations** (AC#3) — `StationRegistry` (the `Fleet` analogue): a
  sorted-unique list of `OwnedStation`, defaulting to `EMPTY`. It only ever **grows** in the
  MVP (stations are founded and gain modules; never removed). Station ids are allocated
  `max(id) + 1` (pure, no counter/clock — replay-stable).
- **Modular construction** (AC#1) — an `OwnedStation` holds a gap-tolerant
  `Map<slotIndex, StationModuleId>` (the `Loadout` analogue), anchored in a `SectorId`. A
  `StationModule` (in `StationModuleCatalog`, authored data resolved on load — never persisted)
  exposes one `StationFunction` and a `StationBuildCost`.
- **Functions** (AC#2) — `StationFunction { COMMERCE, RETROFIT }`. `OwnedStation
  .availableFunctions()` derives the set its built modules expose.
- **Cost: credits and/or resources** (AC#1) — `StationBuildCost(credits, resources)`; resources
  are drawn from the active ship's cargo hold. The MVP catalog: **Commerce Hub**
  (`commerce-hub-i`, COMMERCE) and **Retrofit Bay** (`retrofit-bay-i`, RETROFIT). All costs are
  **`[TUNE]`** placeholders.
- **The build resolver** — `StationBuilder.resolve(...)` is pure and deterministic (the
  `FleetResolver`/`Outfitting` analogue): `FoundStation(moduleType)` founds a station at the
  docked station's sector with that module in slot 0; `BuildModule(stationId, moduleType)` snaps
  a module onto an owned station's lowest free slot. Both are **docked-only and gated on the
  docked station's `buildsStations` capability flag** (a station capability, like `hiresCrew` —
  NOT a new `StationKind`). Affordability is **atomic** (credits + every resource checked up
  front; all-or-nothing; a shortfall is a no-op). In the MVP, **Alpha Station** (start sector)
  is the one build-capable station.

## Player-facing behavior

- **Built (UC15):** while docked at a build-capable station, the player can found a personal
  station (and, via the resolver, add modules); ownership and the modules' functions persist
  across save/reload. A recorded playthrough (UC02) founds a module and asserts ownership +
  function availability (AC#6).
- **Deferred (post-MVP, ADR 0014):** the dedicated **build/edit screen** (module choice,
  expansion). UC15 wires only a minimal `BUILD` hub **action** (shown at a build-capable
  station) that fires a default `FoundStation` order through `PlayScreen.build` — proving the
  device→resolver→persist path without a new screen. How owned stations are **placed and
  surfaced** in the world / on the minimap, and **docking to use** a built station's commerce
  or retrofit function, are also deferred (today `OwnedStation` carries only its anchor sector).

## Data & state

- **Built (UC15, additive schema v12 → v13 — ADR 0014):** owned stations are save-wide player
  state on `WorldState.stations`. Two new tables — `owned_station(id PK, sector)` and
  `station_module(station_id, slot_index, module_type, PK(station_id, slot_index))`. A station's
  modules are a full-snapshot rewrite (delete-then-insert, minSdk-24 safe, like `ship_upgrade`);
  the `owned_station` row is upserted. Stations only grow, so there is no delete-station query.
  The module catalog (functions/costs) is authored data reconstructed on load — only id slugs
  are stored; an unknown slug is skipped with a WARN. The `12.sqm` migration creates the two
  empty tables, so a migrated pre-UC15 save reads back with **zero** owned stations
  (byte-identical to a game that never built one). The UC04 schema-extensibility **guardrail
  below was honored** — this landed as a purely additive migration with no breaking change (AC#4).
- **Guardrail (still holds for future station data):** keep player-owned entities modeled so
  further station state (placement, economics, crew) can be appended as new versioned migrations
  without a breaking change (ADR 0002 / [save-and-persistence.md](save-and-persistence.md)).

## Dependencies & interactions

- **Economy** (build costs paid in credits + mined resources — built; commerce/passive income —
  deferred), **upgrades/progression** (retrofit — function modeled, surfacing deferred),
  **world & sector** (placement near asteroids / jump points — deferred), **combat** (defense —
  deferred), **crew** (staffing — deferred).

## Open questions (deferred)

- Module catalog breadth and balancing (costs are `[TUNE]`); what each future module provides.
- Placement (anchored in a sector, at jump points, mobile?) and how owned stations surface in
  the world and on the map.
- Using a built station's functions (docking to trade / refit at your own station).
- Defense vs. purely economic/utility; passive economics; crew needs; station teardown.

## References

X4 Foundations player stations (modular, single-player); Starminer modular stations. See
PROJECT_BRIEF.md → Reference Points & Inspiration.
