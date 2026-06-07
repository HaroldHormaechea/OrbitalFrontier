# ADR 0003 — Persistence access layer: SQLDelight

- **Status:** Accepted (confirmed by the project owner 2026-06-07)
- **Date:** 2026-06-07
- **Refines:** [ADR 0002](0002-persistence-sqlite-migrations.md) (SQLite + versioned migrations); constrained by [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable)

## Context

[ADR 0002](0002-persistence-sqlite-migrations.md) chose SQLite from the start with
sequential, version-by-version migrations. [ADR 0001](0001-engine-choice.md) requires
`core` (all game logic) to stay free of Android-SDK dependencies so it is JVM-unit-
testable. Android's built-in `android.database.sqlite` is an SDK API and would pull the
SDK into `core` if used directly. We need a single access layer that runs on-device and in
JVM tests, with first-class migration support.

## Options considered

| Option | For | Against |
|---|---|---|
| **SQLDelight** | Multiplatform; typesafe generated Kotlin from `.sq`; `AndroidSqliteDriver` on device + `JdbcSqliteDriver`/in-memory in JVM tests; **native `.sqm` versioned migrations** (matches ADR 0002); one source of SQL. | Adds a Gradle plugin + code generation step; a SQL-first authoring style to learn. |
| Hand-rolled `core` interface + Android impl | No new deps. | Hand-write migration runner, mapping, and a fake for tests; more boilerplate; easy to drift. |
| JDBC-SQLite (tests) + Android SQLite (device) behind an interface | Real DB on both paths. | Two driver code paths to keep in sync; migrations written/verified twice. |

## Decision

Use **SQLDelight**. Define the schema and queries as `.sq` files (typesafe generated
Kotlin APIs) consumed by `core`. Inject the `SqlDriver` from the platform:
`AndroidSqliteDriver` on device, `JdbcSqliteDriver` (or an in-memory driver) in JVM unit
tests — so `core` depends only on SQLDelight's runtime, never the Android SDK. Versioned
**`.sqm` migration files** implement ADR 0002's sequential migration requirement, and
SQLDelight's schema verification guards the upgrade chain.

## Consequences

- `core` stays JVM-testable: tests exercise the real SQL against an in-memory/JDBC driver.
- Persistence is **driver-injected** — `core` takes a `SqlDriver`; the `android` module
  supplies the Android driver, test code the JDBC/in-memory one.
- Adds the SQLDelight Gradle plugin + generated sources to the build; schema lives in
  `.sq`, migrations in numbered `.sqm` files.
- Per-version fixture databases (from ADR 0002) test the migration chain end-to-end.
- `PROJECT_BRIEF.md` stack/build references SQLDelight as the persistence access layer.
