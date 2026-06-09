# Use Case 20: Station menu grid layout (≤4 rows × N columns)

## Summary
The station menus are currently laid out in a single horizontal row, which is a problem at stations (cramped / overflowing as the number of options grows). Replace the horizontal row with a grid arrangement capped at a maximum of 4 rows, expanding into additional columns as more options are added (rows × columns ≤ 4 × N — at most 4 rows tall, columns grow). The change is purely the layout/arrangement of the existing station menu buttons; the buttons themselves and the actions they trigger are unchanged.

## Acceptance Criteria
1. Station menus are arranged in a grid rather than a single horizontal row.
2. The grid never exceeds 4 rows; when there are more items than fit in 4 rows, additional columns are used.
3. Items fill the grid in a consistent, predictable order (e.g. column-major or row-major — chosen and applied consistently).
4. All existing station menu options remain present and functional; only their arrangement changes.
5. The layout renders without overlap or clipping at the app's supported screen sizes/orientations, and degrades sensibly (wraps to more columns) as item count grows.

## Potential Pitfalls & Open Questions
- **Assumption** — "4xN (rows x columns)" is read as a hard cap of 4 rows with columns growing as needed (confirmed).
- **Edge case** — With few items (e.g. 2–3) the grid should still look intentional (single column or short grid), not stretched.
- **Ambiguity** — Fill order (row-major vs column-major) isn't specified; pick the one that keeps related actions grouped and apply consistently.
- **Risk** — On small/narrow screens a wide multi-column grid may overflow horizontally; ensure it fits or scrolls rather than clipping.

## Original Description
"On another note regarding menus: current arrange in a horizontal row is an issue In stations. Arrange them in a table of sorts, no more than 4xN (rows x columns)."

## Clarifications
- Q: Did "4xN (rows x columns)" mean max 4 rows or max 4 columns?
  A: Max 4 rows, N columns (up to 4 rows tall; add columns as items grow).
