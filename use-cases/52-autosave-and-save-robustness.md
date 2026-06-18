# Use Case 52: Periodic autosave, indicator & save robustness

## Summary
Harden saving for real-world mobile use. save-and-persistence.md leaves three items open: **periodic autosave during flight** (interval TBD — today saving is event-driven, so a crash mid-flight loses progress), an on-screen **autosave indicator**, and robustness around schema upgrades — **backup-before-migrate** and defined **behavior on an unsupported/unreadable save** (today a corrupt/newer save has no graceful path). Add a periodic in-flight autosave on a fixed interval with a subtle saving indicator, back up the DB before any migration, and handle unreadable/unsupported saves gracefully (recover from backup or surface a clear error rather than crashing).

## Acceptance Criteria
1. The game autosaves periodically during flight on a defined interval (in addition to existing event-driven saves), without a perceptible hitch.
2. A subtle autosave indicator shows when a save is in progress/just completed.
3. The DB is backed up before any schema migration runs; a failed migration can roll back to the backup.
4. Opening an unsupported (newer schema) or unreadable/corrupt save produces a clear, non-crashing outcome (restore from backup or an explanatory error), not a hard crash.
5. `./gradlew :core:ktlintCheck :core:test` green; tests cover the autosave cadence, backup-before-migrate, and the corrupt/unsupported-save path.

## Potential Pitfalls & Open Questions
- **Open question** — autosave interval is TBD; pick a sensible default (e.g. 30–60 s and on key events) and make it data-driven.
- **Edge case** — autosave must never write a partial/torn DB; use the existing single-DB transaction boundary and a temp-then-rename or backup copy.
- **Dependency** — migration-chain test fixtures (a fixture DB per schema version) support verifying the upgrade chain; add them alongside.

## Original Description
Autonomously captured from the feature catalog (save-and-persistence.md: periodic autosave, autosave indicator, backup-before-migrate, and unsupported/unreadable-save handling all open).
