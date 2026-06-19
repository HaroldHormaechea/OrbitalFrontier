# Design Note — Save & Persistence

- **Status:** in-progress (storage, save model & **multi-slot management (UC38)** decided; some hardening items open)
- **Last updated:** 2026-06-19
- **Related:** PROJECT_BRIEF.md → in_scope #5, data_stores, Development → migrations; ADR 0001 (core stays JVM-testable), **ADR 0002 (SQLite + migrations)**, **ADR 0003 (SQLDelight access layer)**, **ADR 0026 (save slots)**; every stateful system note (this is cross-cutting)

## Summary

All game state is stored in **SQLite from the start** (not JSON) — **world state, player
state, and settings together in one database**. As of **UC38 (ADR 0026)** the game keeps
**multiple save slots** rather than a single one: every game-state table is **partitioned by a
`slot_id`** column (added to its primary key) in the same single DB, and `meta.active_slot_id`
records which slot the autosave targets and `Continue` resumes. The legacy single autosave
migrates into **slot 0** and keeps working as an ordinary slot. **Settings stay global** (not
per-slot). Each slot autosaves **event-driven on every meaningful action**, **periodically
during flight**, and **on app pause/exit**, and can additionally be written by an explicit
**manual save** from the pause overlay, **loaded**, **deleted**, or seeded with a **new game**
from the save/load screen. A **save/schema version** is stored, with **sequential,
version-by-version migrations** so a player jumping from e.g. v1 to v4 has each upgrade step
applied in order.

## Goals

- Reliable, corruption-resistant persistence that survives Android killing the app.
- One coherent store for all systems; no per-system file sprawl.
- Forward-compatible saves across many versions via ordered migrations.

## Mechanics / ideas

**Storage — SQLite, single DB = the save.** A single SQLite database holds everything:
- **World state:** sector seeds/layout, POI states, **asteroid-field depletion**, **station
  stock/prices/offers**, **revealed hidden POIs**, encounter bookkeeping.
- **Player state:** **credits**, **owned ships + per-slot loadouts + active ship**, **cargo
  per ship**, **fuel per ship**, **crew**, **accepted missions + progress + timers**,
  **available missions per source**, reputation (later).
- **Settings:** handedness/control layout, audio, etc.

**Save slots & cadence (UC38 / ADR 0026).** **Multiple slots** in one DB, each independently
autosaved, plus an explicit manual save. The active slot (`meta.active_slot_id`) is the one the
autosave writes to and `Continue` resumes; a load / save-as re-points it. Writes are triggered:
1. **Event-driven** on every meaningful action — **jump, buy, sell, change/ refit a slot**,
   accept/complete/fail a mission, dock/undock — to the **active slot**.
2. **Periodic autosave during flight** (every ~20 s) to the active slot.
3. **On app pause/exit** (Android `onPause`) — the critical flush, since the OS can kill
   the app at any time.
4. **Manual save** from the pause overlay into any chosen slot (an occupied slot warns before
   overwrite); the save/load screen also offers **load**, **delete** (confirmed), and **new game
   into an empty slot**.

**Slot mechanics.** Every game-state table carries a `slot_id` (first PK column), so a read /
write / delete is scoped by `WHERE slot_id = ?` and slots never bleed into each other (isolation).
The `game_state` header additionally holds per-slot display metadata — `name`,
`last_saved_epoch_millis`, `play_time_seconds`. An **autosave updates only the gameplay columns +
that metadata, never `name`** (a seed-then-targeted-UPDATE guard, minSdk-24-safe — no UPSERT), so
autosave can't clobber a player-chosen name; rename is a separate targeted write. "Last saved" is
the one genuine wall-clock value, read through an injected `Clock` **only** at the save boundary
(the simulation stays time-free, ADR 0006). **Play time** is accumulated from the per-frame `dt`
while actually playing and persisted per slot, but is **not** part of the deterministic replay state.

**Versioning & migrations.** Persist a **save/schema version**. Migrations are
**sequential and version-by-version** (v1→v2→v3→…→vN); loading an older save applies each
step in order up to the current version. Implemented with **SQLDelight's `.sqm` migration
files** (one per version step) — see **ADR 0003**.

**Access layer — SQLDelight (ADR 0003).** Schema and queries are authored as `.sq` files
(typesafe generated Kotlin) in `core`; the `SqlDriver` is **injected by the platform** —
`AndroidSqliteDriver` on device, `JdbcSqliteDriver`/in-memory in JVM tests — so `core`
never depends on the Android SDK (honoring ADR 0001). This resolves the JVM-testability
tension noted below.

## Player-facing behavior

- **New Game / Continue** plus a **LOAD GAME** entry (main menu) and a **SAVE** entry (pause
  overlay). The save/load screen lists each slot with its name, last-saved time, and a short state
  summary (credits, sector, play time), and supports save / load / delete (confirmed) / new-game.
  Continue resumes the active slot; the legacy autosave appears as slot 0 (UC38). The frequent
  autosave still runs in the background.

## Data & state

This note **owns the canonical save schema**. All systems serialize through it; schema
changes must be **centralized** and always paired with a **version bump + a migration
step**. Keep write operations cheap/atomic enough to run on the frequent autosave triggers
without stalling the frame.

## Dependencies & interactions

- **Every stateful system** persists here (ship loadouts, economy, missions, world, crew,
  settings). Any change to a system's persisted shape **must add a migration**.
- Couples tightly to **ADR 0001's constraint** that `core` stays JVM-testable (see Open
  questions — Android SQLite is an SDK API).

## Open questions

- ~~Access layer vs. JVM-testability~~ — **RESOLVED: SQLDelight (ADR 0003)** — driver
  injected per platform; `core` depends only on SQLDelight's runtime, tests use the JDBC/
  in-memory driver.
- **Periodic autosave interval** during flight.
- **Corruption handling:** writes MUST be **atomic/transactional** so a failed save rolls
  back and never corrupts the last good save (now a **binding rule** — see
  `docs/coding-guidelines.md` → Error handling). Remaining: backup-before-migrate for schema
  upgrades, and behavior on an unsupported/unreadable save.
- **Migration testing:** keep a fixture DB per version to test the full upgrade chain.

## Decided

- **SQLite from the start** (not JSON).
- **World + player + settings all in SQLite.**
- ~~Single save slot, autosave only.~~ → **Multiple save slots (UC38 / ADR 0026):** every
  game-state table partitioned by `slot_id`; `meta.active_slot_id` pointer; legacy save migrates to
  slot 0; manual save/load/delete/new-game alongside the per-slot autosave. Settings stay global.
- Save **event-driven + periodic-in-flight + on pause/exit**.
- **Store save version; sequential version-by-version migrations** (v1→…→vN).
- **Access layer = SQLDelight** (ADR 0003): `.sq` schema/queries in `core`, driver injected
  per platform, `.sqm` versioned migrations.
- **Saves are atomic/transactional** — a failed write rolls back, never corrupts the last
  good save (binding rule, `docs/coding-guidelines.md` → Error handling).

## References

libGDX/Android SQLite, SQLDelight (multiplatform option). See ADR 0002 and PROJECT_BRIEF.md
→ Development → migrations.
