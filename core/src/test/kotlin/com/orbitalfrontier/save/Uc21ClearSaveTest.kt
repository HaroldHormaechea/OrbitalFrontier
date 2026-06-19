package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.station.OwnedStation
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for the UC21 save wipe [SqlDelightGameStateRepository.clearSave], exercised
 * against an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code that runs on the Android
 * driver on device), mirroring [SqlDelightGameStateRepositoryTest]'s setup.
 *
 * UC21 AC#3 contract verified here:
 *  - clearSave() over a rich, multi-table save (multi-ship fleet + owned station/modules + accepted
 *    missions + non-neutral reputation + field depletion + revealed contacts) leaves NO stale game
 *    rows: `loadGameState()` reads back `null` and `hasSave()` is `false`.
 *  - the player's `settings` (handedness) and the `meta` save-format version survive the wipe — a wipe
 *    resets *progress* only, not preferences or the schema version (so no migration re-runs).
 *  - clearSave() on an empty DB is a safe no-op (Start with no save calls it unconditionally).
 */
class Uc21ClearSaveTest {
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
        runCatching { driver.close() }
    }

    private fun newGameStateRepository() = SqlDelightGameStateRepository(database, logger, com.orbitalfrontier.platform.FixedClock)

    private fun newSettingsRepository() = SqlDelightSettingsRepository(database, logger)

    /**
     * A rich world state touching as many durable tables as possible so a wipe is meaningfully tested:
     * a two-ship fleet (kinematics + cargo + fuel + per-ship loadout via the derived capacities), an
     * owned station with modules, accepted missions, non-neutral reputation, field depletion, revealed
     * contacts, credits, and a docked station header.
     */
    private fun richState(): WorldState {
        val ship0 =
            OwnedShip.fresh(ShipId(0), ShipRoster.STARTER, Vec2(1.5f, -2.5f))
                .let {
                    it.copy(
                        cargo = Cargo(mapOf(ResourceType.IRON_ORE to 10), it.cargo.capacity),
                        fuel = Fuel(level = 30f, capacity = it.fuel.capacity),
                    )
                }
        val ship1 = OwnedShip.fresh(ShipId(1), ShipRoster.SWIFT, Vec2(40f, 60f))

        val station =
            OwnedStation(
                id = StationId(0),
                sector = SectorId("alpha"),
                modules = mapOf(0 to StationModuleCatalog.COMMERCE_HUB, 2 to StationModuleCatalog.RETROFIT_BAY),
            )

        val mission =
            Mission(
                id = MissionId("board:alpha-station:mining"),
                type = MissionType.MINING,
                source = MissionSource.BOARD,
                status = MissionStatus.ACTIVE,
                rewardCredits = 400,
                quotaResource = ResourceType.HYDROGEN,
                quotaUnits = 8,
            )

        return WorldState(
            currentSector = SectorId("beta"),
            fleet = Fleet(listOf(ship0, ship1), ShipId(1)),
            dockedStation = PoiId("beta-station"),
            fieldDepletion = mapOf(PoiId("alpha-belt") to mapOf(ResourceType.IRON_ORE to 18)),
            credits = 2900L,
            revealedContacts = setOf(PoiId("hidden-1"), PoiId("hidden-2")),
            missions = MissionLog(accepted = listOf(mission)),
            reputation = Reputation(mapOf(FactionId("traders-league") to 5)),
            stations = StationRegistry(listOf(station)),
            lastDockedStation = PoiId("beta-station"),
        )
    }

    // --- AC#3: clearSave wipes every durable game-state table ---

    @Test
    fun `clearSave wipes a rich multi-table save so no state survives`() {
        val repo = newGameStateRepository()
        repo.saveGameState(richState())
        assertTrue("precondition: the rich save is present", repo.hasSave())
        assertTrue("precondition: the rich save reloads", newGameStateRepository().loadGameState() != null)

        repo.clearSave()

        // A fresh repository over the same connection == an app restart; the read goes back through SQL.
        assertNull("AC#3: after the wipe there is no usable save (no stale rows in any table)", newGameStateRepository().loadGameState())
        assertFalse("AC#3: hasSave() is false after the wipe", newGameStateRepository().hasSave())
    }

    // --- AC#3: the wipe is destructive even for the header-only quick check ---

    @Test
    fun `clearSave removes the save header so hasSave reports false immediately`() {
        val repo = newGameStateRepository()
        repo.saveGameState(richState())

        repo.clearSave()

        assertFalse("the game_state header row is gone", repo.hasSave())
    }

    // --- UC21: settings (handedness) + meta (save version) survive a wipe ---

    @Test
    fun `clearSave keeps the player's handedness setting intact`() {
        val settings = newSettingsRepository()
        settings.ensureInitialized()
        // Store a NON-default handedness so we can prove it is the stored value that survives, not the
        // fallback default.
        settings.saveHandedness(Handedness.LEFT_HANDED)
        assertEquals("precondition: handedness stored", Handedness.LEFT_HANDED, settings.loadHandedness())

        newGameStateRepository().saveGameState(richState())
        newGameStateRepository().clearSave()

        assertEquals(
            "UC21: a save wipe resets progress only — the handedness preference is preserved",
            Handedness.LEFT_HANDED,
            newSettingsRepository().loadHandedness(),
        )
    }

    @Test
    fun `clearSave keeps the meta save-format version intact (no migration re-run)`() {
        newSettingsRepository().ensureInitialized()
        val before = database.orbitalFrontierQueries.selectSaveVersion().executeAsOne()

        newGameStateRepository().saveGameState(richState())
        newGameStateRepository().clearSave()

        val after = database.orbitalFrontierQueries.selectSaveVersion().executeAsOne()
        assertEquals("UC21: meta (save_version) must survive the wipe so no migration re-runs", before, after)
        assertEquals("the version is the schema's current version", OrbitalFrontier.Schema.version, after)
    }

    // --- UC21: a brand-new-game Start calls clearSave() unconditionally, even with nothing to wipe ---

    @Test
    fun `clearSave on an empty database is a safe no-op that does not throw or log an error`() {
        val repo = newGameStateRepository()
        assertFalse("precondition: nothing is saved", repo.hasSave())

        repo.clearSave()

        assertFalse("an empty DB stays empty after a no-op wipe", repo.hasSave())
        assertNull("still no save", newGameStateRepository().loadGameState())
        assertTrue("a no-op wipe must not log an error (graceful, silent on empty)", logger.errors.isEmpty())
    }

    @Test
    fun `a fresh game can be saved after a wipe (the slot is reusable)`() {
        val repo = newGameStateRepository()
        repo.saveGameState(richState())
        repo.clearSave()

        // Mirrors OrbitalFrontierGame.onStartNewGame: wipe, then seed a brand-new world.
        val fresh = WorldState(currentSector = SectorId("alpha"), credits = 50_000L)
        repo.saveGameState(fresh)

        val reloaded = newGameStateRepository().loadGameState()
        assertEquals("the post-wipe fresh game persists with no leftover state from the old save", fresh, reloaded)
        assertEquals("only the fresh single-starter fleet remains", 1, reloaded!!.fleet.ships.size)
        assertTrue("no leftover owned stations from the wiped save", reloaded.stations.isEmpty)
        assertTrue("no leftover accepted missions from the wiped save", reloaded.missions.accepted.isEmpty())
        assertEquals("no leftover reputation from the wiped save", Reputation.EMPTY, reloaded.reputation)
        assertTrue("no leftover field depletion", reloaded.fieldDepletion.isEmpty())
        assertTrue("no leftover revealed contacts", reloaded.revealedContacts.isEmpty())
    }

    /** Logger that records WARN/ERROR messages so error-path assertions can inspect them. */
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
