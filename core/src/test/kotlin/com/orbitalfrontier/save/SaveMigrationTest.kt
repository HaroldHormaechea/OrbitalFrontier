package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.station.OwnedStation
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.world.SectorId
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

        // Data survival: the seeded docked game_state survives, read directly. loadGameState() now
        // (UC07/v5) reads the ship.fuel column, which a v4 DB lacks — exactly the reason the v2->v3
        // test reads columns directly too; a full repository load of a fully-migrated save is covered
        // by the v4->v5 and full-chain tests below.
        val (sector, docked) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertEquals("the dock state must survive", "beta-station", docked)
        assertTrue("a migrated v3 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 4 (AC#4).
        assertEquals(4L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC07 AC#6: v4 -> v5 adds the ship.fuel column additively, backfilling a full tank ---

    /**
     * Build the exact v4 (UC06) schema — `meta` + `settings` + the v3/v4 `game_state` (WITH
     * `docked_station_id`) + the v4 `ship` (NO `fuel` column yet) + `cargo` + `field_deposit` — and
     * seed a real in-flight save (settings + game_state header + ship row + a cargo row) so the v4→v5
     * migration is exercised against **data-bearing** tables, not empty ones.
     */
    private fun buildRealV4Database() {
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
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT)",
            0,
        )
        // v4 ship: NO fuel column yet (that is exactly what 4.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 4)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id) VALUES (0, 'beta', 0, NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel) VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'HYDROGEN', 7)", 0)
    }

    /** Read the single ship row's `fuel` column directly via SQL (used for the backfill assertion). */
    private fun readShipFuel(): Double? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT fuel FROM ship WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getDouble(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v4 database to v5 adds the backfilled ship_fuel column and bumps the version`() {
        buildRealV4Database()

        // Apply the sequential v4 -> v5 migration (runs migrations/4.sqm).
        OrbitalFrontier.Schema.migrate(driver, 4L, 5L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo survive the additive migration.
        assertEquals("v4 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new fuel column exists and was backfilled to a FULL tank (DEFAULT 100) for the existing ship.
        assertTrue("ship.fuel column must exist after migration", columnExists("ship", "fuel"))
        assertEquals(
            "the migration backfills the existing ship to a full tank (never stranded)",
            FuelParams.DEFAULT_TANK_CAPACITY.toDouble(),
            readShipFuel()!!,
            1e-9,
        )

        // Data survival: the seeded in-flight game_state survives, read directly. loadGameState() now
        // (UC08/v6) reads the game_state.credits column, which a v5 DB lacks — exactly the reason the
        // earlier step tests read columns directly too; a full repository load of a fully-migrated save
        // is covered by the v5->v6 and full-chain tests below.
        val (sector, docked) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertNull("a migrated v4 save reads back as in flight (seeded with no dock)", docked)
        assertTrue("a migrated v4 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 5 (AC#6).
        assertEquals(5L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC08 AC#1: v5 -> v6 adds the game_state.credits column additively, backfilling 0 ---

    /**
     * Build the exact v5 (UC07) schema — `meta` + `settings` + the v5 `game_state` (WITH
     * `docked_station_id`, but NO `credits`) + the v5 `ship` (WITH `fuel`) + `cargo` + `field_deposit` —
     * and seed a real in-flight save (settings + game_state header + ship row with fuel + a cargo row)
     * so the v5→v6 migration is exercised against **data-bearing** tables, not empty ones.
     */
    private fun buildRealV5Database() {
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
        // v5 game_state: INCLUDES docked_station_id, but NO credits column yet (that is what 5.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT)",
            0,
        )
        // v5 ship: INCLUDES the fuel column.
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, fuel REAL NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 5)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id) VALUES (0, 'beta', 0, NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
    }

    /** Read the single `game_state` row's `credits` column directly via SQL (used for the backfill assertion). */
    private fun readGameStateCredits(): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT credits FROM game_state WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v5 database to v6 adds the backfilled credits column and bumps the version`() {
        buildRealV5Database()

        // Apply the sequential v5 -> v6 migration (runs migrations/5.sqm).
        OrbitalFrontier.Schema.migrate(driver, 5L, 6L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo survive the additive migration.
        assertEquals("v5 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new credits column exists and was backfilled to 0 (a migrated save upgrades broke; a new
        // game seeds a starting balance in code, not via this default).
        assertTrue("game_state.credits column must exist after migration", columnExists("game_state", "credits"))
        assertEquals("the migration backfills the existing save to a zero balance", 0L, readGameStateCredits())

        // Data survival read directly: loadGameState() now (UC09/v7) reads the ship.ship_type column +
        // the ship_upgrade table, which a v6 DB lacks — exactly the reason the earlier step tests read
        // columns directly too. A full repository load of a fully-migrated save is covered by the
        // v6->v7 and full-chain tests below.
        val (sector, _) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertEquals("the saved fuel must survive", 42.0, readShipFuel()!!, 1e-9)
        assertTrue("a migrated v5 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 6 (AC#1).
        assertEquals(6L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC09 AC#6: v6 -> v7 adds ship.ship_type (default 'starter') + the ship_upgrade table ---

    /**
     * Build the exact v6 (UC08) schema — `meta` + `settings` + the v6 `game_state` (WITH
     * `docked_station_id` and `credits`) + the v6 `ship` (WITH `fuel`, but NO `ship_type`) + `cargo` +
     * `field_deposit` — and seed a real in-flight save with a non-zero wallet so the v6→v7 migration is
     * exercised against **data-bearing** tables, not empty ones.
     */
    private fun buildRealV6Database() {
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
        // v6 game_state: INCLUDES docked_station_id AND credits.
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        // v6 ship: INCLUDES fuel, but NO ship_type column yet (that is exactly what 6.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, fuel REAL NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 6)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits) " +
                "VALUES (0, 'beta', 0, NULL, 1234)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
    }

    /** Read the single ship row's `ship_type` column directly via SQL (used for the backfill assertion). */
    private fun readShipType(): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT ship_type FROM ship WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v6 database to v7 backfills the starter ship_type, adds ship_upgrade, and bumps the version`() {
        buildRealV6Database()

        // Apply the sequential v6 -> v7 migration (runs migrations/6.sqm).
        OrbitalFrontier.Schema.migrate(driver, 6L, 7L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo survive the additive migration.
        assertEquals("v6 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new ship_type column exists and the existing ship was backfilled to the starter type.
        assertTrue("ship.ship_type column must exist after migration", columnExists("ship", "ship_type"))
        assertEquals("the migration backfills the existing ship to the starter type", "starter", readShipType())

        // The new (empty) ship_upgrade table exists.
        assertTrue("ship_upgrade table must exist after migration", tableExists("ship_upgrade"))

        // Data survival read directly: loadGameState() now (UC10/v8) reads the revealed_contact table,
        // which a v7 DB lacks — exactly the reason the earlier step tests read columns directly too. A
        // full repository load of a fully-migrated save is covered by the v7->v8 and full-chain tests below.
        val (sector, _) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertEquals("the migration backfills the existing ship to the starter type", "starter", readShipType())
        assertEquals("the saved fuel must survive", 42.0, readShipFuel()!!, 1e-9)
        assertEquals("the saved credits must survive", 1234L, readGameStateCredits())
        assertTrue("a migrated v6 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 7 (AC#6).
        assertEquals(7L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC10 AC#4: v7 -> v8 adds the revealed_contact table additively (empty), preserving the save ---

    /**
     * Build the exact v7 (UC09) schema — `meta` + `settings` + the v7 `game_state` (WITH
     * `docked_station_id` and `credits`) + the v7 `ship` (WITH `fuel` AND `ship_type`) + `ship_upgrade`
     * + `cargo` + `field_deposit` — and seed a real in-flight save (a single starter ship with cargo +
     * fuel + a non-zero wallet) so the v7→v8 migration is exercised against **data-bearing** tables.
     * This is the v7 baseline the production .sq describes (the v6→v7 migration's output).
     */
    private fun buildRealV7Database() {
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
        // v7 game_state: INCLUDES docked_station_id AND credits.
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        // v7 ship: INCLUDES fuel AND ship_type (the v6->v7 additions).
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter')",
            0,
        )
        // v7 ship_upgrade table (empty here — the seeded ship has no installed upgrades).
        driver.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, " +
                "PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 7)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits) " +
                "VALUES (0, 'beta', 0, NULL, 1234)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0, 'starter')",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
    }

    /** Count the rows in the v8 `revealed_contact` table directly via SQL (used for the empty-after-migration assertion). */
    private fun readRevealedContactCount(): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM revealed_contact",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v7 database to v8 adds the empty revealed_contact table, preserves the save, and bumps the version`() {
        buildRealV7Database()

        // Apply the sequential v7 -> v8 migration (runs migrations/7.sqm).
        OrbitalFrontier.Schema.migrate(driver, 7L, 8L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo survive the additive migration.
        assertEquals("v7 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new revealed_contact table exists and is EMPTY — a migrated save has scanned nothing, so
        // every hidden contact reads back still hidden (UC10 AC#4; the migration is purely additive).
        assertTrue("revealed_contact table must exist after migration", tableExists("revealed_contact"))
        assertEquals("a migrated v7 save has nothing revealed", 0L, readRevealedContactCount())

        // Data survival read directly: loadGameState() now (UC11/v9) reads the ship.crew column, which a
        // v8 DB lacks — exactly the reason the earlier step tests read columns directly too. A full
        // repository load of a fully-migrated save is covered by the v8->v9 and full-chain tests below.
        val (sector, _) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertEquals("the migrated save keeps the starter ship_type", "starter", readShipType())
        assertEquals("the saved fuel must survive", 42.0, readShipFuel()!!, 1e-9)
        assertEquals("the saved credits must survive", 1234L, readGameStateCredits())
        assertTrue("a migrated v7 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 8 (AC#4).
        assertEquals(8L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC11 AC#4: v8 -> v9 adds the ship.crew column additively, backfilling 0 (uncrewed) ---

    /**
     * Build the exact v8 (UC10) schema — `meta` + `settings` + the v8 `game_state` (WITH
     * `docked_station_id` and `credits`) + the v8 `ship` (WITH `fuel` AND `ship_type`, but NO `crew`) +
     * `ship_upgrade` + `cargo` + `field_deposit` + `revealed_contact` — and seed a real in-flight save
     * (a single starter ship with cargo + fuel + a non-zero wallet + a revealed contact) so the v8→v9
     * migration is exercised against **data-bearing** tables. This is the v8 baseline the production .sq
     * describes (the v7→v8 migration's output).
     */
    private fun buildRealV8Database() {
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
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        // v8 ship: INCLUDES fuel AND ship_type, but NO crew column yet (that is exactly what 8.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter')",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, " +
                "PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        // v8 revealed_contact table (the v7->v8 addition), seeded with one revealed contact.
        driver.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 8)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits) " +
                "VALUES (0, 'beta', 0, NULL, 1234)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0, 'starter')",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
        driver.execute(null, "INSERT INTO revealed_contact(contact_id) VALUES ('alpha-derelict')", 0)
    }

    /** Read the single ship row's `crew` column directly via SQL (used for the backfill assertion). */
    private fun readShipCrew(): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT crew FROM ship WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v8 database to v9 adds the backfilled ship_crew column and bumps the version`() {
        buildRealV8Database()

        // Apply the sequential v8 -> v9 migration (runs migrations/8.sqm).
        OrbitalFrontier.Schema.migrate(driver, 8L, 9L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo + the revealed contact survive
        // the additive migration.
        assertEquals("v8 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new crew column exists and was backfilled to 0 (a migrated ship reads back uncrewed).
        assertTrue("ship.crew column must exist after migration", columnExists("ship", "crew"))
        assertEquals("the migration backfills the existing ship to 0 crew (uncrewed)", 0L, readShipCrew())

        // Data survival read directly: loadGameState() now (UC12/v10) reads the `mission` table, which a
        // v9 DB lacks — exactly the reason the earlier step tests read columns directly too. A full
        // repository load of a fully-migrated save is covered by the v9->v10 and full-chain tests below.
        val (sector, _) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertEquals("the migrated save keeps the starter ship_type", "starter", readShipType())
        assertEquals("the saved fuel must survive", 42.0, readShipFuel()!!, 1e-9)
        assertEquals("the saved credits must survive", 1234L, readGameStateCredits())
        assertTrue("a migrated v8 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 9 (AC#4).
        assertEquals(9L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC12 AC#5: v9 -> v10 adds the (empty) mission table additively, preserving the save ---

    /**
     * Build the exact v9 (UC11) schema — `meta` + `settings` + the v9 `game_state` (WITH
     * `docked_station_id` and `credits`) + the v9 `ship` (WITH `fuel`, `ship_type` AND `crew`) +
     * `ship_upgrade` + `cargo` + `field_deposit` + `revealed_contact` — and seed a real in-flight save
     * (a single starter ship with cargo + fuel + a non-zero wallet + a revealed contact + 0 crew) so the
     * v9→v10 migration is exercised against **data-bearing** tables. This is the v9 baseline the
     * production .sq describes (the v8→v9 migration's output).
     */
    private fun buildRealV9Database() {
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
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        // v9 ship: INCLUDES fuel, ship_type AND crew (the v8->v9 addition). NO mission table yet.
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter', crew INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, " +
                "PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 9)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits) " +
                "VALUES (0, 'beta', 0, NULL, 1234)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type, crew) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0, 'starter', 0)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
        driver.execute(null, "INSERT INTO revealed_contact(contact_id) VALUES ('alpha-derelict')", 0)
    }

    /** Count the rows in the v10 `mission` table directly via SQL (used for the empty-after-migration assertion). */
    private fun readMissionCount(): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM mission",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v9 database to v10 adds the empty mission table, preserves the save, and bumps the version`() {
        buildRealV9Database()

        // Apply the sequential v9 -> v10 migration (runs migrations/9.sqm).
        OrbitalFrontier.Schema.migrate(driver, 9L, 10L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo + the revealed contact survive
        // the additive migration.
        assertEquals("v9 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new mission table exists and is EMPTY — a migrated save has no missions, so the player reads
        // back with an empty mission log (UC12 AC#5; the migration is purely additive).
        assertTrue("mission table must exist after migration", tableExists("mission"))
        assertEquals("a migrated v9 save has no missions", 0L, readMissionCount())

        // Data survival read directly: loadGameState() now (UC13/v11) reads the ship_section_damage table +
        // game_state.last_docked_station_id, which a v10 DB lacks — exactly the reason the earlier step tests
        // read columns directly too. A full repository load of a fully-migrated save is covered by the
        // v10->v11 and full-chain tests below.
        val (sector, _) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertEquals("the migrated save keeps the starter ship_type", "starter", readShipType())
        assertEquals("the saved fuel must survive", 42.0, readShipFuel()!!, 1e-9)
        assertEquals("the saved credits must survive", 1234L, readGameStateCredits())
        assertTrue("a migrated v9 DB still reports a save", SqlDelightGameStateRepository(database, NoOpLogger).hasSave())

        // The stored save-format version is bumped to 10 (AC#5).
        assertEquals(10L, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC13 AC#5: v10 -> v11 adds the ship_section_damage table + game_state.last_docked_station_id ---

    /**
     * Build the exact v10 (UC12) schema — `meta` + `settings` + the v10 `game_state` (WITH
     * `docked_station_id` and `credits`, but NO `last_docked_station_id`) + the v10 `ship` (WITH `fuel`,
     * `ship_type` AND `crew`) + `ship_upgrade` + `cargo` + `field_deposit` + `revealed_contact` +
     * `mission` — and seed a real in-flight save (a single starter ship with cargo + fuel + a non-zero
     * wallet + a revealed contact + 0 crew + one accepted mission) so the v10→v11 migration is exercised
     * against **data-bearing** tables. This is the v10 baseline the production .sq describes (the v9→v10
     * migration's output); crucially it has NO `ship_section_damage` table and NO
     * `game_state.last_docked_station_id` column yet — exactly what 10.sqm adds.
     */
    private fun buildRealV10Database() {
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
        // v10 game_state: docked_station_id + credits, but NO last_docked_station_id (that is what 10.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        // v10 ship: fuel, ship_type AND crew. NO ship_section_damage table yet.
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter', crew INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, " +
                "PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        // v10 mission table (the v9->v10 addition), empty here.
        driver.execute(
            null,
            "CREATE TABLE mission (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, source TEXT NOT NULL, " +
                "status TEXT NOT NULL, reward_credits INTEGER NOT NULL, reward_resource TEXT, " +
                "reward_resource_units INTEGER NOT NULL DEFAULT 0, quota_resource TEXT, quota_units INTEGER NOT NULL DEFAULT 0, " +
                "pickup TEXT, destination TEXT, remaining_ticks INTEGER NOT NULL DEFAULT 0, picked_up INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 10)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits) " +
                "VALUES (0, 'beta', 0, NULL, 1234)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type, crew) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0, 'starter', 0)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
        driver.execute(null, "INSERT INTO revealed_contact(contact_id) VALUES ('alpha-derelict')", 0)
    }

    /** Read the single `game_state` row's `last_docked_station_id` column directly via SQL. */
    private fun readLastDockedStation(): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT last_docked_station_id FROM game_state WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v10 database to v11 adds combat state, preserves the save, and bumps the version`() {
        buildRealV10Database()

        // Apply the sequential v10 -> v11 migration (runs migrations/10.sqm).
        OrbitalFrontier.Schema.migrate(driver, 10L, 11L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // Data survival: settings + the in-flight game_state + ship + cargo + the revealed contact survive
        // the additive migration.
        assertEquals("v10 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // The new (empty) per-ship section-damage table exists — a migrated save has no rows, so every ship
        // reads back pristine (full derived HP, UC13 AC#3; the migration is purely additive).
        assertTrue("ship_section_damage table must exist after migration", tableExists("ship_section_damage"))

        // The new nullable last_docked_station_id column exists and is NULL for the migrated save (a save
        // that predates the column has no recorded last dock → respawn-in-place, AC#5).
        assertTrue(
            "game_state.last_docked_station_id column must exist after migration",
            columnExists("game_state", "last_docked_station_id"),
        )
        assertNull("a migrated v10 save has no recorded last dock", readLastDockedStation())

        // The stored save-format version is bumped to 11 (AC#5) — assert the v10->v11 step before
        // continuing the chain (the repository is now v12-aware and needs the reputation table to load).
        assertEquals(11L, queries.selectSaveVersion().executeAsOne())

        // Continue to the current schema so the v13-aware repository can load (it reads the v12 reputation
        // table + the v13 owned_station/station_module tables; the canonical per-step migration assertions
        // live in the dedicated v11->v12 and v12->v13 tests).
        OrbitalFrontier.Schema.migrate(driver, 11L, 13L)

        // The full save now loads through the v13-aware repository: the prior single starter ship with its
        // sector + cargo + fuel + credits + revealed contact intact, 0 crew, pristine sections, no last dock.
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        val loaded = gameStateRepo.loadGameState()
        assertNotNull("a migrated v10 save must still load", loaded)
        assertEquals("the saved sector must survive", "beta", loaded!!.currentSector.value)
        assertEquals("the migrated save is a single-ship fleet", 1, loaded.fleet.ships.size)
        assertEquals("the one ship is the starter type", "starter", loaded.fleet.active.type.id.value)
        assertEquals("the saved cargo must survive", 9, loaded.cargo.contents[ResourceType.IRON_ORE])
        assertEquals("the saved fuel must survive", 42.0, loaded.fuel.level.toDouble(), 1e-9)
        assertEquals("the saved credits must survive", 1234L, loaded.credits)
        assertTrue("the revealed contact must survive", loaded.revealedContacts.any { it.value == "alpha-derelict" })
        assertTrue("a migrated v10 save reads back with pristine sections", loaded.fleet.active.sectionDamage.isEmpty())
        assertNull("a migrated v10 save reads back with no last docked station", loaded.lastDockedStation)
    }

    // --- UC14 AC#2: v11 -> v12 adds the reputation table + mission.faction_id column additively ---

    /**
     * Build the exact v11 (UC13) schema — `meta` + `settings` + the v11 `game_state` (WITH
     * `last_docked_station_id`) + the v11 `ship` (WITH `fuel` + `crew`) + `ship_upgrade` + `cargo` +
     * `field_deposit` + `revealed_contact` + the v11 `mission` table (NO `faction_id` column) + the v11
     * `ship_section_damage` table — seeded with a real save (sector + ship + cargo + a recorded last dock
     * + a revealed contact + one accepted mission + one damaged section) so the v11→v12 migration is
     * exercised against **data-bearing** tables. NO `reputation` table and NO `mission.faction_id` column
     * yet — those are exactly what 11.sqm adds. This is the v11 baseline the production .sq describes.
     */
    private fun buildRealV11Database() {
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
        // v11 game_state: docked_station_id + credits + last_docked_station_id (the v10->v11 addition).
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0, last_docked_station_id TEXT)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter', crew INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, " +
                "PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        // v11 mission table — NO faction_id column yet (that is what 11.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE mission (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, source TEXT NOT NULL, " +
                "status TEXT NOT NULL, reward_credits INTEGER NOT NULL, reward_resource TEXT, " +
                "reward_resource_units INTEGER NOT NULL DEFAULT 0, quota_resource TEXT, quota_units INTEGER NOT NULL DEFAULT 0, " +
                "pickup TEXT, destination TEXT, remaining_ticks INTEGER NOT NULL DEFAULT 0, picked_up INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        // v11 ship_section_damage table (the v10->v11 addition).
        driver.execute(
            null,
            "CREATE TABLE ship_section_damage (ship_id INTEGER NOT NULL, section TEXT NOT NULL, " +
                "current_hp INTEGER NOT NULL, PRIMARY KEY (ship_id, section))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 11)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits, last_docked_station_id) " +
                "VALUES (0, 'beta', 0, NULL, 1234, 'alpha-station')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type, crew) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0, 'starter', 0)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
        driver.execute(null, "INSERT INTO revealed_contact(contact_id) VALUES ('alpha-derelict')", 0)
        // A pre-UC14 accepted mining mission (no faction attribution — the column does not exist yet).
        driver.execute(
            null,
            "INSERT INTO mission(id, type, source, status, reward_credits, quota_resource, quota_units, remaining_ticks, picked_up) " +
                "VALUES ('board:alpha-station:mining', 'MINING', 'BOARD', 'ACTIVE', 400, 'HYDROGEN', 8, 0, 0)",
            0,
        )
    }

    /** Count the rows in the v12 `reputation` table directly via SQL (used for the empty-after-migration assertion). */
    private fun readReputationCount(): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM reputation",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v11 database to v12 adds reputation and mission faction_id, preserves the save, and bumps the version`() {
        buildRealV11Database()

        // Apply the sequential v11 -> v12 migration (runs migrations/11.sqm).
        OrbitalFrontier.Schema.migrate(driver, 11L, 12L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The new (empty) reputation table exists — a migrated save holds no standings, so every faction
        // reads back neutral (Reputation.EMPTY), byte-identical to a pre-UC14 game (AC#2; purely additive).
        assertTrue("reputation table must exist after migration", tableExists("reputation"))
        assertEquals("a migrated v11 save has no reputation rows", 0L, readReputationCount())

        // The new nullable mission.faction_id column exists and is NULL for the pre-UC14 mission row (a
        // mission saved before factions had no attribution → grants no reputation on turn-in, which is correct).
        assertTrue("mission.faction_id column must exist after migration", columnExists("mission", "faction_id"))

        // Data survival: settings + the in-flight game_state + last dock + ship + cargo + the revealed
        // contact + the accepted mission all survive the additive migration.
        assertEquals("v11 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())
        assertEquals("the recorded last dock must survive", "alpha-station", readLastDockedStation())

        // The stored save-format version is bumped to 12 (AC#2) — assert the v11->v12 step before continuing.
        assertEquals(12L, queries.selectSaveVersion().executeAsOne())

        // Continue to the current schema so the v13-aware repository can load (it reads the v13
        // owned_station/station_module tables; the canonical v12->v13 assertions live in the dedicated test).
        OrbitalFrontier.Schema.migrate(driver, 12L, 13L)

        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        val loaded = gameStateRepo.loadGameState()
        assertNotNull("a migrated v11 save must still load", loaded)
        assertEquals("the saved sector must survive", "beta", loaded!!.currentSector.value)
        assertEquals("the saved cargo must survive", 9, loaded.cargo.contents[ResourceType.IRON_ORE])
        assertEquals("the saved credits must survive", 1234L, loaded.credits)
        assertEquals("the saved last docked station must survive", "alpha-station", loaded.lastDockedStation?.value)
        assertTrue("the accepted mission must survive", loaded.missions.accepted.any { it.id.value == "board:alpha-station:mining" })
        // A migrated pre-UC14 mission reads back faction-less, and the player reads back fully neutral.
        assertNull(
            "a migrated mission has no faction attribution",
            loaded.missions.accepted.single { it.id.value == "board:alpha-station:mining" }.factionId,
        )
        assertTrue("a migrated v11 save reads back fully neutral", loaded.reputation.byFaction.isEmpty())
        // The v11->v12 step's save_version (12) is asserted above, before continuing the chain to v13.

        // The new tables/columns are writable, not just present: a non-neutral standing + a faction-attributed
        // mission saved on top of the migrated DB round-trips.
        gameStateRepo.saveGameState(loaded.copy(reputation = Reputation(mapOf(Factions.LEAGUE.id to 25))))
        val reSaved = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()
        assertEquals(
            "a non-neutral standing saved into the migrated DB round-trips",
            25,
            reSaved!!.reputation.valueFor(Factions.LEAGUE.id),
        )
    }

    // --- UC15 AC#4: v12 -> v13 adds owned_station + station_module additively (no breaking change) ---

    /**
     * Build the exact v12 (UC14) schema — the v11 tables PLUS the v12 additions (`reputation` table +
     * `mission.faction_id` column) — seeded with a real save (sector + ship + cargo + last dock + a revealed
     * contact + a faction-attributed accepted mission + a non-neutral reputation row) so the v12→v13
     * migration is exercised against **data-bearing** tables. NO `owned_station` / `station_module` tables
     * yet — those are exactly what 12.sqm adds. This is the v12 baseline the production .sq describes.
     */
    private fun buildRealV12Database() {
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
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0, last_docked_station_id TEXT)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter', crew INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, " +
                "PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        // v12 mission table — WITH the faction_id column (the v11->v12 addition).
        driver.execute(
            null,
            "CREATE TABLE mission (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, source TEXT NOT NULL, " +
                "status TEXT NOT NULL, reward_credits INTEGER NOT NULL, reward_resource TEXT, " +
                "reward_resource_units INTEGER NOT NULL DEFAULT 0, quota_resource TEXT, quota_units INTEGER NOT NULL DEFAULT 0, " +
                "pickup TEXT, destination TEXT, remaining_ticks INTEGER NOT NULL DEFAULT 0, picked_up INTEGER NOT NULL DEFAULT 0, " +
                "faction_id TEXT)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE ship_section_damage (ship_id INTEGER NOT NULL, section TEXT NOT NULL, " +
                "current_hp INTEGER NOT NULL, PRIMARY KEY (ship_id, section))",
            0,
        )
        // v12 reputation table (the v11->v12 addition).
        driver.execute(
            null,
            "CREATE TABLE reputation (faction_id TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 12)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        driver.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits, last_docked_station_id) " +
                "VALUES (0, 'beta', 0, NULL, 1234, 'alpha-station')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type, crew) " +
                "VALUES (0, 12.5, -7.5, 1.0, 2.0, 0.25, -0.5, 42.0, 'starter', 0)",
            0,
        )
        driver.execute(null, "INSERT INTO cargo(ship_id, resource, units) VALUES (0, 'IRON_ORE', 9)", 0)
        driver.execute(null, "INSERT INTO revealed_contact(contact_id) VALUES ('alpha-derelict')", 0)
        // A v12 faction-attributed accepted mission + a non-neutral reputation row, to prove data survival.
        driver.execute(
            null,
            "INSERT INTO mission(id, type, source, status, reward_credits, quota_resource, quota_units, " +
                "remaining_ticks, picked_up, faction_id) " +
                "VALUES ('board:alpha-station:mining', 'MINING', 'BOARD', 'ACTIVE', 400, 'HYDROGEN', 8, 0, 0, 'league')",
            0,
        )
        driver.execute(null, "INSERT INTO reputation(faction_id, value) VALUES ('league', 25)", 0)
    }

    /** Count the rows in the v13 `owned_station` table directly via SQL (the empty-after-migration assertion). */
    private fun readOwnedStationCount(): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM owned_station",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v12 database to v13 adds the station tables, preserves the save, and bumps the version`() {
        buildRealV12Database()

        // Apply the sequential v12 -> v13 migration (runs migrations/12.sqm).
        OrbitalFrontier.Schema.migrate(driver, 12L, 13L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The two new (empty) station tables exist — a migrated save owns no stations, so it reads back with
        // zero owned stations, byte-identical to a pre-UC15 game (AC#4; purely additive).
        assertTrue("owned_station table must exist after migration", tableExists("owned_station"))
        assertTrue("station_module table must exist after migration", tableExists("station_module"))
        assertEquals("a migrated v12 save has no owned-station rows", 0L, readOwnedStationCount())

        // Data survival: settings + the in-flight game_state + last dock + ship + cargo + the revealed contact
        // + the faction-attributed mission + the reputation row all survive the additive migration.
        assertEquals("v12 settings must survive", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())
        assertEquals("the recorded last dock must survive", "alpha-station", readLastDockedStation())

        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        val loaded = gameStateRepo.loadGameState()
        assertNotNull("a migrated v12 save must still load", loaded)
        assertEquals("the saved sector must survive", "beta", loaded!!.currentSector.value)
        assertEquals("the saved cargo must survive", 9, loaded.cargo.contents[ResourceType.IRON_ORE])
        assertEquals("the saved credits must survive", 1234L, loaded.credits)
        assertEquals("the saved last docked station must survive", "alpha-station", loaded.lastDockedStation?.value)
        assertTrue("the faction mission must survive", loaded.missions.accepted.any { it.id.value == "board:alpha-station:mining" })
        assertEquals("the league reputation must survive", 25, loaded.reputation.valueFor(Factions.LEAGUE.id))
        // AC#4 backward compat: a pre-UC15 (v12) save migrates and reads back owning ZERO stations.
        assertTrue("a migrated v12 save reads back with no owned stations", loaded.stations.isEmpty)
        assertEquals("zero owned stations after migration (AC#4)", 0, loaded.stations.size)

        // The stored save-format version is bumped to 13 (AC#4).
        assertEquals(13L, queries.selectSaveVersion().executeAsOne())

        // The new tables are writable, not just present: a founded station saved on top of the migrated DB
        // round-trips (id + anchor sector + module slot), proving the additive migration is fully functional.
        val founded =
            OwnedStation(
                id = StationId(0),
                sector = SectorId("beta"),
                modules = mapOf(0 to StationModuleCatalog.COMMERCE_HUB),
            )
        gameStateRepo.saveGameState(loaded.copy(stations = StationRegistry(listOf(founded))))
        val reSaved = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()
        assertEquals("a station saved into the migrated DB round-trips", 1, reSaved!!.stations.size)
        assertEquals(
            "the round-tripped station keeps its commerce hub",
            StationModuleCatalog.COMMERCE_HUB,
            reSaved.stations.station(StationId(0))!!.moduleAt(0),
        )
    }

    @Test
    fun `the full v1 to v13 chain preserves settings, lands every schema change, and ends at version 13`() {
        buildRealV1Database()

        // SQLDelight applies the .sqm chain in order: 1.sqm (v1->v2) … 11.sqm (v11->v12), 12.sqm (v12->v13).
        OrbitalFrontier.Schema.migrate(driver, 1L, 13L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // v1 settings survive the whole chain.
        assertEquals("v1 settings must survive the v1->v13 chain", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())

        // Every schema change landed: the v2 tables, the v3 dock column, the v4 tables, the v5 fuel
        // column, the v6 credits column, the v7 ship_type column + ship_upgrade table, the v8
        // revealed_contact table, the v9 ship.crew column, the v10 mission table, the v11
        // ship_section_damage table + game_state.last_docked_station_id column, and the v12 reputation
        // table + mission.faction_id column.
        assertTrue("game_state table must exist", tableExists("game_state"))
        assertTrue("ship table must exist", tableExists("ship"))
        assertTrue("docked_station_id column must exist", columnExists("game_state", "docked_station_id"))
        assertTrue("cargo table must exist", tableExists("cargo"))
        assertTrue("field_deposit table must exist", tableExists("field_deposit"))
        assertTrue("ship.fuel column must exist", columnExists("ship", "fuel"))
        assertTrue("game_state.credits column must exist", columnExists("game_state", "credits"))
        assertTrue("ship.ship_type column must exist", columnExists("ship", "ship_type"))
        assertTrue("ship_upgrade table must exist", tableExists("ship_upgrade"))
        assertTrue("revealed_contact table must exist", tableExists("revealed_contact"))
        assertTrue("ship.crew column must exist", columnExists("ship", "crew"))
        assertTrue("mission table must exist", tableExists("mission"))
        assertTrue("ship_section_damage table must exist", tableExists("ship_section_damage"))
        assertTrue("game_state.last_docked_station_id column must exist", columnExists("game_state", "last_docked_station_id"))
        assertTrue("reputation table must exist", tableExists("reputation"))
        assertTrue("mission.faction_id column must exist", columnExists("mission", "faction_id"))
        // …and the v13 owned_station + station_module tables (UC15).
        assertTrue("owned_station table must exist", tableExists("owned_station"))
        assertTrue("station_module table must exist", tableExists("station_module"))

        // A migrated-from-v1 DB has no game state (settings-only origin) → New Game.
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger)
        assertNull("a v1-origin DB has no saved game state", gameStateRepo.loadGameState())

        // Ends at the current schema version.
        assertEquals(13L, queries.selectSaveVersion().executeAsOne())
    }
}
