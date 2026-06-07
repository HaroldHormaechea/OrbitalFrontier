# Use Case 12: Missions — mining & courier (radio + station boards)

## Summary
Implement the mission system: two **handcrafted mission types** — **mining** (gather a resource quota and turn it in) and **station-to-station courier** (pick up cargo at A, deliver to B, time-limited) — **procedurally instanced** from world/sector state. Missions are offered at **station mission boards** and via **ship radio broadcasts** (the radio/comms system). The player can hold **multiple concurrent missions**, tracked in a **mission log**, with accept → progress → complete → fail(timeout) and **predefined consequences**. Rewards are **credits** (optionally resources/crew). **Available and accepted missions persist** (offers don't reset on restart). Reputation effects are deferred to UC14. Builds on UC05/UC06/UC08.

## Acceptance Criteria
1. Two mission types exist: mining (resource quota → turn in) and courier (pick up at A → deliver to B, time-limited).
2. Missions are procedurally instanced from world state and offered at station boards and via ship radio broadcasts.
3. The player can accept multiple missions; a mission log lists available/active/completed missions.
4. Completing a mission grants rewards (credits; optionally resources); failing or timing out applies a predefined consequence.
5. Available and accepted missions (with progress and timers) persist across save/reload.
6. Mission logic is pure and JVM-testable.
7. A recorded playthrough (UC02) accepts a mining mission, mines the quota, turns it in, and asserts completion and credit reward.

## Potential Pitfalls & Open Questions
- **Assumption** — Active-mission map markers are deferred (post-MVP); the mission log is the MVP surface.
- **Assumption** — Reputation/faction effects deferred to UC14.
- **Missing input** — Reward balancing and radio broadcast range/cadence are placeholders.

## Original Description
Autonomously captured from the Missions design note (mining + courier, radio + station-board sources) and the Radio/Comms candidate system, per the owner's request to capture every prepared system.
