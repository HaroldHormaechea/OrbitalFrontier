# Use Case 02: Deterministic playthrough record & replay test harness

## Summary
Build a deterministic record-and-replay test harness so all later gameplay can be verified reproducibly. The simulation must be made deterministic: a fixed timestep, an injected seeded RNG (no `Math.random`/wall-clock in core logic — via a `platform` port like the existing Logger/SqlDriver ports, per DIP), and time supplied through an injected clock. A **Playthrough** is a serializable artifact: an RNG seed, the fixed `dt`, an ordered list of timestamped/ticked input events, and optional initial state/config. A **recorder** captures a playthrough from a session; a **replay runner** executes a playthrough headlessly (no GL/Android) by stepping the pure simulation deterministically and yields a final state snapshot (and optionally per-tick snapshots). QA uses this in every subsequent UC: record a playthrough exercising the feature, persist it under test resources, replay it in a JVM test, and assert on resulting state. This UC also ships a project skill (under `<repo>/.claude/skills/`) that runs a named playthrough to verify the game. No new player-facing gameplay — this is test/dev infrastructure and the foundation for all later UCs.

## Acceptance Criteria
1. The core simulation is deterministic: identical seed + identical input script + identical fixed `dt` produce an identical end-state snapshot across repeated runs.
2. RNG is obtained through an injected `platform` port (no direct `Math.random`/`Random()` or wall-clock reads in `core` game logic); the seed is set per playthrough.
3. A serializable `Playthrough` format exists (seed, fixed `dt`, ordered input events with tick/timestamp, optional initial state) that round-trips to/from a stable text format (e.g. JSON).
4. A recorder can capture a playthrough (input events + seed) from a simulated/live session and persist it.
5. A replay runner executes a `Playthrough` headlessly on the JVM (no GL, no Android, no native Box2D requirement for the pure-model layer), stepping the simulation deterministically, and returns a final state snapshot.
6. The harness exposes snapshot access/assertion helpers so a JUnit test can replay a playthrough and assert on resulting game state.
7. At least one example playthrough is recorded, persisted under a defined test-resources location, and covered by a passing JVM test (e.g. replays UC01 movement: thrust N ticks → assert ship position/velocity within tolerance).
8. Playthrough artifacts live in a defined location and are stable/diffable across runs.
9. Replays run on the JVM, fast enough for CI, and are wired into the test suite (`./gradlew :core:test` or a dedicated task that CI runs).
10. A project skill under `<repo>/.claude/skills/` (e.g. `playtest`) runs a named playthrough to verify the game and reports pass/fail, documented so future sessions can invoke it.
11. A determinism guard test replays the same playthrough twice and asserts the two snapshots are identical.
12. A short doc (e.g. `docs/PLAYTESTING.md`) explains how to record a new playthrough and add a replay test, so every future UC's QA follows the same pattern.

## Potential Pitfalls & Open Questions
- **Risk** — Floating-point determinism: keep the pure model in plain `Float` math and assert with tolerance where needed; document JVM-determinism scope. Box2D-integrated physics is native — prefer asserting the pure-model layer; treat full Box2D replays as device-tier, not the CI default.
- **Assumption** — Time and randomness must be injected (clock + RNG ports); existing direct uses (if any) get refactored behind ports.
- **Edge case** — Input events must be expressible in the format (joystick vector + magnitude + released, action taps); extend the format as later UCs add inputs.
- **Missing input** — Snapshot scope/format (which state fields) — start with ship kinematics and grow per UC; define an extensible snapshot.

## Original Description
Autonomously captured (owner asleep) per the request to capture every prepared system as a use case, and to make QA define/persist/replay a "playthrough" in every UC test. Per the owner's instruction, because this record/replay harness is complex it is its own use case and the FIRST to implement — it is the basis for testing all later use cases. Also ships the "skill to test the game" the owner asked for. Grounds: the coding guidelines (DIP, determinism, concurrency) and the testing strategy in PROJECT_BRIEF.md.
