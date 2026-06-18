# Use Case 46: Dynamic station pricing

## Summary
Make station prices dynamic. Today `StationMarket` notes "Prices are MVP-fixed and data-driven … dynamic pricing is deferred"; economy.md and ADR 0007/0013 record `FactionPricing` as a deferred hook. Introduce **price movement**: per-station buy/sell prices vary by supply/demand (driven by player trades and/or a simulated market drift) and by sector/faction/reputation state, with the mutable per-station price state moving into the save behind the existing `StationMarket` type. This turns trading (UC08) from fixed arbitrage into a living economy.

## Acceptance Criteria
1. Per-station resource prices vary over time and/or in response to player buying/selling (supply & demand), rather than being fixed.
2. Price modulation accounts for faction/sector and (optionally) player reputation (`FactionPricing` seam).
3. Mutable per-station market state persists across save/reload.
4. Price changes are deterministic given the seed and player actions, so trading playthroughs replay identically.
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough that buys/sells and asserts price movement is added.

## Potential Pitfalls & Open Questions
- **Edge case** — prevent degenerate infinite-profit loops; cap price swings and define recovery/drift.
- **Decision** — pure simulated drift vs. player-driven supply/demand vs. both; default to a bounded blend.
- **Open question** — all elasticity/drift constants are `[TUNE]`; schema change to persist market state needs a migration + possibly an ADR.

## Original Description
Autonomously captured from the feature catalog (economy-and-resources.md "Dynamic pricing model (post-MVP)"; StationMarket.kt "dynamic pricing is deferred"; ADR 0007/0013 FactionPricing hook).
