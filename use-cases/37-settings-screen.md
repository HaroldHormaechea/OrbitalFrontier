# Use Case 37: Full settings screen

## Summary
Build a real **settings screen**. Today "settings" is a single handedness-toggle button (`SettingsOverlay`). Replace it with a proper settings surface grouping: **audio** (master/SFX/music volume, mute — from UC31), **controls** (handedness, joystick sensitivity/deadzone), **accessibility** hooks (text size, colorblind palette — from the accessibility UC), **gameplay** (replay tutorial), and **save management** (link to save-slot UI). All settings persist across launches via the existing settings store and apply live where possible.

## Acceptance Criteria
1. A settings screen replaces the single-button overlay and groups audio, controls, accessibility, gameplay, and save options.
2. Each setting persists across app launches and applies immediately (or on confirm) without a restart where feasible.
3. Handedness (existing) and `UiScale` continue to work and live under the new screen.
4. The screen is reachable from the main menu and the pause overlay (UC32).
5. `./gradlew :core:ktlintCheck :core:test` green; settings persistence is unit-tested.

## Potential Pitfalls & Open Questions
- **Dependency** — audio rows depend on UC31; accessibility rows on the accessibility UC; save rows on the save-slot UC. Build the screen as a shell that grows as those land, but ship handedness + sensitivity + tutorial-replay now.
- **Edge case** — invalid/edge values (e.g. zero sensitivity) must be clamped.
- **Risk** — keep the settings model in the pure layer so it stays testable and save-compatible.

## Original Description
Autonomously captured from the UI/UX analysis — the only "setting" today is a handedness toggle; there is no volume, sensitivity, accessibility, or save management.
