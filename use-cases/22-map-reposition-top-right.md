# Use Case 22: Reposition map to top-right (off the action buttons)

## Summary
The on-screen map widget currently overlaps the action buttons, which is unacceptable. Move the map to the top-right corner of the HUD as the conventional location, so it no longer covers or interferes with the action buttons (or any other interactive control). This is a HUD layout fix; the map's content and behaviour are otherwise unchanged (zoom/labels are covered by UC23/UC24).

## Acceptance Criteria
1. The map widget is anchored in the top-right corner of the screen.
2. The map no longer overlaps the action buttons or any other interactive HUD control, in any supported screen size/orientation.
3. The action buttons remain fully visible and tappable (no part is obscured by the map).
4. The map remains visible and functional in its new position (still shows the player/sector as before).

## Potential Pitfalls & Open Questions
- **Edge case** — Other top-right HUD elements (if any — e.g. fuel/credits readouts) must not now collide with the map; reconcile their positions.
- **Assumption** — "Top right as convention" refers to a minimap anchored to the top-right safe area, respecting device notches/insets.
- **Risk** — Touch targets near screen edges can be hard to hit; ensure the map (if tappable per UC23) and neighbouring controls keep adequate hit areas.

## Original Description
"Current map placement overlaps action buttons. Not acceptable. Move to top right as convention."

## Clarifications
(None.)
