package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.world.PoiId
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

    /**
     * Read the single `game_state` row's (`current_sector`, `docked_station_id`) directly via SQL.
     *
     * Used by the v2→v3 test for data-survival assertions **without** going through
     * [SqlDelightGameStateRepository.loadGameState], which now (UC06/v4) reads the `cargo` +
     * `field_deposit` tables a v3 DB does not yet have. Loading a fully-migrated save through the
     * repository is covered by the v3→v4 and full-chain tests below.
     */
    private fun readGameStateColumns(): Pair<String?, String?> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT current_sector, docked_station_id FROM game_state WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0) to cursor.getString(1))
            },
            parameters = 0,
            binders = null,
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

        // Data survival: the seeded game_state row survives, read directly (loadGameState() now needs
        // the v4 cargo/field_deposit tables, which a v3 DB lacks — covered by the v3->v4 test below).
        val (sector, docked) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertNull("a migrated v2 save reads back as in flight (no dock column value)", docked)
        assertTrue("a migrated v2 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 3 (AC#4).
        assertEquals(3L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC06 AC#4/#5: v3 -> v4 adds the cargo + field_deposit tables additively ---

    /**
     * Build the exact v3 (UC05) schema — `meta` + `settings` + the v3 `game_state` (WITH
     * `docked_station_id`) + `ship` — and seed a real docked save so the v3→v4 migration is
     * exercised against **data-bearing** tables, not empty ones.
     */
    private fun buildRealV3Database() {
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
        // v3 game_state: INCLUDES docked_station_id, but NO cargo/field_deposit tables yet.
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 3)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id) " +
                "VALUES (0, 'beta', 0, 'beta-station')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel) VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5)",
            0,
        )
    }

    @Test
    fun `migrating a real v3 database to v4 preserves the saved game, adds cargo and field_deposit tables, and bumps the version`() {
        buildRealV3Database()

        // Apply the sequential v3 -> v4 migration (runs migrations/3.sqm).
        OrbitalFrontier.Schema.migrate(driver, 3L, 4L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the docked game_state + ship survive the additive migration (AC#4).
        assertEquals("v3 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The two new v4 tables now exist.
        assertTrue("cargo table must exist after migration", tableExists("cargo"))
        assertTrue("field_deposit table must exist after migration", tableExists("field_deposit"))

        // The full save still loads: prior sector + dock state intact, hold empty, all fields pristine.
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        val loaded = gameStateRepo.loadGameState()
        assertNotNull("a migrated v3 save must still load", loaded)
        assertEquals("the saved sector must survive", "beta", loaded!!.currentSector.value)
        assertEquals("the dock state must survive", PoiId("beta-station"), loaded.dockedStation)
        assertTrue("a migrated v3 save has an empty hold", loaded.cargo.contents.isEmpty())
        assertEquals("the hold reloads at the default capacity", Cargo.DEFAULT_CAPACITY, loaded.cargo.capacity)
        assertTrue("a migrated v3 save has all fields pristine", loaded.fieldDepletion.isEmpty())

        // The stored save-format version is bumped to 4 (AC#4).
        assertEquals(4L, queries.selectSaveVersion().executeAsOne())
    }

    @Test
    fun `the full v1 to v4 chain preserves settings, lands every schema change, and ends at version 4`() {
        buildRealV1Database()

        // SQLDelight applies the .sqm chain in order: 1.sqm (v1->v2), 2.sqm (v2->v3), 3.sqm (v3->v4).
        OrbitalFrontier.Schema.migrate(driver, 1L, 4L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // v1 settings survive the whole chain.
        assertEquals("v1 settings must survive the v1->v4 chain", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // Every schema change landed: the v2 tables, the v3 dock column, and the v4 tables.
        assertTrue("game_state table must exist", tableExists("game_state"))
        assertTrue("ship table must exist", tableExists("ship"))
        assertTrue("docked_station_id column must exist", columnExists("game_state", "docked_station_id"))
        assertTrue("cargo table must exist", tableExists("cargo"))
        assertTrue("field_deposit table must exist", tableExists("field_deposit"))

        // A migrated-from-v1 DB has no game state (settings-only origin) → New Game.
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        assertNull("a v1-origin DB has no saved game state", gameStateRepo.loadGameState())

        // Ends at the current schema version.
        assertEquals(4L, queries.selectSaveVersion().executeAsOne())
    }
}
