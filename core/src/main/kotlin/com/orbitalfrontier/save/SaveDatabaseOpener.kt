package com.orbitalfrontier.save

import app.cash.sqldelight.db.SqlDriver
import com.orbitalfrontier.platform.Logger
import java.io.File

/**
 * Orchestrates a **robust** open of the save database (UC52 AC#3/#4): probe the file before touching the
 * driver, back up before a migration, roll back on failure, and refuse a newer-than-supported save
 * instead of crashing on a downgrade-open.
 *
 * The decision flow, keyed on [SaveSchemaProbe] (`currentVersion` = the app's [OrbitalFrontier.Schema]
 * version):
 *  - **No file / in-memory** → open fresh (schema is created on open). Nothing to back up.
 *  - **`v > currentVersion`** → [SaveOpenResult.UnsupportedNewer] — the file is NOT opened (opening a
 *    newer DB with an older schema is an undefined downgrade that crashes). The caller surfaces a
 *    non-crashing menu; only an explicit, double-confirmed New Game may overwrite it.
 *  - **`v < currentVersion`** → [backupStore] backs up the last-good file, then [openMigrating] runs the
 *    upgrade and [forceOpen] forces it to execute *now* (see below). A throw rolls back to the backup
 *    and retries once → [SaveOpenResult.RecoveredFromBackup] or [SaveOpenResult.Unreadable].
 *  - **`v == currentVersion`** → open + [forceOpen]; a throw falls into the same backup-recovery path.
 *  - **Corrupt header** → restore from a `.bak` if one exists (then reopen once), else [Unreadable].
 *
 * **Why [forceOpen] is injected and called inside the guard.** The on-device `AndroidSqliteDriver`
 * migrates **lazily** — the upgrade callback runs on the first real database access, not when the driver
 * object is constructed. If we only constructed the driver inside the try/catch, a failing migration
 * would throw *later*, outside the guard, where the rollback can't catch it. [forceOpen] performs a
 * trivial statement (`PRAGMA user_version`) that forces the open/migration to happen synchronously
 * inside the try/catch. It is injectable so JVM tests can simulate a migration failure deterministically.
 *
 * Pure `java.io` + the SQLDelight runtime only — engine/Android-free (ADR 0001), so the whole pipeline
 * is JVM-unit-testable with a file-backed JDBC driver and real temp files.
 *
 * @param dbFile the on-disk database file, or null for an in-memory driver (JVM tests) — null short-
 *   circuits to a fresh open.
 * @param currentVersion the schema version this build supports (`OrbitalFrontier.Schema.version`).
 * @param openMigrating opens the driver, running schema creation / migration (the platform factory's
 *   `create()`); each call must produce a usable driver or throw.
 * @param forceOpen forces a freshly-opened [SqlDriver] to actually touch the database so a lazy
 *   migration runs here, inside the guard; defaults to a `PRAGMA user_version` statement.
 */
class SaveDatabaseOpener(
    private val dbFile: File?,
    private val currentVersion: Long,
    private val probe: SaveSchemaProbe,
    private val backupStore: SaveBackupStore,
    private val logger: Logger,
    private val openMigrating: () -> SqlDriver,
    private val forceOpen: (SqlDriver) -> Unit = { driver ->
        driver.execute(null, "PRAGMA user_version", 0)
    },
) {
    /** Run the pipeline and return the outcome (a driver for the openable cases, a reason otherwise). */
    fun open(): SaveOpenResult {
        val file = dbFile ?: return openFresh()
        return when (val probed = probe.probe(file)) {
            SaveSchemaProbeResult.NoFile -> openFresh()
            SaveSchemaProbeResult.Corrupt -> restoreFromBackup(file, reason = "corrupt header")
            is SaveSchemaProbeResult.Version -> openVersioned(file, probed.userVersion)
        }
    }

    /** Open a brand-new / in-memory database (schema created on open). Nothing to migrate or back up. */
    private fun openFresh(): SaveOpenResult =
        try {
            val driver = openMigrating()
            forceOpen(driver)
            SaveOpenResult.Opened(driver)
        } catch (t: Throwable) {
            logger.error(TAG, "Fresh database open failed", t)
            SaveOpenResult.Unreadable
        }

    private fun openVersioned(
        file: File,
        version: Long,
    ): SaveOpenResult =
        when {
            version > currentVersion -> {
                logger.warn(
                    TAG,
                    "Save schema v$version is newer than supported v$currentVersion; refusing to open (no downgrade)",
                )
                SaveOpenResult.UnsupportedNewer
            }
            version < currentVersion -> openWithBackup(file, fromVersion = version)
            else ->
                guardedOpen(
                    onSuccess = { SaveOpenResult.Opened(it) },
                    onFailure = {
                        logger.error(TAG, "Opening current-version save (v$version) failed", it)
                        restoreFromBackup(file, reason = "open failure at current version")
                    },
                )
        }

    /** `v < current`: back up the last-good file, then migrate under the guard; roll back on failure. */
    private fun openWithBackup(
        file: File,
        fromVersion: Long,
    ): SaveOpenResult {
        try {
            if (backupStore.backup(file)) {
                logger.info(TAG, "Backed up save before migrating from v$fromVersion to v$currentVersion")
            } else {
                logger.warn(TAG, "No save file to back up before migrating from v$fromVersion")
            }
        } catch (t: Throwable) {
            // A backup failure must not crash the launch; proceed without rollback protection (logged).
            logger.error(TAG, "Could not back up save before migrating from v$fromVersion", t)
        }
        return guardedOpen(
            onSuccess = {
                logger.info(TAG, "Migrated save from v$fromVersion to v$currentVersion")
                SaveOpenResult.Opened(it)
            },
            onFailure = {
                logger.error(TAG, "Migration from v$fromVersion failed; rolling back to backup", it)
                rollBackAndRetry(file)
            },
        )
    }

    /** Restore the pre-migration backup and reopen ONCE (bounded). */
    private fun rollBackAndRetry(file: File): SaveOpenResult {
        if (!restoreBackupFile(file)) {
            logger.error(TAG, "No backup to roll back to after migration failure; save is unreadable")
            return SaveOpenResult.Unreadable
        }
        return guardedOpen(
            onSuccess = {
                logger.info(TAG, "Recovered the pre-migration save from backup")
                SaveOpenResult.RecoveredFromBackup(it)
            },
            onFailure = {
                logger.error(TAG, "Reopen after backup rollback failed; save is unreadable", it)
                SaveOpenResult.Unreadable
            },
        )
    }

    /** Corrupt / unopenable file: restore from a `.bak` if present and reopen once, else [Unreadable]. */
    private fun restoreFromBackup(
        file: File,
        reason: String,
    ): SaveOpenResult {
        if (!backupStore.hasBackup(file)) {
            logger.error(TAG, "Save $reason and no backup available; save is unreadable")
            return SaveOpenResult.Unreadable
        }
        logger.warn(TAG, "Save $reason; restoring from backup")
        if (!restoreBackupFile(file)) return SaveOpenResult.Unreadable
        // Re-probe the restored file: a sane backup must itself be readable and not newer-than-supported.
        return when (val reprobed = probe.probe(file)) {
            is SaveSchemaProbeResult.Version -> {
                if (reprobed.userVersion > currentVersion) {
                    logger.error(TAG, "Restored backup is itself newer (v${reprobed.userVersion}); unreadable")
                    SaveOpenResult.Unreadable
                } else {
                    guardedOpen(
                        onSuccess = {
                            logger.info(TAG, "Recovered the save from backup")
                            SaveOpenResult.RecoveredFromBackup(it)
                        },
                        onFailure = {
                            logger.error(TAG, "Reopen of restored backup failed; save is unreadable", it)
                            SaveOpenResult.Unreadable
                        },
                    )
                }
            }
            else -> {
                logger.error(TAG, "Restored backup is itself unusable ($reprobed); save is unreadable")
                SaveOpenResult.Unreadable
            }
        }
    }

    private fun restoreBackupFile(file: File): Boolean =
        try {
            backupStore.restore(file)
        } catch (t: Throwable) {
            logger.error(TAG, "Restoring backup failed", t)
            false
        }

    /** Open + [forceOpen] inside one try/catch; dispatch to [onSuccess] or [onFailure]. */
    private inline fun guardedOpen(
        onSuccess: (SqlDriver) -> SaveOpenResult,
        onFailure: (Throwable) -> SaveOpenResult,
    ): SaveOpenResult =
        try {
            val driver = openMigrating()
            forceOpen(driver)
            onSuccess(driver)
        } catch (t: Throwable) {
            onFailure(t)
        }

    private companion object {
        const val TAG = "Save"
    }
}

/**
 * The outcome of [SaveDatabaseOpener.open]. The two openable cases carry the ready-to-use [SqlDriver];
 * the two terminal cases carry no driver and tell the app to start in a non-crashing degraded mode.
 */
sealed interface SaveOpenResult {
    /** Opened cleanly (fresh, current-version, or migrated). */
    data class Opened(val driver: SqlDriver) : SaveOpenResult

    /** The save was rolled back to a backup (after a failed migration or a corrupt file) and opened. */
    data class RecoveredFromBackup(val driver: SqlDriver) : SaveOpenResult

    /** The save is a NEWER schema than this build supports; it was deliberately NOT opened (no downgrade). */
    data object UnsupportedNewer : SaveOpenResult

    /** The save is unreadable/corrupt and no usable backup exists; it was NOT opened. */
    data object Unreadable : SaveOpenResult
}
