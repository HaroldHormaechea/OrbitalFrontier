# Use Case 09: Ship outfitting, upgrades, junkyards & multiple ships

## Summary
Implement horizontal progression (no pilot XP): ships have **role-based slot layouts** by category (weapons, communications, hull plating, engines, sensors, cargo, fuel tanks, crew quarters); **upgrades occupy free slots and modify the matching stat** (engine→speed, cargo→capacity, fuel tank→fuel cap, sensors→scan range, crew quarters→crew capacity, etc.). Upgrades and ships are **bought at stations with credits** (UC08; reputation gating deferred to UC14). **Used parts are sold/removed and refit only at junkyard** station variants (not at normal dealers). The player can **own multiple ships and switch the active one while docked**, each with its own loadout/cargo/fuel. Everything persists. Builds on UC04/UC05/UC08.

## Acceptance Criteria
1. Ship config has role-based slots by category; slot counts depend on ship type (data-driven).
2. Installing an upgrade fills a slot and changes the matching ship stat; removing it reverts the stat.
3. Upgrades are bought at a station with credits; availability is data-driven.
4. A junkyard station variant allows removing/selling used upgrades (not sellable at normal dealers) and refitting.
5. The player can buy additional ships and switch the active ship while docked; each ship keeps its own loadout, cargo, and fuel.
6. All owned ships and their loadouts persist across save/reload.
7. Outfitting logic is pure and JVM-testable.
8. A recorded playthrough (UC02) buys an engine upgrade and asserts increased max speed; switches active ship and asserts the active ship changed.

## Potential Pitfalls & Open Questions
- **Missing input** — Per-category slot counts and the MVP ship roster are placeholders to define/balance.
- **Assumption** — Reputation gating of availability is deferred to UC14; cash-gating only here.

## Original Description
Autonomously captured from the Upgrades & Progression design note (ships-as-roles, free slots, junkyards) plus the multi-ship economy decision, per the owner's request to capture every prepared system.
