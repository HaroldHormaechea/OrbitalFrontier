package com.orbitalfrontier.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * Injected port that produces a ready-to-use SQLDelight [SqlDriver] (DIP — ADR 0003).
 *
 * The `android` module supplies an `AndroidSqliteDriver`-backed implementation (on-device
 * SQLite); JVM tests supply a `JdbcSqliteDriver` (in-memory). `core` depends only on this
 * abstraction and SQLDelight's runtime, never the Android SDK (ADR 0001).
 *
 * Implementations MUST return a driver whose schema has already been created/migrated to the
 * current version, so callers can immediately construct the generated database and query it.
 */
interface SqlDriverFactory {
    fun create(): SqlDriver
}
