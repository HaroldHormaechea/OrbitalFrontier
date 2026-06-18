# Use Case 29: Final UI skin / theme (replace the placeholder controls skin)

## Summary
Replace `PlaceholderControlsSkin` — used today by **every** screen (MainMenu, StationHub, Trade, Shipyard, Outfit, Hire, MissionBoard, Play, StationWalkaround) and which generates solid-shape fallbacks when no atlas is present — with a **finished design-system UI skin**: buttons, panels, labels, scroll panes, and the action-arc circular buttons styled from the project palette and atlas. The skin is the single styling source for all Scene2D widgets, so visual identity is consistent and the "placeholder" naming and programmer-art fallbacks are retired.

## Acceptance Criteria
1. A new skin (drawables, nine-patches, label/button styles) built on the design-system palette and `orbital.atlas` replaces `PlaceholderControlsSkin` everywhere it is referenced.
2. All listed screens render with the new skin; no screen falls back to generated solid shapes.
3. Action-arc buttons (UC26) and station menu grid (UC20) adopt the new button/label styles without regressing their geometry or layout reservations.
4. Disabled, pressed, and focused states are styled (not just the default state).
5. `./gradlew :core:ktlintCheck :core:test` green; UI layout guard tests updated to the new skin metrics.

## Potential Pitfalls & Open Questions
- **Dependency** — pairs with UC28 (font) and UC30 (sprite art); the skin should consume the real font once available rather than re-introducing the built-in one.
- **Edge case** — nine-patch insets must keep existing panels readable at the smallest supported screen.
- **Risk** — handedness mirroring (UC26) and `UiScale` must still apply after restyling.

## Original Description
Autonomously captured from the capture analysis (PlaceholderControlsSkin.kt is literally named a placeholder and is the styling source for all screens). The owner asked to enumerate what makes the game "not by far ready"; the UI skin is programmer-art across the board.
