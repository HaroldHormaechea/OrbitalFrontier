# Use Case 32: Pause overlay

## Summary
Add a **pause overlay** that halts the simulation during flight. Today nothing pauses the game — even the full-map overlay is explicitly LIVE (`MapOverlayLayout.PAUSES_SIMULATION = false`), which is unusual for a mobile title where interruptions are constant. The overlay offers **Resume**, **Settings**, and **Quit to main menu** (with an autosave on quit), and freezes the deterministic tick while open so no game time passes.

## Acceptance Criteria
1. A pause control is reachable from the flight screen (HUD button and/or Android back gesture) and opens a modal pause overlay.
2. While paused, the simulation tick does not advance (no movement, fuel burn, combat, or mission timers progress).
3. The overlay offers Resume, Settings (opens the settings screen), and Quit to main menu.
4. Quitting saves the game first so no progress is lost; resuming continues exactly where the player left off.
5. `./gradlew :core:ktlintCheck :core:test` green, including a test asserting no ticks advance while paused.

## Potential Pitfalls & Open Questions
- **Edge case** — pausing must compose with combat encounters and the map overlay (only one overlay visible at a time).
- **Decision** — whether the Android back button maps to pause vs. the existing screen-back behavior; pick the least surprising and document it.
- **Risk** — multi-touch held inputs (joystick, FIRE) must be released/neutralized when the overlay opens so they don't "stick" on resume.

## Original Description
Autonomously captured from the UI/UX analysis — a mobile game with no pause/resume is a readiness gap; `MapOverlayLayout.PAUSES_SIMULATION = false` confirms nothing currently halts the sim.
