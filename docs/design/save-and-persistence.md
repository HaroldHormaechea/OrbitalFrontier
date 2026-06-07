# Design Note — Save & Persistence

- **Status:** in-progress (storage & save model decided; access-layer & migration detail open)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #5, data_stores, Development → migrations; ADR 0001 (core stays JVM-testable), **ADR 0002 (persistence)**; every stateful system note (this is cross-cutting)

## Summary

All game state is stored in **SQLite from the start** (not JSON) — **world state, player
state, and settings together in one database**. There is a **single save slot** with
**autosave only** (no manual saves). The save is written **event-driven on every
meaningful action**, **periodically during flight**, and **on app pause/exit**. A
**save/schema version** is stored, with **sequential, version-by-version migrations** so a
player jumping from e.g. v1 to v4 has each upgrade step applied in order.

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

**Save slot & cadence.** **Single slot, autosave only.** Writes are triggered:
1. **Event-driven** on every meaningful action — **jump, buy, sell, change/ refit a slot**,
   accept/complete/fail a mission, dock/undock.
2. **Periodic autosave during flight** (interval _TBD_).
3. **On app pause/exit** (Android `onPause`) — the critical flush, since the OS can kill
   the app at any time.

**Versioning & migrations.** Persist a **save/schema version**. Migrations are
**sequential and version-by-version** (v1→v2→v3→…→vN); loading an older save applies each
step in order up to the current version. This requires a small **migration framework**
(an ordered list of upgrade steps keyed by version).

## Player-facing behavior

- **New Game / Continue** (single slot). An **autosave indicator**; no manual save UI.

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

- **Access layer vs. JVM-testability (key):** ADR 0001 says `core` must avoid Android-SDK
  deps so logic is JVM-testable, but Android's `android.database.sqlite` *is* an SDK API.
  Options: (a) a persistence **interface in `core`** with an Android SQLite impl in the
  `android` module; (b) a **multiplatform SQLite** lib (e.g. SQLDelight) usable from
  `core` + tests; (c) JDBC-SQLite for JVM tests, Android SQLite on device. → resolve in
  **ADR 0002**.
- **Periodic autosave interval** during flight.
- **Corruption/too-old handling:** backup-before-migrate? behavior on an unsupported/
  unreadable save?
- **Migration testing:** keep a fixture DB per version to test the full upgrade chain.

## Decided

- **SQLite from the start** (not JSON).
- **World + player + settings all in SQLite.**
- **Single save slot, autosave only.**
- Save **event-driven + periodic-in-flight + on pause/exit**.
- **Store save version; sequential version-by-version migrations** (v1→…→vN).

## References

libGDX/Android SQLite, SQLDelight (multiplatform option). See ADR 0002 and PROJECT_BRIEF.md
→ Development → migrations.
