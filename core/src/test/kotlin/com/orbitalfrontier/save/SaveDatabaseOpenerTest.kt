package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.NoOpLogger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The heart of UC52's robustness work: [SaveDatabaseOpener] orchestration tests (AC#3 backup-before-
 * migrate + rollback, AC#4 unsupported/unreadable handling), exercised against **real temp-file SQLite
 * databases** with a file-backed [JdbcSqliteDriver] and the genuine [OrbitalFrontier.Schema] — the same
 * `core` code that runs on the Android driver on device (ADR 0003).
 *
 * The version *decision* is driven by the real header probe reading the file's `user_version` (which the
 * seeding helper stamps), so each branch is taken exactly as it would be on device. The real per-step
 * migration SQL is covered separately by [SaveMigrationTest]; here the focus is the open/backup/rollback
 * **control flow**, including the AC#3 proof that a **lazy-migration-style throw from `forceOpen` inside
 * the guard** is caught and rolled back (the on-device `AndroidSqliteDriver` migrates lazily on first
 * access, so the failure surfaces at `forceOpen`, not at driver construction).
 */
class SaveDatabaseOpenerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val probe = SaveSchemaProbe()
    private val backupStore = SaveBackupStore()
    private val currentVersion = OrbitalFrontier.Schema.version

    /** Every driver opened during a test, closed in [tearDown] so temp files release cleanly. */
    private val openedDrivers = mutableListOf<SqlDriver>()

    @After
    fun tearDown() {
        openedDrivers.forEach { runCatching { it.close() } }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** Open a file-backed driver (tracked for cleanup); [create] installs the schema for a fresh open. */
    private fun openFile(
        file: File,
        create: Boolean,
    ): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        if (create) OrbitalFrontier.Schema.create(driver)
        openedDrivers += driver
        return driver
    }

    /** Seed a genuine SQLite DB file at [version]: real schema + a stamped header `user_version`. */
    private fun seedDatabase(
        file: File,
        version: Long,
    ) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OrbitalFrontier.Schema.create(driver)
        driver.execute(null, "PRAGMA user_version = $version", 0)
        driver.close()
    }

    private fun newFilePath(name: String): File = File(tempFolder.root, name)

    private fun opener(
        dbFile: File?,
        openMigrating: () -> SqlDriver,
        forceOpen: (SqlDriver) -> Unit = { it.execute(null, "PRAGMA user_version", 0) },
        version: Long = currentVersion,
    ) = SaveDatabaseOpener(
        dbFile = dbFile,
        currentVersion = version,
        probe = probe,
        backupStore = backupStore,
        logger = NoOpLogger,
        openMigrating = openMigrating,
        forceOpen = forceOpen,
    )

    // --- fresh / in-memory: nothing to probe or back up ------------------------------------------------

    @Test
    fun `a null dbFile opens fresh in-memory without touching the backup store`() {
        val result =
            opener(
                dbFile = null,
                openMigrating = {
                    val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                    OrbitalFrontier.Schema.create(d)
                    openedDrivers += d
                    d
                },
            ).open()

        assertTrue("a null dbFile must open fresh, got $result", result is SaveOpenResult.Opened)
    }

    @Test
    fun `a missing dbFile (fresh install) opens fresh`() {
        val file = newFilePath("fresh.db")
        var opens = 0

        val result =
            opener(
                dbFile = file,
                openMigrating = {
                    opens++
                    openFile(file, create = true)
                },
            ).open()

        assertTrue("a fresh install must open, got $result", result is SaveOpenResult.Opened)
        assertEquals("fresh open uses a single driver open", 1, opens)
        assertTrue("a fresh open must not leave a backup", !backupStore.hasBackup(file))
    }

    // --- v == current: open + forceOpen, no backup -----------------------------------------------------

    @Test
    fun `a current-version save opens without taking a backup`() {
        val file = newFilePath("current.db")
        seedDatabase(file, currentVersion)

        val result =
            opener(
                dbFile = file,
                openMigrating = { openFile(file, create = false) },
            ).open()

        assertTrue("a current-version save must open, got $result", result is SaveOpenResult.Opened)
        assertTrue("an unchanged-version open must not back up", !backupStore.hasBackup(file))
    }

    // --- AC#3: v < current → backup THEN migrate THEN forceOpen ----------------------------------------

    @Test
    fun `an older save is backed up before it is migrated, then opens`() {
        val file = newFilePath("older.db")
        seedDatabase(file, currentVersion - 1)

        val result =
            opener(
                dbFile = file,
                openMigrating = { openFile(file, create = false) },
            ).open()

        assertTrue("a migrated save must open, got $result", result is SaveOpenResult.Opened)
        assertTrue("AC#3: the pre-migration backup must be present after a v<current open", backupStore.hasBackup(file))
    }

    // --- AC#3: migration failure → rollback via forceOpen-inside-the-guard -----------------------------

    @Test
    fun `a lazy-migration failure from forceOpen rolls back to the backup and recovers`() {
        val file = newFilePath("rollback.db")
        seedDatabase(file, currentVersion - 1)
        val goodBytes = file.readBytes()

        var forceCalls = 0
        val result =
            opener(
                dbFile = file,
                openMigrating = { openFile(file, create = false) },
                forceOpen = { driver ->
                    forceCalls++
                    if (forceCalls == 1) {
                        // Simulate a lazy migration that corrupts the live file then throws (the throw lands
                        // at forceOpen, INSIDE the guard — exactly the Android lazy-migration shape).
                        file.writeBytes(ByteArray(120) { 0x00 })
                        throw IllegalStateException("simulated migration failure")
                    }
                    driver.execute(null, "PRAGMA user_version", 0)
                },
            ).open()

        assertTrue("AC#3: a rolled-back save must recover from backup, got $result", result is SaveOpenResult.RecoveredFromBackup)
        assertEquals("the guard must retry exactly once (bounded): one failing + one succeeding forceOpen", 2, forceCalls)
        assertArrayEquals("rollback must restore the pre-migration bytes onto the live file", goodBytes, file.readBytes())
    }

    @Test
    fun `a migration failure that persists after rollback ends Unreadable after a bounded single retry`() {
        val file = newFilePath("rollback-fails.db")
        seedDatabase(file, currentVersion - 1)

        var forceCalls = 0
        val result =
            opener(
                dbFile = file,
                openMigrating = { openFile(file, create = false) },
                forceOpen = {
                    forceCalls++
                    throw IllegalStateException("forceOpen always fails")
                },
            ).open()

        assertEquals(SaveOpenResult.Unreadable, result)
        assertEquals("bounded single retry: the migrate attempt + one post-rollback retry, never a loop", 2, forceCalls)
    }

    // --- AC#4: v > current → UnsupportedNewer, DB never opened -----------------------------------------

    @Test
    fun `a newer-than-supported save is refused without opening the database`() {
        val file = newFilePath("newer.db")
        seedDatabase(file, currentVersion + 1)

        var opens = 0
        val result =
            opener(
                dbFile = file,
                openMigrating = {
                    opens++
                    openFile(file, create = false)
                },
            ).open()

        assertEquals("AC#4: a newer save must be refused, not downgraded", SaveOpenResult.UnsupportedNewer, result)
        assertEquals("AC#4: a newer save must NEVER be opened (no downgrade-open)", 0, opens)
        assertTrue("a refused newer save must not be clobbered with a backup", !backupStore.hasBackup(file))
    }

    // --- AC#4: corrupt handling ------------------------------------------------------------------------

    @Test
    fun `a corrupt save with a usable backup is restored and reopened`() {
        val file = newFilePath("corrupt-with-bak.db")
        // A real, current backup beside a garbage live file.
        seedDatabase(file, currentVersion)
        backupStore.backup(file)
        val goodBytes = file.readBytes()
        file.writeBytes(ByteArray(200) { 0x66 }) // corrupt the live file (bad magic)

        val result =
            opener(
                dbFile = file,
                openMigrating = { openFile(file, create = false) },
            ).open()

        assertTrue("a corrupt save with a backup must recover, got $result", result is SaveOpenResult.RecoveredFromBackup)
        assertArrayEquals("the corrupt live file must be replaced by the backup", goodBytes, file.readBytes())
    }

    @Test
    fun `a corrupt save with no backup is Unreadable, not a crash`() {
        val file = newFilePath("corrupt-no-bak.db")
        file.writeBytes(ByteArray(200) { 0x66 }) // garbage, no .bak

        var opens = 0
        val result =
            opener(
                dbFile = file,
                openMigrating = {
                    opens++
                    openFile(file, create = false)
                },
            ).open()

        assertEquals("AC#4: a corrupt save with no backup must be Unreadable", SaveOpenResult.Unreadable, result)
        assertEquals("an unreadable corrupt file must not be opened", 0, opens)
    }

    @Test
    fun `a current-version open failure falls into backup recovery`() {
        val file = newFilePath("current-fails.db")
        seedDatabase(file, currentVersion)
        backupStore.backup(file)

        var forceCalls = 0
        val result =
            opener(
                dbFile = file,
                openMigrating = { openFile(file, create = false) },
                forceOpen = { driver ->
                    forceCalls++
                    if (forceCalls == 1) throw IllegalStateException("current-version open boom")
                    driver.execute(null, "PRAGMA user_version", 0)
                },
            ).open()

        assertTrue("a failed current-version open must fall back to the backup, got $result", result is SaveOpenResult.RecoveredFromBackup)
    }

    // --- invariant: a real temp DB is journal_mode=DELETE (single authoritative .db) -------------------

    @Test
    fun `a real temp-file database defaults to journal_mode DELETE`() {
        val file = newFilePath("journal.db")
        seedDatabase(file, currentVersion)

        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val mode =
            try {
                driver
                    .executeQuery(
                        identifier = null,
                        sql = "PRAGMA journal_mode",
                        mapper = { cursor ->
                            cursor.next()
                            QueryResult.Value(cursor.getString(0) ?: "")
                        },
                        parameters = 0,
                        binders = null,
                    ).value
            } finally {
                driver.close()
            }

        assertEquals(
            "the probe + single-file backup rely on a single authoritative .db (no WAL sidecars)",
            "delete",
            mode.lowercase(),
        )
    }
}
