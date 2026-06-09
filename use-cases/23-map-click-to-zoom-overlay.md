# Use Case 23: Click map to open a full-height zoomed overlay

## Summary
Allow the player to tap/click the (top-right) map to open a zoomed-in map view. The zoomed view spans the full height of the screen and is rendered at 80% opacity (the gameplay behind it remains faintly visible through the overlay). This builds on the repositioned map (UC22) and adds an expanded inspection mode; the minimap itself stays as the always-on HUD element, and tapping it toggles the larger overlay. Dismissing the overlay returns to normal play.

## Acceptance Criteria
1. Tapping/clicking the map widget opens a zoomed map overlay.
2. The zoomed overlay spans the full screen height.
3. The overlay is rendered at ~80% opacity, so the scene behind it is partially visible.
4. The zoomed view shows more map detail/area than the minimap (a genuine zoom-in, not just a scaled copy of the same content).
5. The overlay can be dismissed (e.g. tapping outside it, a close control, or tapping the map again), returning to normal gameplay with the HUD intact.
6. Opening the overlay does not pause-break or corrupt game state inappropriately for this game's conventions (define and apply consistent behaviour — paused vs. live).

## Potential Pitfalls & Open Questions
- **Ambiguity** — Overlay width isn't specified ("spans the whole height"); assume full height with a sensible centred/right-aligned width, not necessarily full screen width.
- **Ambiguity** — Whether the game pauses while the overlay is open is unspecified; pick the behaviour consistent with the rest of the game and apply it.
- **Edge case** — Dismiss gesture must be unambiguous so the player isn't trapped in the overlay.
- **Assumption** — "80% opaque" = the overlay/background is drawn at ~0.8 alpha; the map markers/labels on it remain fully legible.
- **Dependency** — Relies on UC22 (map in top-right) for the tap target's location.

## Original Description
"Clicking in the map should zoom it in with a view that spans the whole height and is 80% opaque."

## Clarifications
(None.)
