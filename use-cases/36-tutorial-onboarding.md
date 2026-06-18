# Use Case 36: First-run tutorial & onboarding

## Summary
Add **first-run onboarding**. Today a new player is dropped straight into flight facing an action arc of icon buttons and a joystick with no explanation of controls or goals. Add an interactive, skippable tutorial (or staged control-hint overlays) that introduces the core loop: steer/thrust, dock at a station, accept a mission, mine/trade, refuel, and fire in combat. The tutorial runs only on first launch (tracked in settings/save) and can be replayed from settings.

## Acceptance Criteria
1. On first launch (no prior save / a first-run flag), the player is guided through steering, docking, accepting a mission, mining or trading, refuelling, and firing.
2. Each step is contextual (highlights the relevant control) and advances on completion or can be skipped.
3. A persistent flag prevents the tutorial from re-triggering on later launches; it can be replayed from the settings screen.
4. The tutorial does not break the deterministic core (it gates/annotates input but does not alter simulation rules).
5. `./gradlew :core:ktlintCheck :core:test` green; tutorial step progression is unit-tested where logic is non-trivial.

## Potential Pitfalls & Open Questions
- **Decision** — full guided tutorial vs. lightweight first-run control hints; scope to the latter if art/time is tight, but cover the whole core loop.
- **Edge case** — starting the tutorial mid-flight must compose with pause (UC32) and notifications (UC35).
- **Risk** — onboarding copy depends on the real font (UC28) for legibility.

## Original Description
Autonomously captured from the UI/UX analysis — there is no first-run guidance; new players get no explanation of controls or objectives.
