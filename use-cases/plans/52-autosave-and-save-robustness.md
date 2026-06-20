---
plan_for: use-cases/52-autosave-and-save-robustness.md
work_branch: feat/uc-52-autosave-robustness
team: orbital-frontier-uc-52
approved: 2026-06-20
---

# UC-52 (periodic autosave indicator + save robustness) — FINAL APPROVED PROPOSAL (challenger approved Revision 1).

## Scope note (important for planning)
**AC#1 (periodic autosave) already exists** from UC04 — `AutosaveController.update(dt)` enqueues a periodic save every 20s, wired in PlayScreen.kt:1172, off-thread via `SaveExecutor`, already unit-tested (AutosaveControllerTest). The real new work is AC#2 (indicator), AC#3 (backup-before-migrate + rollback), AC#4 (unsupported/corrupt handling). **No schema bump — stays v22.**

## Solution

**A. Robust DB open (AC#3/#4) — pure, JVM-testable core seams (java.io only, minSdk-24 safe):**
1. `core/.../save/SaveSchemaProbe.kt` (NEW, pure) — reads the SQLite file header directly: 16-byte magic (detect garbage/truncated/corrupt) + 4-byte big-endian `user_version` at offset 60 (no DB open, so a NEWER DB is read without triggering a downgrade crash). Sealed result `NoFile`/`Corrupt`/`Version(v)`.
2. `core/.../save/SaveBackupStore.kt` (NEW, pure) — sibling `.bak` via atomic temp-then-rename (FileChannel.transferTo + File.renameTo): `backup`/`restore`/`hasBackup`/`deleteBackup`.
3. `core/.../save/SaveDatabaseOpener.kt` (NEW, orchestrator) — inputs `dbFile: File?`, `currentVersion`, probe, backup store, logger, `openMigrating: () -> SqlDriver`, and a distinct injectable **`forceOpen: (SqlDriver) -> Unit`** (default `driver.execute(null, "PRAGMA user_version", 0)`). Flow: NoFile/in-memory → open (fresh). `v > current` → **UnsupportedNewer** (do NOT open). `v < current` → **backup() then openMigrating() then forceOpen()** all inside one try/catch; on throw → restore() + bounded single retry through the same guarded routine → `RecoveredFromBackup`/`Unreadable`. `v == current` → open + forceOpen. `Corrupt` → restore+re-enter if `.bak`, else `Unreadable`. The forceOpen-inside-the-guard is what makes Android's **lazy** migration throw where rollback can catch it (the bare `create()` at OrbitalFrontierGame.kt:172 migrates lazily on the first query, `ensureInitialized()` at :178 — outside any guard today).
4. `core/.../platform/SqlDriverFactory.kt` (MODIFY) — add `databaseFile(): java.io.File?` (null=in-memory).
5. `android/.../AndroidSqlDriverFactory.kt` (MODIFY) — implement `databaseFile()` = `context.getDatabasePath("orbital_frontier.db")`; **construct AndroidSqliteDriver with WAL disabled (`journal_mode=DELETE`)** so the single `.db` is authoritative (probe + single-file backup correct by construction). ⚠️ **Developer note from challenger (non-blocking but required): disable WAL on the SupportSQLiteOpenHelper config (e.g. `setWriteAheadLoggingEnabled(false)`), not via a PRAGMA in the wrong callback that the framework can silently override.** Must be verified effective on a real Android open — JVM tests default to DELETE and won't catch a misplaced PRAGMA.
6. `core/.../app/OrbitalFrontierGame.kt` (MODIFY, ~165-190) — route create() through `SaveDatabaseOpener`. `Opened`/`RecoveredFromBackup` → proceed as today. `UnsupportedNewer`/`Unreadable` → non-crashing: main menu with Continue disabled + explanatory NotificationQueue message; New-Game keeps its existing double-confirm so a newer save is never silently clobbered. No dedicated error screen.

**B. Autosave indicator (AC#2) — pure state + thread bridge + draw:**
7. `core/.../render/AutosaveIndicatorState.kt` (NEW, pure, like CombatHudState) — phase IDLE/SAVING/SAVED + fade timer; `onSaveStarted`/`onSaveFinished`/`update(dt)`; derived `visible`/`alpha`/`label`. **ASCII-only label** ("Saving"/"Saved"), or any non-ASCII glyph MUST be in `GameFont.REQUIRED_GLYPHS`.
8. `core/.../save/AutosaveActivitySignal.kt` (NEW) — thread-safe AtomicLong enqueued/completed counters; `markSaving()` (render thread), `markSaved()` (executor thread), render-thread `poll()`.
9. `core/.../save/AutosaveController.kt` (MODIFY) — inject the signal (default no-op): `markSaving()` at enqueue, wrap the executor task so `markSaved()` runs after `repository.saveGameState`. Source the interval from a data-driven tunable (new `*Params`, AC#1 "data-driven" delta).
10. `core/.../screen/PlayScreen.kt` (MODIFY) — each frame poll the signal → feed `AutosaveIndicatorState`, `update(dt)`, draw via HudRenderer. Render-only; NOT in sim/Simulation (replay stays byte-identical).
11. `core/.../render/HudRenderer.kt` (MODIFY) — draw the subtle indicator with alpha.

**C. Docs (developer authorized for docs/**):**
12. `docs/adr/0040-autosave-cadence-and-save-robustness.md` (NEW, next free number) — interval (20s, data-driven), indicator, backup-before-migrate+rollback, unsupported-newer/corrupt policy, journal_mode=DELETE decision + pre-release residual, backup retention (rolling last-good, never deleted on routine open).
13. `docs/design/save-and-persistence.md` (MODIFY) — move the three open items to Decided.

## Files Affected
**Production (developer):** NEW core save/SaveSchemaProbe.kt, save/SaveBackupStore.kt, save/SaveDatabaseOpener.kt, save/AutosaveActivitySignal.kt, render/AutosaveIndicatorState.kt, the autosave-interval `*Params`; MODIFY core app/OrbitalFrontierGame.kt, save/AutosaveController.kt, screen/PlayScreen.kt, render/HudRenderer.kt, platform/SqlDriverFactory.kt; MODIFY android AndroidSqlDriverFactory.kt; docs NEW adr/0040, MODIFY design/save-and-persistence.md.
**Test (qa):** SaveSchemaProbeTest, SaveBackupStoreTest (temp files), SaveDatabaseOpenerTest (real temp-file DBs + file-backed JdbcSqliteDriver + Schema.migrate; cover fresh/current/upgrade-with-backup/migration-failure-rollback-via-forceOpen/unsupported-newer/corrupt-restore; pin `journal_mode=DELETE`), AutosaveIndicatorStateTest; extend AutosaveControllerTest for the signal; `Uc52AutosaveIndicatorSourceTest` source-anchored guard (PlayScreen/HudRenderer indicator wiring + AutosaveController signal wiring + WAL-disabled assertion on the Android driver factory + ASCII/REQUIRED_GLYPHS check). Recommend GENERATING newer/corrupt fixtures in-test (not committed binaries; keep `databases/` verifyMigrations baselines clean).

## Risks
- Probe `user_version`(offset 60) == the value AndroidSqliteDriver migrates from — true once WAL is disabled; QA asserts on a real DELETE-mode file.
- Backup = copy of the flushed single `.db`; migration stays in the existing single transaction (invariant #6).
- UnsupportedNewer never auto-clobbers (only New-Game double-confirm can overwrite).
- Determinism: indicator + cadence are render/real-time only; confirmed out of sim/Simulation; no lockstep-mirror change.

Gate: `./gradlew :core:ktlintCheck :core:test`. Ready for the developer.

## Challenger verdict
**APPROVED** after one revision round. Blocked initial proposal on two Major on-device "false-green" correctness gaps (would pass JVM tests, fail on device): (1) AndroidSqliteDriver migrates LAZILY on first query (ensureInitialized, outside the open guard) → rollback would never engage → closed via injectable forceOpen (PRAGMA user_version) inside the try/catch; (2) WAL sidecars broke the offset-60 probe + single-file backup → closed by journal_mode=DELETE making the single .db authoritative. Minors folded: bounded restore-reentry, backup retention, ASCII/REQUIRED_GLYPHS guard. No schema bump (v22), determinism preserved (render/real-time only, zero fixture regen, no lockstep change), minSdk-24 java.io-only. Non-blocking developer note: disable WAL via setWriteAheadLoggingEnabled(false) on the SupportSQLiteOpenHelper config, not a PRAGMA in a callback the framework can override — verify effective on a real Android open.
