# Use Case 04: Full game-state save & load

## Summary
Extend persistence from settings-only (UC01) to the **full game state**: current sector, ship kinematics (position/velocity/heading), owned-ship loadout(s), and any then-existing player state, in a single autosave slot. Saves are SQLite via SQLDelight (ADR 0002/0003), **transactional** (a failed write never corrupts the last good save), triggered event-driven (e.g. on jump, and on dock once UC05 lands), periodically during flight, and on app pause/exit. The schema `save_version` is bumped with a **sequential migration** from the v1 (settings-only) schema, tested with a fixture DB. New Game initializes default state; Continue restores the save. Round-trippable and replay-testable (UC02). This is the persistence backbone every later UC writes into.

## Acceptance Criteria
1. Full game state (current sector, ship kinematics, owned-ship loadout, credits/cargo as they exist) is saved to the single-slot SQLite DB.
2. Autosave triggers: on key events (jump now; dock from UC05), periodically during flight, and on app pause/exit.
3. Writes are transactional; an induced failure leaves the previous good save intact (no corruption).
4. `save_version` is bumped and a sequential migration upgrades a v1 (settings-only) DB to the new schema; covered by a fixture-DB migration test.
5. New Game initializes default state; Continue restores the saved state.
6. Round-trip: save → reload → restored state matches (sector, position, loadout).
7. The save schema is centralized; serialization is JVM-testable against an in-memory JDBC driver.
8. A recorded playthrough (UC02) drives movement + a jump, saves, reloads, and asserts state equality.
9. The migration chain (v1 → current) is verified by SQLDelight `verifyMigrations` and a replayed round-trip test.

## Potential Pitfalls & Open Questions
- **Edge case** — Transient physics: persist kinematics and re-seed the Box2D body on load (don't persist Box2D internals).
- **Risk** — Schema churn: later UCs add tables/columns; each must add a migration step and bump the version.
- **Missing input** — Periodic autosave interval: choose a sane default (e.g. ~15–30s) and document it.

## Original Description
Autonomously captured from the Save & Persistence design note (docs/design/save-and-persistence.md) and ADR 0002/0003, per the owner's request to capture every prepared system.
