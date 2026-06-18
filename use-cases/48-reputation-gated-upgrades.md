# Use Case 48: Reputation-gated upgrade & ship availability

## Summary
Gate upgrade/ship acquisition by faction standing. Today, per upgrades-and-progression.md and ADR 0013, "upgrade/ship acquisition is still cash-only" and "Reputation gating of upgrades/ships detail — still deferred." Use the existing reputation model (UC14) to gate **which upgrades and ships are available** at a station and **at what price**: higher standing unlocks premium parts/ships and/or discounts; low/hostile standing restricts or surcharges them. This mirrors the gated premium *mission* offers UC14 already proved, extended to the shop/shipyard.

## Acceptance Criteria
1. A station's outfit/shipyard catalog filters available items by the player's standing with that station's faction.
2. Standing also modulates price (discount at high standing, surcharge or lockout at low standing) via the same pricing seam as UC46.
3. Gating/price effects persist across save/reload and update as standing changes (e.g. after combat reputation, UC43).
4. The player can see why an item is locked (standing requirement) rather than it silently vanishing.
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough raises standing and asserts a previously-locked item becomes purchasable.

## Potential Pitfalls & Open Questions
- **Dependency** — builds on the reputation model (UC14), dynamic pricing (UC46), and combat reputation (UC43).
- **Open question** — standing thresholds and discount/surcharge curves are `[TUNE]`.
- **Edge case** — an item already owned/installed when standing later drops below its threshold must not be confiscated.

## Original Description
Autonomously captured from the feature catalog (upgrades-and-progression.md + ADR 0013: reputation gating of upgrades/ships deferred; acquisition is cash-only).
