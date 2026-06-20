# ADR 0040 — Autosave cadence, indicator & save robustness

- **Status:** Accepted
- **Date:** 2026-06-20

## Context

UC52 hardens saving for real-world mobile use. `docs/design/save-and-persistence.md` left three
items open: the **periodic autosave interval** during flight, an on-screen **autosave indicator**,
and robustness around schema upgrades — **backup-before-migrate** and a defined behaviour on an
**unsupported (newer) or unreadable/corrupt save** (today a corrupt or newer save has no graceful
path and could crash the launch).

Periodic in-flight autosave (UC04 AC#2) already exists: `AutosaveController.update(dt)` enqueues a
save every 20s, off the render thread via the single-writer `SaveExecutor` (ADR 0002 / ADR 0026).
The real new work is the indicator (AC#2), backup-before-migrate + rollback (AC#3), and the
unsupported/unreadable-save policy (AC#4).

Binding constraints: **no schema bump** — this is save *mechanics* (when/how we save, how we
open/migrate), not schema shape, so the format stays **v22**; **minSdk-24** (`java.io` only — no
`java.nio.file.Files`; no SQLite UPSERT); `core` stays JVM-testable (ADR 0001); and the
record/replay determinism harness (ADR 0006/0012) — every existing fixture must stay byte-identical.

## Options considered

| Option | For | Against |
|---|---|---|
| **Probe the SQLite header before opening; pure core seams (probe / backup / opener) with an injectable `forceOpen`; WAL disabled so the single `.db` is authoritative** | Detects a newer/corrupt save *before* a crashing downgrade-open; the `forceOpen`-inside-the-guard makes Android's **lazy** migration throw where rollback can catch it; all seams JVM-unit-testable on real temp files; no schema bump | One more open indirection; WAL-off forgoes WAL's concurrency (irrelevant here — single-writer executor already serialises writes) |
| Catch the exception from a normal migrating open and recover | No header parsing | Android `AndroidSqliteDriver` migrates **lazily** on first query, *outside* any try/catch around construction — the throw escapes the guard; a newer-DB downgrade-open is undefined and may corrupt before it throws |
| Bump the schema / add a migration to record robustness metadata | Explicit | Unnecessary v23 bump + migration + regenerated `.db` + version-tripwire churn for behaviour that needs no stored shape change |
| WAL left enabled (default) | Nothing to configure | `-wal`/`-shm` sidecars make the offset-60 header probe read a stale `user_version` and break the single-file backup; the single `.db` would not be authoritative |

## Decision

**Indicator (AC#2).** A render-only `AutosaveIndicatorState` (IDLE/SAVING/SAVED + fade) drives a
subtle bottom-right "Saving"/"Saved" cue. A cross-thread `AutosaveActivitySignal` (two `AtomicLong`
counters) bridges the off-thread writer to the render thread: `AutosaveController.markSaving()` on
enqueue (render thread), `markSaved()` after the write (executor thread), polled once per frame in
`PlayScreen`. It lives in the frame loop **off `SimulationState`**, so replay stays byte-identical;
the label is ASCII (within `GameFont.REQUIRED_GLYPHS`).

**Cadence (AC#1).** The 20s periodic interval is now **data-driven** via `AutosaveParams`
(`periodicIntervalSeconds`, default 20s, within UC04's 15–30s range), sourced by the controller
rather than a hard-coded constant.

**Backup-before-migrate + rollback (AC#3).** `SaveDatabaseOpener` orchestrates the open:
`SaveSchemaProbe` reads the header directly (16-byte magic + 4-byte big-endian `user_version` at
offset 60 — no DB open) to learn the version / detect corruption; on an **upgrade** (`v < current`)
`SaveBackupStore` copies the last-good `.db` to a sibling `.bak` (atomic temp-then-rename) *before*
migrating, then the opener runs the migration and a distinct injectable **`forceOpen`** (default
`PRAGMA user_version`) **inside one try/catch** so Android's lazy migration executes there; a throw
restores the backup and retries once → recovered, else unreadable. The backup is a **rolling
last-good** copy, never deleted on a routine open.

**Unsupported/corrupt policy (AC#4).** `v > current` → **UnsupportedNewer**: the file is **not**
opened or downgraded. Unreadable/corrupt → restore from `.bak` if one exists, else a clear
non-crashing outcome: the main menu shows with **Continue disabled** and an explanatory notice; New
Game keeps its existing **double-confirm** so a newer save is never silently clobbered (only a
confirmed New Game discards it, via a fresh-database recovery path).

**`journal_mode=DELETE`.** The Android driver is built from a `SupportSQLiteOpenHelper` with
`setWriteAheadLoggingEnabled(false)` on the helper (not a PRAGMA in a callback the framework can
override), so the single `.db` is authoritative — the offset-60 probe and the single-file backup are
correct by construction. (The single-writer executor already serialises writes, so WAL's concurrency
is not needed.)

## Consequences

- **No schema bump (v22):** no migration, no regenerated `.db`, no version-tripwire churn; the
  `SaveVersion`/schema tie-break stays intact.
- **Robust launch:** a newer or corrupt save degrades to a clear menu state instead of crashing; a
  failed migration rolls back to the pre-migration data rather than leaving a half-upgraded DB.
- **Testability:** probe, backup, and opener are pure `java.io` + the SQLDelight runtime, unit-tested
  on real temp files with a file-backed JDBC driver (the `forceOpen` injection simulates a migration
  failure deterministically).
- **Determinism preserved:** the indicator + cadence are render/real-time only, never in
  `sim/Simulation`/`SimulationState`; zero fixture regeneration, no lockstep mirror change.
- **New compile dependency:** `androidx.sqlite:sqlite-framework` (already transitive at runtime via
  the SQLDelight android-driver) is now declared in `android/build.gradle.kts` so the factory can
  build its own open-helper.
- **Residual (pre-release):** the WAL-disabled config and the lazy-migration rollback can only be
  fully verified on a real Android open; JVM tests default to DELETE mode and assert on a real
  DELETE-mode file but cannot exercise the device driver. Backup retention is a single rolling
  last-good `.bak` (no generational history). New-Game in degraded mode discards the unopenable file
  (and its sidecars/backup) only after the player's explicit double-confirm.
