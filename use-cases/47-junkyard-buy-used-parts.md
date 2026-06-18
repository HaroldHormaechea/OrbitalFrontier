# Use Case 47: Buy used parts at junkyards

## Summary
Add the **buy-used** side of junkyards. Today only the sell/refit side shipped (UC09); upgrades-and-progression.md notes "Junkyard used-part buy-used option and used-part pricing curve (only the sell/refit side shipped)" and "buying cheaper used parts is still TBD." Let junkyards stock **used upgrade parts at a discount** relative to new-shop prices, with availability/stock that varies, giving budget players a cheaper acquisition path.

## Acceptance Criteria
1. Junkyards present a buy list of used parts (a subset of the upgrade catalog) priced below the equivalent new part via a defined used-part pricing curve.
2. Buying a used part installs/stores it exactly like a new part (same outfitting flow as UC09).
3. Used stock is finite/variable (not an infinite shelf) and persists/regenerates deterministically across save/reload.
4. Used-part pricing is data-driven and applies consistently across junkyards.
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough buys a used part and asserts the discounted cost and install.

## Potential Pitfalls & Open Questions
- **Open question** — the used-part pricing curve (flat discount vs. condition-based) is `[TUNE]`.
- **Decision** — whether used parts carry any drawback (condition/wear) or are purely cheaper; MVP = purely cheaper.
- **Edge case** — stock depletion and restock cadence.

## Original Description
Autonomously captured from the feature catalog (upgrades-and-progression.md: junkyard buy-used option and pricing curve deferred; only sell/refit shipped in UC09).
