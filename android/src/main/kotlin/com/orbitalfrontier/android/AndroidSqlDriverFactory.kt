package com.orbitalfrontier.android

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.orbitalfrontier.platform.SqlDriverFactory
import com.orbitalfrontier.save.OrbitalFrontier

/**
 * On-device [SqlDriverFactory] (ADR 0003). Produces an [AndroidSqliteDriver] bound to the
 * generated [OrbitalFrontier.Schema]; the driver runs schema creation/migrations against the
 * app's SQLite database file on first open, satisfying the port's "ready-to-use" contract.
 */
class AndroidSqlDriverFactory(
    private val context: Context,
) : SqlDriverFactory {
    override fun create(): SqlDriver = AndroidSqliteDriver(OrbitalFrontier.Schema, context, DATABASE_NAME)

    private companion object {
        const val DATABASE_NAME = "orbital_frontier.db"
    }
}
