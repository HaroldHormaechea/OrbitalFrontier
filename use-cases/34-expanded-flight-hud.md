# Use Case 34: Expanded flight HUD

## Summary
Expand the flight HUD beyond its current three readouts (SPEED, HDG, FUEL, plus "LOW"/"IN COMBAT" flags). Add the information a player needs to make decisions without opening a menu: **credits**, **cargo-hold fill** (used/total), **current sector name**, and an **active-objective line** for the accepted mission (target + progress). Readouts must lay out cleanly with the UC22 minimap band and UC26 action arc without overlap, across supported screen sizes.

## Acceptance Criteria
1. The HUD shows current credits, cargo fill (used/total or %), and the current sector name in addition to the existing speed/heading/fuel.
2. When a mission is accepted, an objective line shows the target and progress (e.g. "Mine 6 Titanium — 4/6"); it hides when no mission is active.
3. New HUD elements respect existing layout reservations (UC22 minimap, UC26 arc) and do not overlap at supported resolutions.
4. Values update live each tick from the pure simulation state.
5. `./gradlew :core:ktlintCheck :core:test` green; the HUD layout guard test is extended to the new elements.

## Potential Pitfalls & Open Questions
- **Edge case** — small screens: prioritize which readouts are always visible vs. collapsible if space is tight.
- **Dependency** — the objective line depends on accepted-mission state (UC12) and is the lightweight precursor to full map markers (see UC for active-mission map markers).
- **Risk** — text additions interact with the font work (UC28); keep labels short until the real font lands.

## Original Description
Autonomously captured from the UI/UX analysis — the HUD currently shows only speed/heading/fuel; credits, cargo, sector, and objective are absent.
