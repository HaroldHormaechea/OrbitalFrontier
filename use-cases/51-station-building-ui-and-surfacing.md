# Use Case 51: Station-building UI & world surfacing of owned stations

## Summary
Give owned stations a real interface and a presence in the world. Today station building (UC15) is sim + persistence only: `StationHubScreen` BUILD "fires a default build order directly; the full build UI is deferred" (ADR 0014), owned stations are **not surfaced in the sector/minimap**, and there is no way to **dock at your own station to use its functions** (`availableFunctions` is a seam with no consumer). Add a **build/edit screen** (choose modules, expand the station, preview cost), **place/surface owned stations** in a sector and on the minimap, and let the player **dock at an owned station to use its commerce/retrofit modules**.

## Acceptance Criteria
1. A build/edit screen lets the player choose modules and station expansions with a cost preview, replacing the direct default-build action.
2. Owned/built stations appear in their sector and on the minimap/map overlay with labels (UC24).
3. The player can dock at an owned station and use the functions its installed modules provide (e.g. Commerce Hub → trade desk, Retrofit Bay → outfitting), driven by `availableFunctions`.
4. Build choices, station placement, and module state persist across save/reload.
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough builds a module-bearing station and uses one of its functions.

## Potential Pitfalls & Open Questions
- **Open question** — ADR 0014 leaves placement undecided (jump points, near asteroids, mobile?); pick a default and record it.
- **Decision** — module catalog breadth/costs are `[TUNE]`; defense, passive income, crew-staffing, and teardown are explicitly out of this UC (separate later work).
- **Edge case** — docking at an owned station must compose with the existing station hub flow without duplicating refuel/mission services unintentionally.

## Original Description
Autonomously captured from the feature catalog (station-building.md + ADR 0014: build screen, world surfacing, and dock-to-use all deferred) and code (StationHubScreen build UI deferred; availableFunctions seam unused).
