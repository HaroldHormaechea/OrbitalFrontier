# Use Case 11: Crew

## Summary
Implement **crew**: a ship has a **crew count** and a **crew capacity** (from its crew-quarters slot, UC09). Crew can be **hired at stations for credits** (UC08), up to capacity. **Turrets require crew to operate** — a turret without sufficient assigned crew is inoperable (enforced in the ship/combat model, consumed by UC13). Crew state persists. For MVP, hiring is a one-time credit cost (no ongoing wages), and turrets are the only crew-gated system.

## Acceptance Criteria
1. A ship has a crew count and a crew capacity derived from its crew-quarters slot.
2. Crew can be hired at a station for credits, up to capacity; over-capacity hiring is rejected.
3. Turret operability depends on sufficient crew: insufficient crew makes a turret inoperable (a flag/derived state the combat model reads).
4. Crew state persists across save/reload.
5. Crew logic is pure and JVM-testable.
6. A recorded playthrough (UC02) hires crew and asserts the crew count and turret-operability flag.

## Potential Pitfalls & Open Questions
- **Ambiguity** — Ongoing wages are out of scope for MVP (one-time hire cost); revisit later.
- **Assumption** — Crew gates turrets only for MVP; other crew-gated systems are future work.

## Original Description
Autonomously captured from the Crew candidate system (flagged in ship-and-controls / combat / upgrades notes) per the owner's request to capture every prepared system.
