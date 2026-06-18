# Use Case 49: Power brownout/throttle & power UI surfacing

## Summary
Complete the power/energy model and surface it. Today `PowerModel` "does not yet cap totalDraw (brownout deferred)" — module draw can exceed reactor output with no consequence — and power.md flags both the cap and the UI as open. Add **brownout/throttle behavior** (when demand > supply, systems are throttled/disabled by a defined priority, optionally buffered by a capacitor) and **surface power** to the player (a power bar / per-system indicators), so the reactor-output vs. module-draw relationship (fuel feeds reactor → reactor feeds modules) has stakes.

## Acceptance Criteria
1. When total module draw exceeds reactor output, the model applies a defined brownout/throttle (e.g. shed lowest-priority systems or scale output) instead of ignoring the overage.
2. An optional capacitor/buffer smooths short spikes before brownout triggers (if the chosen model includes one).
3. Power state (output, draw, brownout status) is surfaced on the HUD (power bar and/or per-system indicators).
4. The power model stays pure and deterministic; brownout is reflected in dependent systems (e.g. reduced thrust/weapons/scan) consistently.
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough that over-draws power asserts the brownout effect.

## Potential Pitfalls & Open Questions
- **Open question** — power.md's model is an explicit "starting proposal (placeholder)"; pool-vs-rate, capacitor, and allocation are undecided. Confirm the model before building, default to rate-based with priority shedding.
- **Decision** — whether the player manually allocates power (FTL/X-series style) or it is automatic; MVP = automatic priority shedding.
- **Edge case** — brownout must not deadlock the ship (always leave minimal thrust/helm power).

## Original Description
Autonomously captured from the feature catalog (power-and-energy.md model "to be defined", brownout deferred) and code (PowerModel.kt "does not yet cap totalDraw").
