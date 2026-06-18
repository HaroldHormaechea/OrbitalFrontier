# Use Case 42: Loot & salvage from destroyed hostiles

## Summary
Make combat feed the economy. Today, per combat.md, "destroyed hostiles drop nothing yet; combat does not feed economy." Add **loot/salvage**: when a hostile is destroyed it drops collectible salvage (credits and/or resources, possibly a recoverable part) that the player picks up by flying over it (or auto-collects within a radius) and that flows into cargo/credits. This closes the loop from the combat pillar (Earn) into trading and outfitting.

## Acceptance Criteria
1. Destroying a hostile spawns a salvage drop at its position with a defined loot table (credits and/or resources).
2. The player collects salvage by moving within a pickup radius; collected resources go to cargo (respecting capacity) and credits to the balance.
3. Salvage that exceeds cargo capacity is handled deterministically (left behind / partial pickup) and communicated via notification (UC35).
4. Loot generation is pure and seed-deterministic so it is replay-stable.
5. `./gradlew :core:ktlintCheck :core:test` green; a recorded playthrough destroys a hostile and asserts the salvage and pickup.

## Potential Pitfalls & Open Questions
- **Edge case** — full cargo hold at pickup time; define overflow behavior.
- **Dependency** — must not double-reward with bounty missions (UC41); decide whether bounty and salvage stack.
- **Open question** — loot table contents/odds are `[TUNE]`.

## Original Description
Autonomously captured from the feature catalog (combat.md "Loot / salvage / bounty economy — destroyed hostiles drop nothing yet").
