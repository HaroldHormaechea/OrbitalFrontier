package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.render.TextScale
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning
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

    /**
     * Whether the `game_state` table holds any row, read by **direct SQL** (used by the per-step migration
     * tests to assert a seeded save survives a single step). Deliberately not the repository's `hasSave`:
     * since UC38 the repository's generated queries reference the v17 `slot_id` column, which an
     * intermediate (pre-v17) schema does not have, so `hasSave` on an intermediate schema would degrade to
     * `false`. A direct row check is schema-version-agnostic and keeps each per-step test scoped to its step.
     */
    private fun gameStateRowExists(): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM game_state",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            parameters = 0,
            binders = null,
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
            readHandedness(),
        )

        // The v2 tables now exist.
        assertTrue("game_state table must exist after migration", tableExists("game_state"))
        assertTrue("ship table must exist after migration", tableExists("ship"))

        // A migrated v1 DB has no game state yet → New Game (AC#5), read by direct SQL (the repository's
        // load/hasSave now reference the v17 slot_id column, absent on a v2 schema).
        assertFalse("a migrated v1 DB reports no game-state row", gameStateRowExists())

        // The stored save-format version is bumped to 2 (AC#4) — assert the v1->v2 step before continuing.
        assertEquals(2L, queries.selectSaveVersion().executeAsOne())

        // Continue the chain to the current (slot-aware, v17) schema so the repository can be exercised; a
        // v1-origin DB is settings-only, so it still reads back as no save through the slot API (UC38 AC#3).
        OrbitalFrontier.Schema.migrate(driver, 2L, 17L)
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
        assertNull("a migrated v1 DB has no saved game state", gameStateRepo.loadGameState(SlotId.LEGACY))
        assertFalse("a migrated v1 DB reports no save", gameStateRepo.hasSave(SlotId.LEGACY))
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
        assertEquals("v2 settings must survive", "LEFT_HANDED", readHandedness())

        // The new dock column exists and is NULL for the pre-existing (in-flight) save.
        assertTrue("docked_station_id column must exist after migration", columnExists("game_state", "docked_station_id"))

        // Data survival: the seeded game_state row survives, read directly (loadGameState() now needs
        // the v4 cargo/field_deposit tables, which a v3 DB lacks — covered by the v3->v4 test below).
        val (sector, docked) = readGameStateColumns()
        assertEquals("the saved sector must survive", "beta", sector)
        assertNull("a migrated v2 save reads back as in flight (no dock column value)", docked)
        assertTrue("a migrated v2 DB still reports a save", gameStateRowExists())

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
        assertEquals("v3 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v3 DB still reports a save", gameStateRowExists())

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
        assertEquals("v4 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v4 DB still reports a save", gameStateRowExists())

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
        assertEquals("v5 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v5 DB still reports a save", gameStateRowExists())

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
        assertEquals("v6 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v6 DB still reports a save", gameStateRowExists())

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
        assertEquals("v7 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v7 DB still reports a save", gameStateRowExists())

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
        assertEquals("v8 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v8 DB still reports a save", gameStateRowExists())

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
        assertEquals("v9 settings must survive", "LEFT_HANDED", readHandedness())

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
        assertTrue("a migrated v9 DB still reports a save", gameStateRowExists())

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

    /**
     * Read the single `settings` row's `handedness` column directly via raw SQL.
     *
     * UC31 widened the generated `selectSettings` query to also select the new audio columns
     * (`master_muted`/`sfx_volume`/`music_volume`), so running it against a **pre-v14** settings table —
     * which the per-step migration tests build — throws "no such column". A raw single-column read stays
     * valid at every schema version, so it is the right tool for the "the vN handedness row survived"
     * assertions that stop at an intermediate version.
     */
    private fun readHandedness(): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT handedness FROM settings WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
            binders = null,
        ).value

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
        assertEquals("v10 settings must survive", "LEFT_HANDED", readHandedness())

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

        // Continue to the current schema so the now-current repository can load (it reads the v12 reputation
        // table + the v13 owned_station/station_module tables + the v19 bounty mission columns; the canonical
        // per-step migration assertions live in the dedicated per-version tests).
        OrbitalFrontier.Schema.migrate(driver, 11L, OrbitalFrontier.Schema.version)

        // The full save now loads through the v13-aware repository: the prior single starter ship with its
        // sector + cargo + fuel + credits + revealed contact intact, 0 crew, pristine sections, no last dock.
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
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
        assertEquals("v11 settings must survive", "LEFT_HANDED", readHandedness())
        assertEquals("the recorded last dock must survive", "alpha-station", readLastDockedStation())

        // The stored save-format version is bumped to 12 (AC#2) — assert the v11->v12 step before continuing.
        assertEquals(12L, queries.selectSaveVersion().executeAsOne())

        // Continue to the current schema so the now-current repository can load (it reads the v13
        // owned_station/station_module tables + the v19 bounty mission columns; the canonical v12->v13
        // assertions live in the dedicated test).
        OrbitalFrontier.Schema.migrate(driver, 12L, OrbitalFrontier.Schema.version)

        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
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
        val reSaved = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock).loadGameState()
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
        assertEquals("v12 settings must survive", "LEFT_HANDED", readHandedness())
        assertEquals("the recorded last dock must survive", "alpha-station", readLastDockedStation())

        // The stored save-format version is bumped to 13 (AC#4) — assert the v12->v13 step before continuing.
        assertEquals(13L, queries.selectSaveVersion().executeAsOne())

        // Continue to the current schema so the repository can load the migrated save (its queries reference
        // the v17 slot_id column + the v19 bounty mission columns; the per-step v13..v19 assertions live in
        // their own tests).
        OrbitalFrontier.Schema.migrate(driver, 13L, OrbitalFrontier.Schema.version)

        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
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

        // The new tables are writable, not just present: a founded station saved on top of the migrated DB
        // round-trips (id + anchor sector + module slot), proving the additive migration is fully functional.
        val founded =
            OwnedStation(
                id = StationId(0),
                sector = SectorId("beta"),
                modules = mapOf(0 to StationModuleCatalog.COMMERCE_HUB),
            )
        gameStateRepo.saveGameState(loaded.copy(stations = StationRegistry(listOf(founded))))
        val reSaved = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock).loadGameState()
        assertEquals("a station saved into the migrated DB round-trips", 1, reSaved!!.stations.size)
        assertEquals(
            "the round-tripped station keeps its commerce hub",
            StationModuleCatalog.COMMERCE_HUB,
            reSaved.stations.station(StationId(0))!!.moduleAt(0),
        )
    }

    @Test
    fun `the full v1 to v17 chain preserves settings, lands every schema change, and ends at version 17`() {
        buildRealV1Database()

        // SQLDelight applies the .sqm chain in order: 1.sqm (v1->v2) … 15.sqm (v15->v16), 16.sqm (v16->v17).
        OrbitalFrontier.Schema.migrate(driver, 1L, 17L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // v1 settings survive the whole chain.
        assertEquals("v1 settings must survive the v1->v16 chain", "LEFT_HANDED", readHandedness())

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
        // …the v13 owned_station + station_module tables (UC15)…
        assertTrue("owned_station table must exist", tableExists("owned_station"))
        assertTrue("station_module table must exist", tableExists("station_module"))
        // …the v14 audio-preference columns on settings (UC31)…
        assertTrue("settings.master_muted column must exist", columnExists("settings", "master_muted"))
        assertTrue("settings.sfx_volume column must exist", columnExists("settings", "sfx_volume"))
        assertTrue("settings.music_volume column must exist", columnExists("settings", "music_volume"))
        // …the v15 first-run-tutorial flag on settings (UC36)…
        assertTrue("settings.tutorial_completed column must exist", columnExists("settings", "tutorial_completed"))
        // …and the v16 joystick-tuning + UI-scale columns on settings (UC37).
        assertTrue("settings.joystick_sensitivity column must exist", columnExists("settings", "joystick_sensitivity"))
        assertTrue("settings.joystick_deadzone column must exist", columnExists("settings", "joystick_deadzone"))
        assertTrue("settings.ui_scale column must exist", columnExists("settings", "ui_scale"))
        // …and the v17 save-slot additions (UC38): the active-slot pointer on meta + the per-slot game_state
        // display metadata columns (name / last-saved / play-time).
        assertTrue("meta.active_slot_id column must exist", columnExists("meta", "active_slot_id"))
        assertTrue("game_state.name column must exist", columnExists("game_state", "name"))
        assertTrue("game_state.last_saved_epoch_millis column must exist", columnExists("game_state", "last_saved_epoch_millis"))
        assertTrue("game_state.play_time_seconds column must exist", columnExists("game_state", "play_time_seconds"))

        // A migrated-from-v1 DB has no game state (settings-only origin) → New Game (read via the slot API).
        val gameStateRepo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
        assertNull("a v1-origin DB has no saved game state", gameStateRepo.loadGameState(SlotId.LEGACY))
        // Every slot reads back empty for a settings-only origin (UC38 AC#1).
        assertTrue("a v1-origin DB lists only empty slots", gameStateRepo.listSlots().all { it is SaveSlotSummary.Empty })

        val settingsRepo = SqlDelightSettingsRepository(database, NoOpLogger)
        // The migrated settings row backfills the audio defaults (audio enabled at default levels).
        assertEquals(
            "a v1-origin migrated save reads back the default audio settings",
            AudioSettings.DEFAULT,
            settingsRepo.loadAudioSettings(),
        )
        // The v15 tutorial flag backfills its DEFAULT 0, so a migrated player is shown the onboarding once
        // (the flag's job is only to stop it RE-triggering every launch, UC36 AC#3).
        assertFalse(
            "a v1-origin migrated save reads back with the tutorial not yet completed",
            settingsRepo.loadTutorialCompleted(),
        )
        // The v16 joystick-tuning + UI-scale columns backfill their DEFAULTs (neutral stick at the model
        // floor, ×2 UI) — byte-for-byte the behaviour the player already had before UC37 (AC#2).
        assertEquals(
            "a v1-origin migrated save reads back the default joystick tuning",
            JoystickTuning.DEFAULT,
            settingsRepo.loadJoystickTuning(),
        )
        assertEquals(
            "a v1-origin migrated save reads back the default UI scale",
            UiScale.DEFAULT_FACTOR,
            settingsRepo.loadUiScale(),
            0f,
        )

        // Ends at the current schema version.
        assertEquals(17L, queries.selectSaveVersion().executeAsOne())
    }

    /**
     * Build a minimal real v13 (UC15) database — just `meta` + the v13 `settings` table (handedness
     * only, before the UC31 audio columns) — and seed a settings row. The v13->v14 migration (13.sqm)
     * touches only `settings` and `meta`, so the rest of the v13 schema is irrelevant to this migration
     * and is intentionally omitted (mirrors the minimal v1 builder).
     */
    private fun buildRealV13Database() {
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
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 13)", 0)
        driver.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'RIGHT_HANDED')", 0)
    }

    @Test
    fun `migrating a real v13 database to v14 adds the audio columns, preserves handedness, backfills defaults, and bumps the version`() {
        buildRealV13Database()

        // Apply the sequential v13 -> v14 migration (runs migrations/13.sqm).
        OrbitalFrontier.Schema.migrate(driver, 13L, 14L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The three new audio columns now exist on the single-row settings table (purely additive).
        assertTrue("settings.master_muted column must exist after migration", columnExists("settings", "master_muted"))
        assertTrue("settings.sfx_volume column must exist after migration", columnExists("settings", "sfx_volume"))
        assertTrue("settings.music_volume column must exist after migration", columnExists("settings", "music_volume"))

        // Data survival: the pre-UC31 handedness value is untouched by the additive migration.
        assertEquals(
            "the v13 handedness must survive the v13->v14 migration",
            "RIGHT_HANDED",
            readHandedness(),
        )

        // The stored save-format version is bumped to 14 — assert the v13->v14 step before continuing.
        assertEquals(14L, queries.selectSaveVersion().executeAsOne())

        // Continue the chain to the v16 schema so the repository can read selectSettings (UC36 widened it to
        // read settings.tutorial_completed and UC37 widened it again for the joystick/UI-scale columns, which
        // a v14-only DB lacks — the same pattern the v11->v12 test uses to reach a repository-loadable
        // schema). The v14->v15 and v15->v16 steps are purely additive, so the audio backfill below is
        // unchanged. UC39 then widened selectSettings once more; the v16->v17 step repartitions game-state
        // tables this settings-only fixture doesn't build, so the additive UC39 settings columns are applied
        // directly (see [addUc39SettingsColumns]) to reach the repository-readable v18 settings shape.
        OrbitalFrontier.Schema.migrate(driver, 14L, 16L)
        addUc39SettingsColumns()

        // Backfill: a pre-UC31 save had no audio prefs, so the migrated row reads back at the DEFAULTs
        // (unmuted, SFX 1.0, music 0.5) — audio enabled at default levels, no data loss (UC31 AC#3).
        val repo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals(
            "a migrated v13 save backfills the default audio settings",
            AudioSettings.DEFAULT,
            repo.loadAudioSettings(),
        )

        // The new columns are writable, not just present: audio prefs saved on top of the migrated DB
        // round-trip, and (Risk 1) the targeted audio write leaves the migrated handedness untouched.
        val saved = AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f)
        repo.saveAudioSettings(saved)
        val freshRepo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals("audio prefs round-trip on the migrated DB", saved, freshRepo.loadAudioSettings())
        assertEquals(
            "the audio write must not clobber the migrated handedness",
            Handedness.RIGHT_HANDED,
            freshRepo.loadHandedness(),
        )
    }

    // --- UC36 AC#3: v14 -> v15 adds the settings.tutorial_completed flag additively, backfilling 0 ---

    /**
     * Build a minimal real v14 (UC31) database — just `meta` + the v14 `settings` table (handedness PLUS
     * the three audio columns, before the UC36 tutorial flag) — and seed a settings row. The v14->v15
     * migration (14.sqm) touches only `settings` and `meta`, so the rest of the v14 schema is irrelevant
     * to this migration and is intentionally omitted (mirrors the minimal v1 / v13 builders).
     */
    private fun buildRealV14Database() {
        driver.execute(
            null,
            "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)",
            0,
        )
        // v14 settings: handedness + the three UC31 audio columns, but NO tutorial_completed yet (that is
        // exactly what 14.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), handedness TEXT NOT NULL, " +
                "master_muted INTEGER NOT NULL DEFAULT 0, sfx_volume REAL NOT NULL DEFAULT 1.0, " +
                "music_volume REAL NOT NULL DEFAULT 0.5)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 14)", 0)
        // Seed a non-default settings row so the migration's data-survival is meaningful (a muted save with
        // a right-handed layout and non-default volumes).
        driver.execute(
            null,
            "INSERT INTO settings(id, handedness, master_muted, sfx_volume, music_volume) " +
                "VALUES (0, 'RIGHT_HANDED', 1, 0.25, 0.75)",
            0,
        )
    }

    /** Read the single settings row's `tutorial_completed` column directly via SQL (the backfill assertion). */
    private fun readTutorialCompleted(): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT tutorial_completed FROM settings WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v14 database to v15 adds the tutorial flag, preserves settings, backfills 0, and bumps the version`() {
        buildRealV14Database()

        // Apply the sequential v14 -> v15 migration (runs migrations/14.sqm).
        OrbitalFrontier.Schema.migrate(driver, 14L, 15L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The new tutorial_completed column now exists on the single-row settings table (purely additive).
        assertTrue("settings.tutorial_completed column must exist after migration", columnExists("settings", "tutorial_completed"))

        // Backfill: a pre-UC36 save had no flag, so the migrated row reads back at DEFAULT 0 — the player is
        // shown the onboarding once after upgrading (AC#3: the flag only stops it RE-triggering each launch).
        assertEquals("the migration backfills the existing settings row to 0 (tutorial not yet shown)", 0L, readTutorialCompleted())

        // Data survival (via direct SQL): the pre-UC36 handedness is untouched by the additive migration.
        assertEquals("the v14 handedness must survive the v14->v15 migration", "RIGHT_HANDED", readHandedness())

        // The stored save-format version is bumped to 15 — assert the v14->v15 step before continuing.
        assertEquals(15L, queries.selectSaveVersion().executeAsOne())

        // Continue the chain to the v16 schema so the repository can read selectSettings (UC37 widened it for
        // joystick_sensitivity/joystick_deadzone/ui_scale, which a v15-only DB lacks — the same pattern the
        // v13->v14 test uses to reach a repository-loadable schema). The v15->v16 step is purely additive, so
        // the survival assertions below are unchanged. UC39 then widened selectSettings once more; the
        // v16->v17 step repartitions game-state tables this settings-only fixture doesn't build, so the
        // additive UC39 settings columns are applied directly (see [addUc39SettingsColumns]).
        OrbitalFrontier.Schema.migrate(driver, 15L, 16L)
        addUc39SettingsColumns()

        // Data survival (via the repository): the pre-UC36 audio columns survive the additive migrations.
        val repo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals(
            "the v14 audio settings must survive the v14->v15->v16 migrations",
            AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f),
            repo.loadAudioSettings(),
        )
        assertFalse("a migrated v14 save reads back with the tutorial not yet completed", repo.loadTutorialCompleted())

        // The new column is writable, not just present: a tutorial-completion write on top of the migrated
        // DB round-trips, and (per-field discipline) leaves the migrated handedness + audio columns untouched.
        repo.saveTutorialCompleted(true)
        val freshRepo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertTrue("tutorial flag round-trips on the migrated DB", freshRepo.loadTutorialCompleted())
        assertEquals(
            "the tutorial-flag write must not clobber the migrated handedness",
            Handedness.RIGHT_HANDED,
            freshRepo.loadHandedness(),
        )
        assertEquals(
            "the tutorial-flag write must not clobber the migrated audio settings",
            AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f),
            freshRepo.loadAudioSettings(),
        )
    }

    // --- UC37 AC#2: v15 -> v16 adds the joystick-tuning + UI-scale columns additively, backfilling defaults ---

    /**
     * Build a minimal real v15 (UC36) database — just `meta` + the v15 `settings` table (handedness +
     * the three UC31 audio columns + the UC36 tutorial flag, before the UC37 joystick/UI-scale columns) —
     * and seed a settings row. The v15->v16 migration (15.sqm) touches only `settings` and `meta`, so the
     * rest of the v15 schema is irrelevant to this migration and is intentionally omitted (mirrors the
     * minimal v1 / v13 / v14 builders).
     */
    private fun buildRealV15Database() {
        driver.execute(
            null,
            "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)",
            0,
        )
        // v15 settings: handedness + the three UC31 audio columns + the UC36 tutorial flag, but NONE of the
        // UC37 joystick/UI-scale columns (that is exactly what 15.sqm adds).
        driver.execute(
            null,
            "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), handedness TEXT NOT NULL, " +
                "master_muted INTEGER NOT NULL DEFAULT 0, sfx_volume REAL NOT NULL DEFAULT 1.0, " +
                "music_volume REAL NOT NULL DEFAULT 0.5, tutorial_completed INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 15)", 0)
        // Seed a fully non-default settings row so the migration's data-survival is meaningful (a muted,
        // right-handed save with non-default volumes and the tutorial already completed).
        driver.execute(
            null,
            "INSERT INTO settings(id, handedness, master_muted, sfx_volume, music_volume, tutorial_completed) " +
                "VALUES (0, 'RIGHT_HANDED', 1, 0.25, 0.75, 1)",
            0,
        )
    }

    /** Read the single settings row's three v16 REAL columns directly via SQL (the backfill assertions). */
    private fun readReal(column: String): Double? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT $column FROM settings WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getDouble(0))
            },
            parameters = 0,
            binders = null,
        ).value

    /**
     * Bring a **settings-only** migration fixture's `settings` table up to the current (v18) repository-
     * readable shape by applying just the additive accessibility columns from `migrations/17.sqm`.
     *
     * These legacy fixtures (v13/v14/v15) build only `meta` + `settings`, not the slot-partitioned game-state
     * tables that the v16->v17 step (`16.sqm`) rebuilds, so the full `Schema.migrate` chain to v18 cannot run
     * here. The repository's `selectSettings`, however, now reads the UC39 columns, so the test adds exactly
     * those three columns (kept in lock-step with 17.sqm; the full real migration is exercised in
     * `migrating a real v17 database to v18 …`) to reach a repository-loadable schema.
     */
    private fun addUc39SettingsColumns() {
        driver.execute(null, "ALTER TABLE settings ADD COLUMN colorblind_mode TEXT NOT NULL DEFAULT 'STANDARD'", 0)
        driver.execute(null, "ALTER TABLE settings ADD COLUMN text_scale REAL NOT NULL DEFAULT 1.0", 0)
        driver.execute(null, "ALTER TABLE settings ADD COLUMN reduced_motion INTEGER NOT NULL DEFAULT 0", 0)
    }

    @Test
    fun `migrating a real v15 database to v16 adds the joystick plus UI-scale columns, backfills defaults, and bumps version`() {
        buildRealV15Database()

        // Apply the sequential v15 -> v16 migration (runs migrations/15.sqm).
        OrbitalFrontier.Schema.migrate(driver, 15L, 16L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The three new columns now exist on the single-row settings table (purely additive).
        assertTrue("settings.joystick_sensitivity column must exist after migration", columnExists("settings", "joystick_sensitivity"))
        assertTrue("settings.joystick_deadzone column must exist after migration", columnExists("settings", "joystick_deadzone"))
        assertTrue("settings.ui_scale column must exist after migration", columnExists("settings", "ui_scale"))

        // Backfill: a pre-UC37 save had none of these prefs, so the migrated row reads them back at the
        // schema DEFAULTs 1.0 / 0.15 / 2.0 — neutral stick, model-floor deadzone, ×2 UI (no behaviour change).
        assertEquals("joystick_sensitivity backfills to 1.0", 1.0, readReal("joystick_sensitivity")!!, 0.0)
        assertEquals("joystick_deadzone backfills to 0.15", 0.15, readReal("joystick_deadzone")!!, 0.0)
        assertEquals("ui_scale backfills to 2.0", 2.0, readReal("ui_scale")!!, 0.0)

        // Data survival: the pre-UC37 handedness column is untouched by the migration (raw SQL, schema-agnostic).
        assertEquals("the v15 handedness must survive the v15->v16 migration", "RIGHT_HANDED", readHandedness())

        // The stored save-format version is bumped to 16 — assert the v15->v16 step before continuing.
        assertEquals(16L, queries.selectSaveVersion().executeAsOne())

        // UC39 widened selectSettings for the colorblind_mode/text_scale/reduced_motion columns a v16-only DB
        // lacks; the v16->v17 step repartitions game-state tables this settings-only fixture doesn't build, so
        // the additive UC39 settings columns are applied directly (see [addUc39SettingsColumns]) to reach the
        // repository-readable v18 settings shape. They are pure additions, so the survival + default reads
        // below are unchanged.
        addUc39SettingsColumns()
        val repo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals(
            "the v15 audio settings must survive the v15->...->v18 migrations",
            AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f),
            repo.loadAudioSettings(),
        )
        assertTrue("the v15 tutorial flag must survive the migrations", repo.loadTutorialCompleted())

        // The repository reads the backfilled values back as the DEFAULT value types.
        assertEquals("a migrated v15 save reads back the default joystick tuning", JoystickTuning.DEFAULT, repo.loadJoystickTuning())
        assertEquals("a migrated v15 save reads back the default UI scale", UiScale.DEFAULT_FACTOR, repo.loadUiScale(), 0f)

        // The new columns are writable, not just present: tuning + UI-scale writes on top of the migrated DB
        // round-trip, and (per-field discipline) leave the migrated handedness / audio / tutorial untouched.
        val tuning = JoystickTuning(sensitivity = 2.0f, deadzone = 0.4f)
        repo.saveJoystickTuning(tuning)
        repo.saveUiScale(2.5f)
        val freshRepo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals("joystick tuning round-trips on the migrated DB", tuning, freshRepo.loadJoystickTuning())
        assertEquals("UI scale round-trips on the migrated DB", 2.5f, freshRepo.loadUiScale(), 0f)
        assertEquals(
            "the UC37 writes must not clobber the migrated handedness",
            Handedness.RIGHT_HANDED,
            freshRepo.loadHandedness(),
        )
        assertEquals(
            "the UC37 writes must not clobber the migrated audio settings",
            AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f),
            freshRepo.loadAudioSettings(),
        )
        assertTrue("the UC37 writes must not clobber the migrated tutorial flag", freshRepo.loadTutorialCompleted())
    }

    // --- UC38 AC#3/#4: v16 -> v17 partitions every game-state table by slot_id, backfilling the legacy ---
    // --- single autosave into slot 0 ("Autosave"), and adds the active-slot pointer + slot metadata.    ---

    /**
     * Build the exact v16 (UC37) schema — `meta` (NO `active_slot_id`) + the full v16 `settings` + the
     * **single-row** v16 `game_state` (CHECK (id = 0), NO `name` / `last_saved_epoch_millis` /
     * `play_time_seconds`) + every game-state table keyed WITHOUT a `slot_id` — seeded with a rich real
     * save (sector + last dock + credits + a starter ship + cargo + a revealed contact + a faction-attributed
     * mission + a non-neutral reputation + an owned station) so the v16→v17 table-rebuild migration is
     * exercised against **data-bearing** tables, not empty ones. This is the v16 baseline the production .sq
     * describes; UC38's 16.sqm repartitions all 11 game-state tables by `slot_id` (the legacy save → slot 0).
     */
    private fun buildRealV16Database() {
        driver.execute(null, "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)", 0)
        driver.execute(
            null,
            "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), handedness TEXT NOT NULL, " +
                "master_muted INTEGER NOT NULL DEFAULT 0, sfx_volume REAL NOT NULL DEFAULT 1.0, " +
                "music_volume REAL NOT NULL DEFAULT 0.5, tutorial_completed INTEGER NOT NULL DEFAULT 0, " +
                "joystick_sensitivity REAL NOT NULL DEFAULT 1.0, joystick_deadzone REAL NOT NULL DEFAULT 0.15, " +
                "ui_scale REAL NOT NULL DEFAULT 2.0)",
            0,
        )
        // v16 game_state: SINGLE-ROW (CHECK (id = 0)) — no slot_id, no slot display metadata yet.
        driver.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0, last_docked_station_id TEXT)",
            0,
        )
        // v16 ship: single-keyed (id PK), with fuel/ship_type/crew.
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
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, PRIMARY KEY (ship_id, slot_category, slot_index))",
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
            "CREATE TABLE ship_section_damage (ship_id INTEGER NOT NULL, section TEXT NOT NULL, " +
                "current_hp INTEGER NOT NULL, PRIMARY KEY (ship_id, section))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        driver.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        driver.execute(
            null,
            "CREATE TABLE mission (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, source TEXT NOT NULL, " +
                "status TEXT NOT NULL, reward_credits INTEGER NOT NULL, reward_resource TEXT, " +
                "reward_resource_units INTEGER NOT NULL DEFAULT 0, quota_resource TEXT, quota_units INTEGER NOT NULL DEFAULT 0, " +
                "pickup TEXT, destination TEXT, remaining_ticks INTEGER NOT NULL DEFAULT 0, picked_up INTEGER NOT NULL DEFAULT 0, " +
                "faction_id TEXT)",
            0,
        )
        driver.execute(null, "CREATE TABLE reputation (faction_id TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE owned_station (id INTEGER NOT NULL PRIMARY KEY, sector TEXT NOT NULL)", 0)
        driver.execute(
            null,
            "CREATE TABLE station_module (station_id INTEGER NOT NULL, slot_index INTEGER NOT NULL, " +
                "module_type TEXT NOT NULL, PRIMARY KEY (station_id, slot_index))",
            0,
        )

        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 16)", 0)
        driver.execute(
            null,
            "INSERT INTO settings(id, handedness, master_muted, sfx_volume, music_volume, tutorial_completed, " +
                "joystick_sensitivity, joystick_deadzone, ui_scale) " +
                "VALUES (0, 'LEFT_HANDED', 0, 1.0, 0.5, 1, 1.0, 0.15, 2.0)",
            0,
        )
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
        driver.execute(
            null,
            "INSERT INTO mission(id, type, source, status, reward_credits, quota_resource, quota_units, " +
                "remaining_ticks, picked_up, faction_id) " +
                "VALUES ('board:alpha-station:mining', 'MINING', 'BOARD', 'ACTIVE', 400, 'HYDROGEN', 8, 0, 0, 'league')",
            0,
        )
        driver.execute(null, "INSERT INTO reputation(faction_id, value) VALUES ('league', 25)", 0)
        driver.execute(null, "INSERT INTO owned_station(id, sector) VALUES (0, 'beta')", 0)
    }

    @Test
    fun `migrating a real v16 database to v17 backfills the legacy save into slot 0 and adds slot metadata`() {
        buildRealV16Database()

        // Apply the sequential v16 -> v17 migration (runs migrations/16.sqm — the first table-rebuild step).
        OrbitalFrontier.Schema.migrate(driver, 16L, 17L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The v17 additions exist: the active-slot pointer on meta + the per-slot display metadata columns.
        assertTrue("meta.active_slot_id column must exist after migration", columnExists("meta", "active_slot_id"))
        assertTrue("game_state.slot_id column must exist after migration", columnExists("game_state", "slot_id"))
        assertTrue("game_state.name column must exist after migration", columnExists("game_state", "name"))
        assertTrue(
            "game_state.last_saved_epoch_millis column must exist after migration",
            columnExists("game_state", "last_saved_epoch_millis"),
        )
        assertTrue("game_state.play_time_seconds column must exist after migration", columnExists("game_state", "play_time_seconds"))
        // The game-state tables are repartitioned: ship/cargo/etc. gain slot_id.
        assertTrue("ship.slot_id column must exist after migration", columnExists("ship", "slot_id"))
        assertTrue("cargo.slot_id column must exist after migration", columnExists("cargo", "slot_id"))

        // Data survival: the pre-UC38 settings are untouched (settings are global, not per-slot).
        assertEquals("v16 settings must survive", "LEFT_HANDED", readHandedness())

        // The v16 -> v17 step itself landed at v17 (the step under test) before we continue the chain.
        assertEquals("the v16 -> v17 step bumps to 17", 17L, queries.selectSaveVersion().executeAsOne())
        // Continue to the current schema so the now-current slot-aware repository can load the backfilled
        // save (its queries reference the v19 bounty mission columns; the per-step v17..v19 assertions live
        // in their own tests).
        OrbitalFrontier.Schema.migrate(driver, 17L, OrbitalFrontier.Schema.version)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)

        // AC#3: the legacy single autosave is backfilled into slot 0 (SlotId.LEGACY), which the autosave /
        // Continue pointer now targets (DEFAULT 0).
        assertEquals("the active slot defaults to the legacy slot", SlotId.LEGACY, repo.activeSlot())
        assertTrue("the migrated save lives in the legacy slot", repo.hasSave(SlotId.LEGACY))
        assertFalse("no other slot holds a save after migration", repo.hasSave(SlotId(1)))

        // AC#3: the whole rich save reads back from slot 0 — sector, credits, last dock, cargo, the revealed
        // contact, the faction-attributed mission, the non-neutral reputation, and the owned station all survive.
        val loaded = repo.loadGameState(SlotId.LEGACY)
        assertNotNull("the legacy save must load from slot 0", loaded)
        assertEquals("the saved sector survives", "beta", loaded!!.currentSector.value)
        assertEquals("the saved credits survive", 1234L, loaded.credits)
        assertEquals("the saved last dock survives", "alpha-station", loaded.lastDockedStation?.value)
        assertEquals("the saved cargo survives", 9, loaded.cargo.contents[ResourceType.IRON_ORE])
        assertTrue("the revealed contact survives", loaded.revealedContacts.any { it.value == "alpha-derelict" })
        assertTrue("the accepted mission survives", loaded.missions.accepted.any { it.id.value == "board:alpha-station:mining" })
        assertEquals("the non-neutral reputation survives", 25, loaded.reputation.valueFor(Factions.LEAGUE.id))
        assertNotNull("the owned station survives", loaded.stations.station(StationId(0)))

        // AC#1/#3: slot 0 reads back as an occupied "Autosave" slot with backfilled metadata (unknown
        // last-saved / play-time = 0); every other configured slot is empty.
        val slots = repo.listSlots()
        assertEquals("one summary per configured slot", SaveSlots.COUNT, slots.size)
        val legacy = slots[0]
        assertTrue("slot 0 is occupied after the backfill", legacy is SaveSlotSummary.Occupied)
        legacy as SaveSlotSummary.Occupied
        assertEquals("the legacy slot is named 'Autosave'", "Autosave", legacy.name)
        assertEquals("a pre-UC38 save has unknown (0) last-saved", 0L, legacy.lastSavedEpochMillis)
        assertEquals("a pre-UC38 save has 0 backfilled play time", 0L, legacy.playTimeSeconds)
        assertEquals("the legacy slot summary carries the saved credits", 1234L, legacy.credits)
        assertTrue("slots 1..N read back empty", slots.drop(1).all { it is SaveSlotSummary.Empty })

        // The stored save-format version is now the current schema (the chain was continued past v17 so the
        // backfilled save could load through the now-current repository).
        assertEquals(OrbitalFrontier.Schema.version, queries.selectSaveVersion().executeAsOne())
    }

    // --- UC39 AC#4: v17 -> v18 adds the three accessibility columns to the global settings row, ----
    // --- backfilling a pre-UC39 save to the prior behaviour (standard palette, ×1 text, motion on). --

    /**
     * Build the exact v17 (UC38) `settings` schema — handedness + the UC31 audio columns + the UC36 tutorial
     * flag + the UC37 joystick/UI-scale columns, but NONE of the UC39 accessibility columns (exactly what
     * 17.sqm adds) — plus a minimal `meta`. The v17→v18 migration is settings-only and additive (it never
     * touches the slot-partitioned game-state tables), so — like the v15→v16 settings-only test — only `meta`
     * + `settings` need to exist for it to run and for the settings repository to read back. A fully
     * non-default settings row is seeded so the migration's data-survival assertion is meaningful.
     */
    private fun buildRealV17Database() {
        driver.execute(
            null,
            "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), handedness TEXT NOT NULL, " +
                "master_muted INTEGER NOT NULL DEFAULT 0, sfx_volume REAL NOT NULL DEFAULT 1.0, " +
                "music_volume REAL NOT NULL DEFAULT 0.5, tutorial_completed INTEGER NOT NULL DEFAULT 0, " +
                "joystick_sensitivity REAL NOT NULL DEFAULT 1.0, joystick_deadzone REAL NOT NULL DEFAULT 0.15, " +
                "ui_scale REAL NOT NULL DEFAULT 2.0)",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 17)", 0)
        // A muted, left-handed save with non-default volumes, the tutorial already completed, and non-default
        // joystick + UI-scale — so every pre-UC39 column carries a value the migration must preserve.
        driver.execute(
            null,
            "INSERT INTO settings(id, handedness, master_muted, sfx_volume, music_volume, tutorial_completed, " +
                "joystick_sensitivity, joystick_deadzone, ui_scale) " +
                "VALUES (0, 'LEFT_HANDED', 1, 0.25, 0.75, 1, 2.0, 0.4, 2.5)",
            0,
        )
    }

    /** Read the single settings row's TEXT column directly via SQL (the colorblind_mode backfill assertion). */
    private fun readText(column: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT $column FROM settings WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
            binders = null,
        ).value

    /** Read the single settings row's INTEGER column directly via SQL (the reduced_motion backfill assertion). */
    private fun readLong(column: String): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT $column FROM settings WHERE id = 0",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v17 database to v18 adds the accessibility columns, backfills defaults, and bumps version`() {
        buildRealV17Database()

        // Apply the sequential v17 -> v18 migration (runs migrations/17.sqm — purely additive on settings).
        OrbitalFrontier.Schema.migrate(driver, 17L, 18L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The three new accessibility columns now exist on the single-row settings table.
        assertTrue("settings.colorblind_mode column must exist after migration", columnExists("settings", "colorblind_mode"))
        assertTrue("settings.text_scale column must exist after migration", columnExists("settings", "text_scale"))
        assertTrue("settings.reduced_motion column must exist after migration", columnExists("settings", "reduced_motion"))

        // Backfill: a pre-UC39 save had none of these prefs, so the migrated row reads them back at the
        // schema DEFAULTs 'STANDARD' / 1.0 / 0 — the standard palette, ×1 text, motion on (no behaviour change).
        assertEquals("colorblind_mode backfills to 'STANDARD'", "STANDARD", readText("colorblind_mode"))
        assertEquals("text_scale backfills to 1.0", 1.0, readReal("text_scale")!!, 0.0)
        assertEquals("reduced_motion backfills to 0", 0L, readLong("reduced_motion"))

        // Data survival: every pre-UC39 column is untouched by the migration.
        assertEquals("the v17 handedness must survive the v17->v18 migration", "LEFT_HANDED", readHandedness())
        val repo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals(
            "the v17 audio settings must survive the v17->v18 migration",
            AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f),
            repo.loadAudioSettings(),
        )
        assertTrue("the v17 tutorial flag must survive the v17->v18 migration", repo.loadTutorialCompleted())
        assertEquals(
            "the v17 joystick tuning must survive the v17->v18 migration",
            JoystickTuning(sensitivity = 2.0f, deadzone = 0.4f),
            repo.loadJoystickTuning(),
        )
        assertEquals("the v17 UI scale must survive the v17->v18 migration", 2.5f, repo.loadUiScale(), 0f)

        // The repository reads the backfilled accessibility values back as the DEFAULT value types.
        assertEquals("a migrated v17 save reads back the default colour-vision mode", ColorVisionMode.DEFAULT, repo.loadColorVisionMode())
        assertEquals("a migrated v17 save reads back the default text scale", TextScale.DEFAULT_FACTOR, repo.loadTextScale(), 0f)
        assertFalse("a migrated v17 save reads back motion-on (reduced=false)", repo.loadReducedMotion())

        // The stored save-format version is bumped to 18 (the current schema).
        assertEquals(18L, queries.selectSaveVersion().executeAsOne())

        // The new columns are writable, not just present: accessibility writes on top of the migrated DB
        // round-trip, and (per-field discipline) leave the migrated handedness / audio / tutorial untouched.
        repo.saveColorVisionMode(ColorVisionMode.COLORBLIND_SAFE)
        repo.saveTextScale(1.3f)
        repo.saveReducedMotion(true)
        val freshRepo = SqlDelightSettingsRepository(database, NoOpLogger)
        assertEquals("colour-vision mode round-trips on the migrated DB", ColorVisionMode.COLORBLIND_SAFE, freshRepo.loadColorVisionMode())
        assertEquals("text scale round-trips on the migrated DB", 1.3f, freshRepo.loadTextScale(), 0f)
        assertTrue("reduced-motion round-trips on the migrated DB", freshRepo.loadReducedMotion())
        assertEquals(
            "the UC39 writes must not clobber the migrated handedness",
            Handedness.LEFT_HANDED,
            freshRepo.loadHandedness(),
        )
        assertEquals(
            "the UC39 writes must not clobber the migrated audio settings",
            AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f),
            freshRepo.loadAudioSettings(),
        )
        assertTrue("the UC39 writes must not clobber the migrated tutorial flag", freshRepo.loadTutorialCompleted())
    }

    // --- UC41 AC#5 / ADR 0029: v18 -> v19 adds the three bounty columns to the per-slot `mission` table, --
    // --- backfilling a pre-UC41 mission to the non-bounty defaults (null target zone, 0/0 kill counts). ----

    /**
     * Build the exact v18 `mission` schema — the UC12/UC14 columns through `faction_id` and the v17 `slot_id`
     * PK, but NONE of the UC41 bounty columns (exactly what 18.sqm adds) — plus a minimal `meta`. The v18→v19
     * migration only touches `mission` + `meta` (additive `ALTER TABLE ADD COLUMN`), so — like the settings-only
     * migration tests — only those two tables need to exist for it to run and for a direct SQL read-back. A
     * pre-UC41 mining mission row is seeded so the migration's backfill assertion is meaningful.
     */
    private fun buildRealV18MissionTable() {
        driver.execute(
            null,
            "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE mission (slot_id INTEGER NOT NULL, id TEXT NOT NULL, type TEXT NOT NULL, source TEXT NOT NULL, " +
                "status TEXT NOT NULL, reward_credits INTEGER NOT NULL, reward_resource TEXT, " +
                "reward_resource_units INTEGER NOT NULL DEFAULT 0, quota_resource TEXT, quota_units INTEGER NOT NULL DEFAULT 0, " +
                "pickup TEXT, destination TEXT, remaining_ticks INTEGER NOT NULL DEFAULT 0, " +
                "picked_up INTEGER NOT NULL DEFAULT 0, faction_id TEXT, PRIMARY KEY (slot_id, id))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 18)", 0)
        // A pre-UC41 board mining mission (the golden 8-Hydrogen offer) — exactly the shape a v18 save carries.
        driver.execute(
            null,
            "INSERT INTO mission(slot_id, id, type, source, status, reward_credits, quota_resource, quota_units, faction_id) " +
                "VALUES (0, 'board:alpha-station:mining', 'MINING', 'BOARD', 'ACTIVE', 400, 'HYDROGEN', 8, 'league')",
            0,
        )
    }

    /** Read a single mission row's TEXT column directly via SQL (the target_zone_id backfill assertion). */
    private fun readMissionText(column: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT $column FROM mission WHERE slot_id = 0 AND id = 'board:alpha-station:mining'",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
            binders = null,
        ).value

    /** Read a single mission row's INTEGER column directly via SQL (the kill_target / kill_progress backfill). */
    private fun readMissionLong(column: String): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT $column FROM mission WHERE slot_id = 0 AND id = 'board:alpha-station:mining'",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `migrating a real v18 database to v19 adds the bounty columns, backfills the non-bounty defaults, and bumps version`() {
        buildRealV18MissionTable()

        // Apply the sequential v18 -> v19 migration (runs migrations/18.sqm — purely additive on the mission table).
        OrbitalFrontier.Schema.migrate(driver, 18L, 19L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The three new bounty columns now exist on the per-slot mission table.
        assertTrue("mission.target_zone_id column must exist after migration", columnExists("mission", "target_zone_id"))
        assertTrue("mission.kill_target column must exist after migration", columnExists("mission", "kill_target"))
        assertTrue("mission.kill_progress column must exist after migration", columnExists("mission", "kill_progress"))

        // Backfill: a pre-UC41 mission had none of these, so the migrated row reads them back at the
        // non-bounty defaults — null target zone, 0 kill target, 0 kill progress (no behaviour change).
        assertNull("target_zone_id backfills to NULL for a non-bounty mission", readMissionText("target_zone_id"))
        assertEquals("kill_target backfills to 0", 0L, readMissionLong("kill_target"))
        assertEquals("kill_progress backfills to 0", 0L, readMissionLong("kill_progress"))

        // Data survival: the pre-UC41 mission columns are untouched by the migration.
        assertEquals("the pre-UC41 quota resource survives", "HYDROGEN", readMissionText("quota_resource"))
        assertEquals("the pre-UC41 quota units survive", 8L, readMissionLong("quota_units"))
        assertEquals("the pre-UC41 faction survives", "league", readMissionText("faction_id"))

        // The stored save-format version is bumped to exactly 19 by this single v18 -> v19 STEP. This
        // test validates that one migration step, not the latest schema: since UC46 the generated
        // OrbitalFrontier.Schema.version is 20, so the v18 -> v19 step deliberately lands BELOW it (the
        // full chain continues v19 -> v20 in its own step test). Asserting 19L literally keeps this a
        // step test rather than a moving "== Schema.version" target that breaks on every future bump.
        assertEquals(19L, queries.selectSaveVersion().executeAsOne())

        // The new columns are writable, not just present: a bounty row inserts + reads back through SQL.
        driver.execute(
            null,
            "INSERT INTO mission(slot_id, id, type, source, status, reward_credits, target_zone_id, kill_target, kill_progress) " +
                "VALUES (0, 'bounty:bounty-alpha-raider', 'BOUNTY', 'RADIO', 'ACTIVE', 800, 'bounty-alpha-raider', 1, 0)",
            0,
        )
        val zone =
            driver.executeQuery(
                identifier = null,
                sql = "SELECT target_zone_id FROM mission WHERE id = 'bounty:bounty-alpha-raider'",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getString(0))
                },
                parameters = 0,
                binders = null,
            ).value
        assertEquals("a bounty row round-trips its target zone on the migrated DB", "bounty-alpha-raider", zone)
    }

    // --- UC46 AC#3: v19 -> v20 adds the station_market table additively (no breaking change) ---

    /**
     * Build a minimal but **data-bearing** v19-shaped DB for the v19 -> v20 step: the `meta` row at
     * save_version 19 (which 19.sqm bumps), plus a `reputation` table (added back at v12) carrying a real
     * standing row that stands in for "prior data that must survive". The v19 -> v20 migration (19.sqm) is
     * a purely additive `CREATE TABLE station_market` + a `meta` version bump — it references no other table —
     * so this minimal shape exercises the step honestly while staying independent of the unrelated
     * settings-column chain (UC39/UC41) the other step tests build.
     */
    private fun buildRealV19MetaWithReputation() {
        driver.execute(
            null,
            "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE reputation (slot_id INTEGER NOT NULL, faction_id TEXT NOT NULL, value INTEGER NOT NULL, " +
                "PRIMARY KEY (slot_id, faction_id))",
            0,
        )
        driver.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 19)", 0)
        driver.execute(null, "INSERT INTO reputation(slot_id, faction_id, value) VALUES (0, 'league', 25)", 0)
    }

    @Test
    fun `migrating a real v19 database to v20 adds the station_market table, preserves prior data, and bumps the version`() {
        buildRealV19MetaWithReputation()

        // Apply the sequential v19 -> v20 migration (runs migrations/19.sqm — purely additive CREATE TABLE).
        OrbitalFrontier.Schema.migrate(driver, 19L, 20L)

        val database = OrbitalFrontier(driver)
        val queries = database.orbitalFrontierQueries

        // The new (empty) station_market table exists — a migrated save holds no pressure, so every station
        // reads back at its authored base price, byte-identical to a pre-UC46 game (AC#3; purely additive).
        assertTrue("station_market table must exist after migration", tableExists("station_market"))
        assertEquals(
            "a migrated v19 save has no station_market rows",
            0L,
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM station_market",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                },
                parameters = 0,
                binders = null,
            ).value,
        )

        // Data survival: the pre-UC46 reputation row is untouched by the additive migration.
        val standing =
            driver.executeQuery(
                identifier = null,
                sql = "SELECT value FROM reputation WHERE slot_id = 0 AND faction_id = 'league'",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0,
                binders = null,
            ).value
        assertEquals("the pre-UC46 reputation standing survives the migration", 25L, standing)

        // The stored save-format version is bumped to exactly 20 by this single v19 -> v20 step, and 20 is
        // the current generated schema version (UC46 is the latest migration in the chain).
        assertEquals(20L, queries.selectSaveVersion().executeAsOne())
        assertEquals(
            "the v19 -> v20 step lands on the current generated schema version",
            OrbitalFrontier.Schema.version,
            queries.selectSaveVersion().executeAsOne(),
        )

        // The new table is writable, not just present: a pressure row inserts + reads back through SQL.
        driver.execute(
            null,
            "INSERT INTO station_market(slot_id, station_id, resource, pressure) VALUES (0, 'alpha-station', 'TITANIUM', 6)",
            0,
        )
        val pressure =
            driver.executeQuery(
                identifier = null,
                sql = "SELECT pressure FROM station_market WHERE slot_id = 0 AND station_id = 'alpha-station' AND resource = 'TITANIUM'",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0,
                binders = null,
            ).value
        assertEquals("a station_market pressure row round-trips on the migrated DB", 6L, pressure)
    }
}
