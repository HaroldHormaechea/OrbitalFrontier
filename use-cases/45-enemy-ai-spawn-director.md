# Use Case 45: Richer enemy AI & encounter variety

## Summary
Deepen combat encounters beyond the single authored zone. Today there is exactly **one encounter zone in the whole map** (`alpha-raider-picket`) spawning a single RAIDER, and combat.md flags "Richer AI (formations, retreat-and-regroup, targeting the player's weakest section) and difficulty scaling by a spawn director" as unbuilt. Add **multiple encounter zones** across the sectors, more than one hostile per encounter, smarter hostile behavior (approach/retreat, target the weakest section), and a **spawn director** that scales encounter difficulty.

## Acceptance Criteria
1. Multiple encounter zones exist across the three sectors, each with configurable hostile composition (count/type).
2. Hostile AI supports at least: engage, retreat-and-regroup at low health, and preferential targeting of the player's weakest hull section.
3. A spawn director scales difficulty (hostile count/strength) by a defined input (e.g. player progression or reputation standing).
4. All AI and spawning is pure and seed-deterministic for replay stability.
5. `./gradlew :core:ktlintCheck :core:test` green; a recorded multi-hostile encounter playthrough is added and replays identically.

## Potential Pitfalls & Open Questions
- **Edge case** — multi-hostile fights stress auto-aim target selection (combat.md chose distance²-then-id); confirm it stays sane with formations.
- **Open question** — difficulty-scaling inputs and all AI/spawn numbers are `[TUNE]`.
- **Dependency** — interacts with combat-driven reputation (UC43) if hostile factions react to player standing; and with ambient encounters (separate world-population UC).

## Original Description
Autonomously captured from the feature catalog (combat.md richer-AI + spawn-director deferred) and the capture analysis (only one encounter zone, single hostile, auto-aim only).
