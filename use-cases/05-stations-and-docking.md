# Use Case 05: Stations & docking

## Summary
Add **station POIs** to sectors, broadcasting transponders so they appear on the minimap/HUD, plus a **dock/undock** flow that opens a station-hub screen. The hub is the entry point that later UCs fill in (trade, outfitting, missions) — for now its service entries are labeled stubs. Docking is part of game state and persists (you can save while docked and reload docked). Builds on UC03 (sectors/POIs) and UC04 (persistence); foundation for economy (UC08), upgrades (UC09), and missions (UC12).

## Acceptance Criteria
1. Stations exist as POIs in sectors (data-driven) and broadcast a transponder so they appear on the minimap/HUD.
2. Flying within range plus a dock action docks the ship; an undock action returns to flight.
3. Docking opens a station-hub screen with labeled service entries (trade, outfit, missions) that are inert stubs to be wired by later UCs.
4. Dock state (docked + which station) is part of game state and persists (save while docked → reload docked).
5. Dock/undock logic is pure and JVM-testable.
6. A recorded playthrough (UC02) flies to a station, docks, and asserts the docked state (and station id).
7. UI/camera transitions are handled; existing flight controls/multitouch are unaffected when undocked.

## Potential Pitfalls & Open Questions
- **Ambiguity** — Docking trigger: proximity + explicit dock action (chosen) rather than auto-dock.
- **Risk** — Station-hub scope creep: keep service entries as stubs here; real services land in their own UCs.

## Original Description
Autonomously captured from the World & Sector design note (stations as a POI type and services hub) per the owner's request to capture every prepared system.
