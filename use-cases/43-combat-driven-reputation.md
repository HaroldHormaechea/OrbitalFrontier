# Use Case 43: Combat-driven reputation

## Summary
Wire combat into the faction reputation system. ADR 0013 notes "the `Reputation.with` seam already exists; only a combat-side call site is missing." Destroying a ship belonging to a faction should change the player's standing with that faction (and possibly allied/rival factions), feeding the existing reputation model (UC14) that already gates mission offers and (future) prices/availability.

## Acceptance Criteria
1. Destroying a hostile that belongs to a faction applies a reputation change with that faction via the existing `Reputation.with` seam.
2. Reputation effects propagate to allied/rival factions if the model defines such relationships (otherwise single-faction only for MVP).
3. Reputation changes persist across save/reload and are reflected wherever standing is read (mission offers, gated premium offers from UC14).
4. The change is surfaced to the player via notification (UC35).
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough destroys a faction ship and asserts the standing delta persists.

## Potential Pitfalls & Open Questions
- **Edge case** — destroying neutral/unaffiliated hostiles should have no faction effect.
- **Decision** — magnitude of the standing change per kill is `[TUNE]`; whether kills can drop standing into "hostile" territory that then spawns reprisals is a later concern (see enemy-AI UC45).
- **Dependency** — pairs naturally with bounty missions (UC41).

## Original Description
Autonomously captured from the feature catalog (ADR 0013: combat-driven reputation is a recorded deferred hook; only the combat call site is missing).
