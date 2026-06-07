package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.NoOpLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // --- UC05 AC#4: v2 -> v3 adds the dock column additively, preserving existing game data ---

    /**
     * Build the exact v2 (UC04) schema — `meta` + `settings` + the v2 `game_state` (no
     * `docked_station_id`) + `ship` — and seed a real save (settings + a game_state header + a ship
     * row) so the v2→v3 migration is exercised against **data-bearing** tables, not empty ones.
     */
    private fun buildRealV2Database() {
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
        // v2 game_state: NO docked_station_id column yet (that is exactly what 2.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 2)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(null, "INSERT INTO game_state(id, current_sector, active_ship_id) VALUES (0, 'beta', 0)", 0)
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel) VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5)",
            0,
        )
    }

    /** Whether [table] has a column named [column] (via PRAGMA table_info). */
    private fun columnExists(
        table: String,
        column: String,
    ): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            parameters = 2,
            binders = {
                bindString(0, table)
                bindString(1, column)
            },
        ).value

    @Test
    fun `migrating a real v2 database to v3 preserves the saved game, adds the dock column, and bumps the version`() {
        buildRealV2Database()

        // Apply the sequential v2 -> v3 migration (runs migrations/2.sqm).
        OrbitalFrontier.Schema.migrate(driver, 2L, 3L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: the seeded settings + game_state survive the additive migration (AC#4).
        assertEquals("v2 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new dock column exists and is NULL for the pre-existing (in-flight) save.
        assertTrue("docked_station_id column must exist after migration", columnExists("game_state", "docked_station_id"))

        // The full save still loads, with the migrated row read back as in flight (docked_station_id = NULL).
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        val loaded = gameStateRepo.loadGameState()
        assertNotNull("a migrated v2 save must still load", loaded)
        assertEquals("the saved sector must survive", "beta", loaded!!.currentSector.value)
        assertNull("a migrated v2 save reads back as in flight (no dock column value)", loaded.dockedStation)
        assertTrue("a migrated v2 DB still reports a save", gameStateRepo.hasSave())

        // The stored save-format version is bumped to 3 (AC#4).
        assertEquals(3L, queries.selectSaveVersion().executeAsOne())
    }

    @Test
    fun `the full v1 to v3 chain preserves settings, lands every schema change, and ends at version 3`() {
        buildRealV1Database()

        // SQLDelight applies the .sqm chain in order: 1.sqm (v1->v2) then 2.sqm (v2->v3).
        OrbitalFrontier.Schema.migrate(driver, 1L, 3L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // v1 settings survive the whole chain.
        assertEquals("v1 settings must survive the v1->v3 chain", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // Both schema changes landed: the v2 tables and the v3 dock column.
        assertTrue("game_state table must exist", tableExists("game_state"))
        assertTrue("ship table must exist", tableExists("ship"))
        assertTrue("docked_station_id column must exist", columnExists("game_state", "docked_station_id"))

        // A migrated-from-v1 DB has no game state (settings-only origin) → New Game.
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        assertNull("a v1-origin DB has no saved game state", gameStateRepo.loadGameState())

        // Ends at the current schema version.
        assertEquals(3L, queries.selectSaveVersion().executeAsOne())
    }
}
