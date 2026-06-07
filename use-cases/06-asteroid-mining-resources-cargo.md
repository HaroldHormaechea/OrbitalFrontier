# Use Case 06: Asteroid mining, resources & cargo

## Summary
Add **asteroid fields** (POIs) and a **mining** interaction that extracts the ~10 defined resources (hydrogen, water ice, iron, copper, silicon, aluminum, nickel, titanium, rare earths, helium-3, platinum) into a **capacity-limited cargo hold**. Resources and asteroid contents are data-driven. Asteroid-field **depletion persists**, and cargo contents persist as part of game state. No selling yet (that is UC08); hydrogen mined here feeds fuel (UC07). Builds on UC03 (POIs) and UC04 (persistence).

## Acceptance Criteria
1. Asteroid fields exist as POIs in sectors (data-driven); the resource catalog (~10 types) is defined as data.
2. A mining interaction (proximity + action) extracts resource units from an asteroid into cargo over time.
3. Cargo has a capacity limit (a ship stat); mining stops when cargo is full.
4. Asteroid-field depletion is tracked and persists across save/reload.
5. Cargo contents persist as part of game state.
6. Mining and cargo logic are pure and JVM-testable.
7. A recorded playthrough (UC02) mines until cargo is full and asserts cargo contents and field depletion.

## Potential Pitfalls & Open Questions
- **Ambiguity** — Mining UX: proximity + hold/action; define a simple range and extraction rate.
- **Missing input** — Resource values/yields are placeholders to balance later.

## Original Description
Autonomously captured from the Economy & Resources design note (resources, mining, cargo) per the owner's request to capture every prepared system.
