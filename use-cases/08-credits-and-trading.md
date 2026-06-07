# Use Case 08: Credits & inter-station trading

## Summary
Add the single **credits** currency and **inter-station trading**: docked stations buy and sell resources at **MVP-fixed prices** (data-driven per station), enabling buy-low/sell-high across stations. Selling mined resources (UC06) earns credits; buying hydrogen feeds fuel (UC07). Station offers/prices/stock persist. Credits are the spending currency for upgrades/ships (UC09), crew (UC11), and the reward currency for missions (UC12). Dynamic pricing is explicitly deferred (post-MVP).

## Acceptance Criteria
1. The player has a credits balance that persists.
2. A docked station's trade screen lists buyable/sellable resources with fixed, data-driven prices.
3. Selling resources adds credits and removes cargo; buying removes credits and adds cargo, respecting cargo capacity and available credits.
4. Prices differ between stations so buy-low/sell-high is possible; prices/stock are fixed for MVP and persist.
5. Buying hydrogen integrates with refueling (UC07).
6. Trading logic is pure and JVM-testable.
7. A recorded playthrough (UC02) docks, sells cargo, and asserts increased credits and reduced cargo.

## Potential Pitfalls & Open Questions
- **Assumption** — Fixed prices for MVP; dynamic/faction-driven pricing deferred to UC14.
- **Missing input** — Price tables are placeholders to balance later.

## Original Description
Autonomously captured from the Economy & Resources design note (credits, inter-station trading) per the owner's request to capture every prepared system.
