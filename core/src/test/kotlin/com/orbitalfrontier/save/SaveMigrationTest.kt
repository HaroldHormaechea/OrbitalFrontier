package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.NoOpLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sequential-migration test for the v1 (settings-only, UC01) → v2 (full game state, UC04) schema
 * upgrade (UC04 AC#4/#9).
 *
 * Rather than migrate a vacuous empty DB, this hand-builds a **real v1 database** (the exact v1
 * `meta` + `settings` schema) and seeds a settings row, then runs the generated
 * [OrbitalFrontier.Schema.migrate] over it and asserts:
 *  - the seeded v1 data **survives** (handedness row is still there — this is the load-bearing
 *    "data survival, not a no-op" assertion);
 *  - the new v2 tables (`game_state`, `ship`) now exist;
 *  - a fresh [GameStateRepository] reports no save (a migrated v1 DB has settings but no game
 *    state, so Continue is unavailable → New Game, AC#5);
 *  - `meta.save_version` is bumped to 2.
 *
 * The full `verifyMigrations` chain check (the committed `databases/<n>.db` baselines vs the `.sqm`
 * files) is a Gradle/SQLDelight task run separately; this test complements it with a real
 * data-bearing round-trip.
 */
class SaveMigrationTest {
    private lateinit var driver: JdbcSqliteDriver

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
    }

    /** Build the exact v1 (UC01) schema — `meta` + `settings` only — and seed a settings row. */
    private fun buildRealV1Database() {
        driver.execute(
            null,
            "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), handedness TEXT NOT NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 1)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
    }

    private fun tableExists(name: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            parameters = 1,
            binders = { bindString(0, name) },
        ).value

    @Test
    fun `migrating a real v1 database to v2 preserves settings, adds game-state tables, and bumps the version`() {
        buildRealV1Database()

        // Apply the sequential v1 -> v2 migration (runs migrations/1.sqm).
        OrbitalFrontier.Schema.migrate(driver, 1L, 2L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: the v1 handedness row is untouched by the additive migration (AC#4).
        assertEquals(
            "the v1 settings row must survive the migration",
            "LEFT_HANDED",
            queries.selectSettings().executeAsOneOrNull(),
        )

        // The v2 tables now exist.
        assertTrue("game_state table must exist after migration", tableExists("game_state"))
        assertTrue("ship table must exist after migration", tableExists("ship"))

        // A migrated v1 DB has no game state yet → New Game (AC#5).
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        assertNull("a migrated v1 DB has no saved game state", gameStateRepo.loadGameState())
        assertFalse("a migrated v1 DB reports no save", gameStateRepo.hasSave())

        // The stored save-format version is bumped to 2 (AC#4).
        assertEquals(2L, queries.selectSaveVersion().executeAsOne())
    }
}
