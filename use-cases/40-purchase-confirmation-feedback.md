# Use Case 40: Purchase/sale confirmation & economy feedback

## Summary
Improve the **feedback on economy actions**. Today trade/outfit/refuel/shipyard actions return a short status string shown in a label, with no confirmation for expensive purchases and no visible credit-change feedback. Add confirmation dialogs for significant spends (e.g. buying a hull, a ship, or a premium upgrade) and an **animated credit-change** indicator (delta flash / running total) so the player perceives gains and losses. Applies across Trade, Outfit, Shipyard, Hire, and refuel.

## Acceptance Criteria
1. Purchases above a configurable cost threshold (e.g. ships, hulls, premium upgrades) prompt a confirmation dialog showing item, cost, and resulting balance before committing.
2. Successful buys/sells show an animated credit delta and updated balance.
3. Insufficient-credit and invalid actions show a clear, styled error instead of a bare string.
4. Confirmation/feedback works uniformly across Trade, Outfit, Shipyard, Hire, and refuel screens.
5. `./gradlew :core:ktlintCheck :core:test` green; the threshold/confirm-gate logic is unit-tested.

## Potential Pitfalls & Open Questions
- **Decision** — the spend threshold for requiring confirmation is a `[TUNE]` value.
- **Edge case** — multi-step transactions (buy ship + switch active) must confirm once, not per internal step.
- **Dependency** — animated feedback shares the notification layer (UC35).

## Original Description
Autonomously captured from the UI/UX analysis — economy actions only surface a short text string with no confirmation or animated feedback.
