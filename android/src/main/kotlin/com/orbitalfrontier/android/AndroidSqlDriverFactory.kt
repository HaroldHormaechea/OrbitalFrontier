package com.orbitalfrontier.android

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.orbitalfrontier.platform.SqlDriverFactory
import com.orbitalfrontier.save.OrbitalFrontier
import java.io.File

/**
 * On-device [SqlDriverFactory] (ADR 0003). Produces an [AndroidSqliteDriver] bound to the
 * generated [OrbitalFrontier.Schema]; the driver runs schema creation/migrations against the
 * app's SQLite database file on first open, satisfying the port's "ready-to-use" contract.
 *
 * **WAL disabled — `journal_mode=DELETE` (UC52, challenger-mandated).** The driver is built from a
 * [SupportSQLiteOpenHelper] with **write-ahead logging turned off** via
 * [SupportSQLiteOpenHelper.setWriteAheadLoggingEnabled] on the helper itself — NOT a `PRAGMA` in a
 * callback, which the framework can silently re-enable. With WAL off there are no `-wal` / `-shm`
 * sidecar files, so the single `.db` is authoritative: the UC52 header probe
 * ([com.orbitalfrontier.save.SaveSchemaProbe]) reads the real committed `user_version`, and the
 * single-file backup ([com.orbitalfrontier.save.SaveBackupStore]) captures the whole database.
 */
class AndroidSqlDriverFactory(
    private val context: Context,
) : SqlDriverFactory {
    override fun create(): SqlDriver {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(DATABASE_NAME)
                .callback(AndroidSqliteDriver.Callback(OrbitalFrontier.Schema))
                .build()
        val openHelper =
            FrameworkSQLiteOpenHelperFactory().create(configuration).apply {
                // journal_mode=DELETE: a single authoritative .db file (no WAL sidecars) so the UC52
                // header probe + single-file backup are correct by construction.
                setWriteAheadLoggingEnabled(false)
            }
        return AndroidSqliteDriver(openHelper)
    }

    /** The app's on-disk SQLite file (UC52 — probed + backed up by the robust-open pipeline). */
    override fun databaseFile(): File = context.getDatabasePath(DATABASE_NAME)

    private companion object {
        const val DATABASE_NAME = "orbital_frontier.db"
    }
}
