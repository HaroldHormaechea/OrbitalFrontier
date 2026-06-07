# Use Case 13: Real-time combat

## Summary
Implement **real-time combat**: **fixed/forward weapons** fired via an action control along hull facing, plus **auto-aim turrets** that acquire/track hostiles automatically and **require crew** (UC11) to operate. A **per-section/component damage model** drives the HUD ship schematic (engine hit reduces speed, turret hit disables that turret, etc.). **Rule-based enemy AI** (no ML) — e.g. aggressive / flee-when-damaged — with data-driven difficulty. Ship destruction is **forgiving** (respawn at the last docked station with a defined penalty such as cargo loss; no permadeath). The player can **flee/disengage** (outrun or jump out). Encounters are **natural + mission-spawned**. The damage model, targeting priority, and AI decisions live in the pure model (deterministic, seeded RNG) so they are replay-testable (UC02). This is the heaviest UC.

## Acceptance Criteria
1. Fixed/forward weapons fire along hull facing via an action control.
2. Turrets auto-aim at hostiles and require crew (UC11); without crew they are inoperable.
3. A per-section damage model applies hits to components with distinct effects (e.g. engine→reduced speed, turret→disabled) reflected in the HUD schematic.
4. Rule-based enemy ships engage the player (no ML); difficulty is data-driven.
5. Ship destruction respawns the player at the last docked station with a defined penalty (e.g. partial cargo loss); no permadeath.
6. The player can flee (outrun or jump out via a gate).
7. Combat logic (damage model, turret targeting priority, AI decisions) is pure, deterministic (seeded RNG), and JVM-testable.
8. A recorded playthrough (UC02) spawns a hostile, fires, destroys it, and asserts the hostile is gone and any sectional damage taken is recorded.

## Potential Pitfalls & Open Questions
- **Risk** — Replay determinism: keep targeting/damage/AI in the seeded pure model so playthroughs are reproducible; Box2D collision stays device-tier.
- **Missing input** — Weapon/damage/AI balancing numbers are placeholders.
- **Assumption** — Combat is a later MVP phase (per the brief); shields are optional/simple for MVP.

## Original Description
Autonomously captured from the Combat & Encounters design note (real-time decided; turrets/sectional-damage/crew carried over) per the owner's request to capture every prepared system.
