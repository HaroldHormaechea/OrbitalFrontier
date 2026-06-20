package com.orbitalfrontier.platform

import app.cash.sqldelight.db.SqlDriver
import java.io.File

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

    /**
     * The on-disk database file this factory opens, or null for an in-memory driver (JVM tests). UC52:
     * the robust-open pipeline ([com.orbitalfrontier.save.SaveDatabaseOpener]) probes this file's header
     * and backs it up *before* [create] migrates it. A null return means "no file to probe/back up — open
     * fresh", so in-memory backends keep working unchanged. Defaults to null so non-file backends need
     * not override it.
     */
    fun databaseFile(): File? = null
}
