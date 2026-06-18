# Use Case 50: Crew depth & fleet/crew management screen

## Summary
Deepen crew beyond a bare integer and add a management surface. Today crew is "a single integer count" that only flips a turret-operability boolean (UC11/ADR 0010), with no identities, skills, wages, or per-ship assignment, and the multiple ships from UC09 have only an active-ship switch with no fleet screen. Add **crew identities/roles** (and optionally skills that affect systems), **wages/upkeep** as an economic sink (ADR 0010: "wages are deferred future work"), and a **fleet & crew management screen** to view ships, assign crew, and switch the active ship.

## Acceptance Criteria
1. Crew are individual entities (at minimum named, with a role) rather than a single count; turret-operability still derives correctly from assigned crew.
2. Crew incur a periodic wage/upkeep credit drain (an economy sink) that persists across save/reload.
3. A fleet/crew management screen lists owned ships and crew, supports assigning crew to ships/roles, and switching the active ship (replacing the bare UC09 switch).
4. Crew and assignments persist across save/reload.
5. `./gradlew :core:ktlintCheck :core:test` green; wage drain and crew-derived operability are unit-tested.

## Potential Pitfalls & Open Questions
- **Decision** — how much crew depth: identities + wages is the MVP; skills affecting systems and crew upgrades are stretch (upgrades.md "Crew upgrade mechanics (deferred)").
- **Edge case** — unpaid wages / negative balance behavior (desertion? debt?) must be defined.
- **Open question** — wage rates and crew-per-turret ratios are `[TUNE]`; reintroducing wages is "a new resolver + an ADR" per ADR 0010.

## Original Description
Autonomously captured from the feature catalog (ship-and-controls.md crew "candidate for a dedicated note", ADR 0010 wages deferred, upgrades.md crew-upgrade deferred) and code (TurretOperability.kt pure flag, crew is an integer).
