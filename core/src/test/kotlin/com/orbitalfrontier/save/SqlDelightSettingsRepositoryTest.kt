package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.settings.Handedness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence round-trip + error-path tests for [SqlDelightSettingsRepository], exercised
 * against an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code that runs on the
 * Android driver on device). Covers AC#8 (persist/reload handedness) and AC#13 (save_version,
 * transactional writes, graceful first-run / corrupt-row handling).
 *
 * "App restart" is simulated by constructing a fresh repository over the *same* live driver
 * (the in-memory DB persists for the lifetime of the connection), so the reload genuinely goes
 * back through SQL rather than reading an in-process field.
 */
class SqlDelightSettingsRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier
    private lateinit var logger: CapturingLogger

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
        logger = CapturingLogger()
    }

    @After
    fun tearDown() {
        // Some tests close the driver themselves (graceful-degradation case); ignore a double close.
        runCatching { driver.close() }
    }

    private fun newRepository() = SqlDelightSettingsRepository(database, logger)

    // --- AC#13: save metadata / save_version ---

    @Test
    fun `ensureInitialized seeds the save_version row at the current version`() {
        newRepository().ensureInitialized()

        val version = database.orbitalFrontierQueries.selectSaveVersion().executeAsOne()
        // UC12 bumped the schema to v10 (the mission table); ensureInitialized seeds
        // SaveVersion.CURRENT (10L), which is pinned to OrbitalFrontier.Schema.version.
        assertEquals(OrbitalFrontier.Schema.version, version)
        assertEquals(10L, version)
    }

    @Test
    fun `ensureInitialized is idempotent across repeated launches`() {
        val repo = newRepository()
        repo.ensureInitialized()
        repo.ensureInitialized()

        val version = database.orbitalFrontierQueries.selectSaveVersion().executeAsOne()
        assertEquals("repeated init keeps a single version-10 row", 10L, version)
    }

    // --- AC#13: first-run / missing settings row handled gracefully ---

    @Test
    fun `first run with no settings row returns the default and seeds it`() {
        val repo = newRepository()

        val loaded = repo.loadHandedness()

        assertEquals(Handedness.DEFAULT, loaded)
        val persisted = database.orbitalFrontierQueries.selectSettings().executeAsOneOrNull()
        assertEquals("missing row is seeded with the default", Handedness.DEFAULT.name, persisted)
    }

    // --- AC#8: persist + reload across a simulated restart ---

    @Test
    fun `saved handedness survives a reload`() {
        newRepository().saveHandedness(Handedness.LEFT_HANDED)

        // Fresh repository over the same DB == app restart.
        val reloaded = newRepository().loadHandedness()

        assertEquals(Handedness.LEFT_HANDED, reloaded)
    }

    @Test
    fun `the latest saved handedness wins`() {
        val repo = newRepository()
        repo.saveHandedness(Handedness.LEFT_HANDED)
        repo.saveHandedness(Handedness.RIGHT_HANDED)

        assertEquals(Handedness.RIGHT_HANDED, newRepository().loadHandedness())
    }

    // --- Error path: corrupt / unknown stored value ---

    @Test
    fun `an unrecognized stored handedness falls back to the default and warns`() {
        database.orbitalFrontierQueries.upsertHandedness("NOT_A_REAL_VALUE")

        val loaded = newRepository().loadHandedness()

        assertEquals(Handedness.DEFAULT, loaded)
        assertTrue("corruption should be logged at WARN", logger.warnings.isNotEmpty())
    }

    // --- Graceful degradation: a write failure must not crash the app ---

    @Test
    fun `saveHandedness does not throw when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail inside the transaction

        // Autosave-style: the failure is caught and logged, never propagated.
        repo.saveHandedness(Handedness.LEFT_HANDED)

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    /** Logger that records WARN/ERROR messages so error-path tests can assert on them. */
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun debug(
            tag: String,
            message: String,
        ) = Unit

        override fun info(
            tag: String,
            message: String,
        ) = Unit

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            warnings += message
        }

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            errors += message
        }
    }
}
