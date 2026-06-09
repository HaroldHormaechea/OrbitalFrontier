# Use Case 24: Show name labels for map items

## Summary
The map should display the names of items on it, such as station names. Per clarification this extends to all named points of interest — stations plus other named map entities (jump gates, planets/POIs) wherever a name exists — so the player can identify destinations at a glance. This applies to the map view (and naturally benefits the zoomed overlay from UC23). It is a presentation change: render each named entity's existing name as a label near its map marker.

## Acceptance Criteria
1. Named map items display their name as a text label adjacent to their map marker.
2. All named POIs are covered — at minimum stations, and also other named entities present on the map (e.g. jump gates, planets/POIs) where a name exists.
3. Items without a name are not labelled (no empty/placeholder labels).
4. Labels are legible on both the HUD minimap and the zoomed overlay (UC23), without overwhelming the view (sensible font size; avoid unreadable clutter).
5. Labels stay associated with their markers as the map pans/zooms (they track the correct item).

## Potential Pitfalls & Open Questions
- **Edge case** — On the small HUD minimap, many labels may overlap/clutter; consider showing labels only on the zoomed overlay, or only for nearby/significant items on the minimap, while ensuring stations are always identifiable.
- **Assumption** — Names already exist on the underlying entities; this UC surfaces them, it does not invent or author names.
- **Risk** — Label rendering must not significantly hurt frame rate when many items are visible.

## Original Description
"Map should show items name like stations names."

## Clarifications
- Q: Which map items should show name labels?
  A: All named POIs (stations plus other named items such as gates/planets where a name exists).
