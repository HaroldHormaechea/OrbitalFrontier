# Use Case 28: Scalable game font / real typography

## Summary
Replace libGDX's built-in `BitmapFont` (currently bilinearly stretched across the HUD and every Scene2D screen) with a **real, scalable game typeface** — a baked bitmap font (or SDF/MSDF) at the resolutions the UI needs, integrated through the design-system art pipeline. Today `HudRenderer` admits the text is "slightly blurry; acceptable as a placeholder" and that glyphs like the degree sign "may render as a blank". A single font asset (with a defined glyph coverage including °, currency, and combat symbols) should drive the HUD, menus, station screens, and the action-arc labels, scaled consistently through `UiScale`.

## Acceptance Criteria
1. A bundled font asset (bitmap or SDF/MSDF) replaces the runtime built-in `BitmapFont` in `HudRenderer` and the controls skin.
2. Text is crisp at the supported device resolutions and DPI buckets (no bilinear-stretch blur) across HUD, menus, and station screens.
3. The font's glyph set covers all characters the UI uses today, including the degree sign, digits, currency/credit marker, and any combat/status symbols.
4. Font sizing flows through the existing `UiScale.factor` so handedness/scale settings keep working.
5. `./gradlew :core:ktlintCheck :core:test` is green; any layout/measurement tests that assumed the old font metrics are updated.

## Potential Pitfalls & Open Questions
- **Decision** — bitmap (simpler, fixed sizes) vs. SDF/MSDF (smooth at any scale, more setup). Implementer's call given target device range.
- **Edge case** — long labels (mission text, station names) must still fit the action-arc buttons and panels after the metric change.
- **Risk** — atlas page budget: the font pages must coexist with `orbital.atlas` without blowing the texture memory budget on min-spec devices.

## Original Description
Autonomously captured from the feature-catalog + capture analysis (HudRenderer.kt placeholder-font comments, Palette.kt "custom fonts deferred", PlaceholderControlsSkin.kt) per the owner's request to enumerate readiness gaps. The game is pre-alpha on typography: all text uses the engine default font.
