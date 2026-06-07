# Use Case 10: Active scanning & hidden contacts

## Summary
Implement detection: **transponder-broadcasting** POIs/ships (stations, gates, civilian traffic) appear on the minimap/HUD automatically (baseline from UC03/UC05), while **hidden contacts** (ships/objects running without a transponder) are not shown until the player runs an **active scan** — a ship sensor ability with a **sensor range** stat improvable via a sensors upgrade (UC09). Revealed contacts persist (stay known once scanned). Builds the world's information layer used by missions (UC12) and combat (UC13).

## Acceptance Criteria
1. POIs/ships with transponders appear on the minimap/HUD automatically.
2. Hidden (no-transponder) contacts are not shown until scanned.
3. A scan action reveals hidden contacts within the ship's sensor range (a stat improvable via a sensors upgrade from UC09).
4. Revealed contacts persist (remain known after scanning) across save/reload.
5. Scanning logic is pure and JVM-testable.
6. A recorded playthrough (UC02) scans near a hidden contact and asserts it becomes known; and asserts a contact outside range stays hidden.

## Potential Pitfalls & Open Questions
- **Missing input** — Scan range/time and what flags a contact "hidden" are data-driven placeholders.
- **Edge case** — Re-hiding: once revealed, contacts stay known (do not re-hide on leaving range) for MVP.

## Original Description
Autonomously captured from the World & Sector design note (transponders + active scanning sensor system) per the owner's request to capture every prepared system.
