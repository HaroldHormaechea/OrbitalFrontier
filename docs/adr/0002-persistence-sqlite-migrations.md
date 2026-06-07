# ADR 0002 — Persistence: SQLite from the start with versioned migrations

- **Status:** Accepted (confirmed by the project owner 2026-06-07)
- **Date:** 2026-06-07

## Context

Orbital Frontier accumulates a sizeable, relational-ish game state: owned ships and their
per-slot loadouts, cargo and fuel per ship, station stock/prices, asteroid-field
depletion, sector layouts, available + accepted missions with progress and timers,
revealed POIs, crew, and settings (see [save-and-persistence.md](../design/save-and-persistence.md)).
The original scaffold (PROJECT_BRIEF.md) assumed a JSON file + SharedPreferences with
SQLite "optional, for larger game state." The owner has decided the game needs structured
persistence and forward-compatible saves from day one.

A constraint from [ADR 0001](0001-engine-choice.md): `core` should stay free of Android-SDK
dependencies so game logic remains JVM-unit-testable.

## Options considered

| Option | For | Against |
|---|---|---|
| **SQLite from the start, single DB** | Structured/queryable; handles relational state and partial updates cheaply; one store for world+player+settings; fits frequent autosave. | More setup than JSON; access layer must respect the `core` JVM-testability constraint. |
| JSON file + SharedPreferences (original) | Simplest; trivially JVM-testable. | Awkward for relational state and partial writes; whole-blob rewrites on frequent autosave; migrations get messy as state grows. |
| Defer (JSON now, SQLite later) | Less upfront work. | A costly migration later once schemas exist; rejected by the owner. |

## Decision

Use **SQLite from the start** as the single persistence store for **world state, player
state, and settings**. **Single save slot, autosave only.** Persist a **save/schema
version** and implement **sequential, version-by-version migrations** (v1→v2→…→vN) so a
save from any prior version upgrades through each step in order.

To preserve ADR 0001's JVM-testability constraint, persistence is accessed through an
abstraction rather than calling Android SQLite directly from `core`. The concrete
mechanism — (a) a `core` persistence interface with an Android-module SQLite impl,
(b) a multiplatform SQLite lib (e.g. SQLDelight), or (c) JDBC-SQLite for tests + Android
SQLite on device — is to be settled during implementation and, if significant, recorded as
a follow-up ADR.

## Consequences

- Structured, queryable saves with cheap partial updates suited to frequent autosaves
  (event-driven, periodic-in-flight, on app pause/exit).
- A **migration framework** is required, plus per-version fixture saves to test the full
  upgrade chain.
- The persistence access layer must not pull the Android SDK into `core`; this shapes the
  module boundary (and may spawn a follow-up ADR).
- Supersedes the brief's original "JSON primary / SQLite optional" data-store choice;
  PROJECT_BRIEF.md `stack.data_stores` and Development → migrations are updated to match.
