package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionGenerator
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mission-persistence tests (UC12 AC#5) — the v9→v10 `mission` table, the save/reload round-trip of
 * accepted / terminal missions, and the **regenerate-and-filter** invariant (ADR 0011): available
 * offers are never stored, they are regenerated from the static authored world on load and filtered
 * against the persisted accepted/terminal ids — so an accepted offer reloads byte-identically and a
 * completed/failed offer is never re-surfaced.
 *
 * Each test runs against an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code path that
 * runs on the Android driver on device); "app restart" is a fresh repository over the same live DB.
 */
class MissionPersistenceTest {
    private val alphaStation = PoiId("alpha-station")
    private val betaStation = PoiId("beta-station")
    private val world = MvpSectorMap.build()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
    }

    private fun repo() = SqlDelightGameStateRepository(database, NoOpLogger)

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

    private fun activeMining(id: String) =
        Mission(
            id = MissionId(id),
            type = MissionType.MINING,
            source = MissionSource.BOARD,
            status = MissionStatus.ACTIVE,
            rewardCredits = 400,
            quotaResource = ResourceType.HYDROGEN,
            quotaUnits = 8,
        )

    private fun activeCourier(id: String) =
        Mission(
            id = MissionId(id),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.ACTIVE,
            rewardCredits = 535,
            pickup = alphaStation,
            destination = betaStation,
            remainingTicks = 156,
            pickedUp = true,
        )

    // --- AC#5: accepted/terminal missions survive a save → reload exactly ---

    @Test
    fun `accepted active missions (mining + courier) survive a save and reload exactly`() {
        val saved =
            WorldState(
                missions =
                    MissionLog(
                        accepted = listOf(activeMining("board:alpha-station:mining"), activeCourier("board:alpha-station:courier")),
                    ),
            )
        repo().saveGameState(saved)

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        // The loader returns rows ordered by id (a deterministic load order), so compare order-insensitively.
        assertEquals(
            "both accepted missions survive byte-for-byte",
            saved.missions.accepted.toSet(),
            reloaded!!.missions.accepted.toSet(),
        )
        // The courier's progress (picked-up flag + remaining ticks) is part of the persisted state (AC#5).
        val courier = reloaded.missions.accepted.single { it.type == MissionType.COURIER }
        assertTrue("the picked-up flag persists", courier.pickedUp)
        assertEquals("the courier timer persists", 156, courier.remainingTicks)
    }

    // --- AC#5 / ADR 0011: available offers are NOT persisted; they are regenerated on load ---

    @Test
    fun `available offers are not persisted and are reproduced byte-identically on reload`() {
        // Save with both a transient available offer list AND an accepted mission.
        val boardOffers = MissionGenerator.boardOffers(world, alphaStation)
        val saved =
            WorldState(
                missions =
                    MissionLog(
                        available = boardOffers,
                        accepted = listOf(activeMining("board:alpha-station:mining")),
                    ),
            )
        repo().saveGameState(saved)

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        // Available offers are transient — a reload reads them back empty (they are never stored).
        assertTrue("available offers are not persisted", reloaded!!.missions.available.isEmpty())

        // Regenerate-and-filter: the offers are reproduced byte-identically from the static world and
        // filtered against the persisted taken ids, so the surfaced board exactly equals the original
        // board minus the already-accepted mining offer.
        val regenerated = MissionGenerator.boardOffers(world, alphaStation).filter { it.id !in reloaded.missions.takenIds }
        val expected = boardOffers.filter { it.id != MissionId("board:alpha-station:mining") }
        assertEquals("regenerated offers are byte-identical to the original (minus the accepted one)", expected, regenerated)
        assertTrue("the accepted mining offer is not re-surfaced", regenerated.none { it.id == MissionId("board:alpha-station:mining") })
    }

    // --- AC#5 / ADR 0011: a terminal (completed/failed) mission id is never re-offered ---

    @Test
    fun `a completed mission is not re-offered after reload`() {
        val completed = activeMining("board:alpha-station:mining").copy(status = MissionStatus.COMPLETED)
        repo().saveGameState(WorldState(missions = MissionLog(accepted = listOf(completed))))

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals("the completed mission persists as terminal", MissionStatus.COMPLETED, reloaded!!.missions.accepted.single().status)

        val surfaced = MissionGenerator.boardOffers(world, alphaStation).filter { it.id !in reloaded.missions.takenIds }
        assertTrue(
            "a completed mining offer is never re-surfaced",
            surfaced.none { it.id == MissionId("board:alpha-station:mining") },
        )
        // The courier (a different id) is unaffected — it still surfaces.
        assertTrue("an unrelated offer still surfaces", surfaced.any { it.id == MissionId("board:alpha-station:courier") })
    }

    @Test
    fun `a failed courier is not re-offered after reload`() {
        val failed =
            activeCourier("board:alpha-station:courier").copy(status = MissionStatus.FAILED, remainingTicks = 0)
        repo().saveGameState(WorldState(missions = MissionLog(accepted = listOf(failed))))

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals(MissionStatus.FAILED, reloaded!!.missions.accepted.single().status)

        val surfaced = MissionGenerator.boardOffers(world, alphaStation).filter { it.id !in reloaded.missions.takenIds }
        assertTrue(
            "a failed courier offer is never re-surfaced",
            surfaced.none { it.id == MissionId("board:alpha-station:courier") },
        )
    }

    // --- AC#5: a real v9 → v10 migration adds the (empty) mission table, preserving prior data ---

    @Test
    fun `migrating a real v9 database to v10 adds the empty mission table and bumps the version`() {
        // A fresh driver carrying a hand-built v9 schema, so the migration runs against a real prior DB.
        val v9 = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            buildRealV9Database(v9)
            // First the v9 -> v10 step under test (adds the empty mission table, bumps the version).
            OrbitalFrontier.Schema.migrate(v9, 9L, 10L)

            val migrated = OrbitalFrontier(v9)
            val queries = migrated.orbitalFrontierQueries

            assertTrue("the v10 mission table must exist after migration", v9.tableExistsLocal("mission"))
            assertEquals("a migrated v9 save has no missions", 0L, v9.missionCount())
            assertEquals("prior data is preserved", "LEFT_HANDED", queries.selectSettings().executeAsOneOrNull())
            assertEquals("the save version is bumped to 10", 10L, queries.selectSaveVersion().executeAsOne())

            // Continue the chain to the current schema so the now-v12-aware repository (which reads the
            // ship_section_damage table + game_state.last_docked_station_id added by v11, and the reputation
            // table added by v12) can load — the canonical v10->v11 / v11->v12 assertions live in SaveMigrationTest.
            OrbitalFrontier.Schema.migrate(v9, 10L, 12L)
            assertEquals("the chain reaches the current save version", 12L, queries.selectSaveVersion().executeAsOne())

            // The migrated save loads through the repository with an empty mission log, and a freshly
            // saved mission round-trips on top of it (the new table is writable, not just present).
            val gameRepo = SqlDelightGameStateRepository(migrated, NoOpLogger)
            val loaded = gameRepo.loadGameState()
            assertNotNull("a migrated v9 save still loads", loaded)
            assertTrue("a migrated save has an empty mission log", loaded!!.missions.accepted.isEmpty())

            gameRepo.saveGameState(loaded.copy(missions = MissionLog(accepted = listOf(activeMining("board:alpha-station:mining")))))
            val reSaved = SqlDelightGameStateRepository(migrated, NoOpLogger).loadGameState()
            assertEquals(
                "a mission saved into the migrated DB round-trips",
                listOf(MissionId("board:alpha-station:mining")),
                reSaved!!.missions.accepted.map { it.id },
            )
        } finally {
            runCatching { v9.close() }
        }
    }

    private fun JdbcSqliteDriver.tableExistsLocal(name: String): Boolean =
        executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            parameters = 1,
            binders = { bindString(0, name) },
        ).value

    private fun JdbcSqliteDriver.missionCount(): Long =
        executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM mission",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
            binders = null,
        ).value

    /** Build the exact v9 (UC11) schema with a seeded single-ship save — NO mission table yet. */
    private fun buildRealV9Database(d: JdbcSqliteDriver) {
        d.execute(null, "CREATE TABLE meta (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), save_version INTEGER NOT NULL)", 0)
        d.execute(null, "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), handedness TEXT NOT NULL)", 0)
        d.execute(
            null,
            "CREATE TABLE game_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), " +
                "current_sector TEXT NOT NULL, active_ship_id INTEGER NOT NULL, docked_station_id TEXT, " +
                "credits INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        d.execute(
            null,
            "CREATE TABLE ship (id INTEGER NOT NULL PRIMARY KEY, pos_x REAL NOT NULL, pos_y REAL NOT NULL, " +
                "vel_x REAL NOT NULL, vel_y REAL NOT NULL, heading REAL NOT NULL, ang_vel REAL NOT NULL, " +
                "fuel REAL NOT NULL DEFAULT 100, ship_type TEXT NOT NULL DEFAULT 'starter', crew INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        d.execute(
            null,
            "CREATE TABLE ship_upgrade (ship_id INTEGER NOT NULL, slot_category TEXT NOT NULL, " +
                "slot_index INTEGER NOT NULL, upgrade_id TEXT NOT NULL, PRIMARY KEY (ship_id, slot_category, slot_index))",
            0,
        )
        d.execute(
            null,
            "CREATE TABLE cargo (ship_id INTEGER NOT NULL, resource TEXT NOT NULL, units INTEGER NOT NULL, " +
                "PRIMARY KEY (ship_id, resource))",
            0,
        )
        d.execute(
            null,
            "CREATE TABLE field_deposit (field_id TEXT NOT NULL, resource TEXT NOT NULL, " +
                "remaining_units INTEGER NOT NULL, PRIMARY KEY (field_id, resource))",
            0,
        )
        d.execute(null, "CREATE TABLE revealed_contact (contact_id TEXT NOT NULL PRIMARY KEY)", 0)
        d.execute(null, "INSERT INTO meta(id, save_version) VALUES (0, 9)", 0)
        d.execute(null, "INSERT INTO settings(id, handedness) VALUES (0, 'LEFT_HANDED')", 0)
        d.execute(
            null,
            "INSERT INTO game_state(id, current_sector, active_ship_id, docked_station_id, credits) VALUES (0, 'alpha', 0, NULL, 0)",
            0,
        )
        d.execute(
            null,
            "INSERT INTO ship(id, pos_x, pos_y, vel_x, vel_y, heading, ang_vel, fuel, ship_type, crew) " +
                "VALUES (0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 'starter', 0)",
            0,
        )
    }

    @Test
    fun `the fresh v10 schema already carries the mission table`() {
        assertTrue("a fresh Schema.create DB has the mission table", tableExists("mission"))
        assertFalse("a fresh DB has no missions yet", repo().loadGameState()?.missions?.accepted?.isNotEmpty() ?: false)
    }
}
