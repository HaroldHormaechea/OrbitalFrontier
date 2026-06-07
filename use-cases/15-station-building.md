# Use Case 15: Station building (post-MVP stretch)

## Summary
**Post-MVP stretch.** Let the player **build and own multiple personal stations** (for commerce, retrofit, etc.) from **modular snap-together pieces**, paid for with **credits and/or mined resources**. Single-player and offline (no networked base-building). Station ownership/layout persists via an **additive migration** (the UC04 save schema was designed to extend to this without a breaking change). Captured now for completeness; the lowest-priority item, implemented only after all MVP systems.

## Acceptance Criteria
1. The player can place/build a personal station from modules at a cost (credits and/or resources).
2. Modules provide functions (e.g. commerce = trade hub, retrofit = outfitting/refit).
3. Multiple owned stations are supported.
4. Station ownership and layout persist via an additive save migration (no breaking change to existing saves).
5. Logic is pure and JVM-testable.
6. A recorded playthrough (UC02) builds a station module and asserts ownership and the module's function availability.

## Potential Pitfalls & Open Questions
- **Assumption** — Post-MVP stretch; lowest priority, implemented last.
- **Missing input** — Module catalog and costs are placeholders; defense is deferred (ties to combat).

## Original Description
Autonomously captured from the Station Building design note (multiple modular stations for commerce/retrofit; schema-extensibility guardrail) per the owner's request to capture every prepared system. Explicitly post-MVP stretch.
