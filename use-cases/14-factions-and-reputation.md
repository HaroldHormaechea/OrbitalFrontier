# Use Case 14: Factions & reputation (post-MVP)

## Summary
**Post-MVP.** Add **factions** that own stations and a **reputation** system: station faction state influences mission availability/rewards and (optionally) dynamic prices; the player holds a **per-faction reputation** that changes with actions (completing/failing missions, combat) and **gates some offers**. This activates the deferred hooks in Missions (UC12) and Economy (UC08 dynamic pricing). Captured now for completeness; implemented after the MVP systems.

## Acceptance Criteria
1. Factions are defined as data; each station belongs to a faction.
2. The player has a per-faction reputation value that persists.
3. Mission/offer availability and (optionally) some prices are gated/modulated by reputation.
4. Reputation changes from relevant actions (e.g. mission complete/fail) by defined amounts.
5. Logic is pure and JVM-testable.
6. A recorded playthrough (UC02) completes a faction mission and asserts increased reputation and a newly available gated offer.

## Potential Pitfalls & Open Questions
- **Assumption** — Post-MVP; implemented after the core loop UCs.
- **Edge case** — Dynamic pricing tie-in (UC08) is optional within this UC.

## Original Description
Autonomously captured from the Factions & Reputation candidate system (station faction state, reputation gating) per the owner's request to capture every prepared system. Explicitly post-MVP.
