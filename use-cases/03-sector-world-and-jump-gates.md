# Use Case 03: Sector world & fixed jump gates

## Summary
Implement the explorable world: an unbounded 2D sector with content clustered near its center (X-series style), and inter-sector travel via **fixed jump gates** (ADR 0004) across **3 small MVP sectors** (~30s to cross). Sector layout is data-driven (hand-authored test maps now; seed-based procedural later) and includes gate POIs forming a fixed graph. The player flies (UC01) within a sector; entering a gate transports the ship to the linked gate in the destination sector. The camera follows; a minimap shows known POIs. World state (sector graph, current sector, gate links) is modeled in `core`, deterministic, and replayable (UC02). Builds directly on UC01 movement and is the spatial foundation for stations, asteroids, missions, and combat.

## Acceptance Criteria
1. Three sectors exist with a fixed gate graph linking them, defined as data (hand-authored test map).
2. Each sector is an unbounded plane with content near the center; flying far out yields empty space (no hard wall).
3. Flying into a jump gate transports the ship to the linked gate in the destination sector, arriving at that gate's position.
4. Jumps have no fuel cost (MVP) and complete near-instantly with a transition.
5. The current sector and ship position are part of game state and exposed for persistence (full save in UC04).
6. A minimap/HUD shows the current sector's known POIs (at least the gates).
7. The camera follows the ship across the sector.
8. The world/gate model is pure and JVM-testable; gate-traversal logic is unit-tested.
9. A recorded playthrough (UC02) drives the ship through a gate and asserts the resulting current-sector and arrival position.
10. Determinism: identical inputs produce identical sector-traversal outcomes (UC02 guard).

## Potential Pitfalls & Open Questions
- **Edge case** — Avoid immediate gate bounce-back: arrive offset from the destination gate so the ship doesn't re-trigger it.
- **Ambiguity** — "Unbounded" implemented as a very large soft bound; document the chosen extent.
- **Assumption** — Starfield/camera reused from UC01; minimap is the small HUD element (expand-to-fullscreen remains post-MVP).

## Original Description
Autonomously captured from the World & Sector design note (docs/design/world-and-sector.md) and ADR 0004 (fixed jump gates), per the owner's request to capture every prepared system. Folds in the Jump candidate system.
