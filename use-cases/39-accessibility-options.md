# Use Case 39: Accessibility options

## Summary
Add **accessibility options**. Today the only accommodation is the handedness toggle. Add a colorblind-safe palette option (or selectable palettes), adjustable **text size** beyond the global `UiScale.factor`, and a **reduced-motion** toggle (the starfield parallax and any screen-shake/animations always run today). These live in the settings screen (UC37) and persist.

## Acceptance Criteria
1. A colorblind-friendly palette option re-maps the UI/world colors used to convey state (faction colors, warnings, hostile vs. friendly) to a colorblind-safe set.
2. A text-size control adjusts UI text independently of the global UI scale.
3. A reduced-motion toggle disables/attenuates parallax starfield, screen shake, and non-essential animations.
4. All three persist across launches and apply live.
5. `./gradlew :core:ktlintCheck :core:test` green; palette/text-size selection logic is unit-tested.

## Potential Pitfalls & Open Questions
- **Dependency** — palette work touches `Palette.kt` and the design-system colors (UC27); coordinate so faction colors (UC14) remain distinguishable under the colorblind palette.
- **Edge case** — text-size changes must not break the layout guards (UC22/UC26) — clamp to tested bounds.
- **Decision** — exact palette set; default to a vetted colorblind-safe scheme.

## Original Description
Autonomously captured from the UI/UX analysis — accessibility is limited to handedness; colorblind palette, text scaling, and reduced motion are absent.
