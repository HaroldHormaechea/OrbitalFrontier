package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.playthrough.PlaythroughFixtures
import com.orbitalfrontier.playthrough.PlaythroughResources
import com.orbitalfrontier.playthrough.ReplayRunner
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "outfit + buy a ship → save → reload" test (UC09 AC#6): replay the committed
 * `uc09-outfit` artifact through the pure [ReplayRunner], map the resulting multi-ship
 * [SimulationState] (two ships, the starter carrying an installed engine, the Swift active) to the
 * production [WorldState], persist it through [SqlDelightGameStateRepository] (schema v7 with the
 * `ship.ship_type` column + `ship_upgrade` table), reload it, and assert the restored state is
 * **exactly** equal — the whole fleet, every loadout, and the active-ship selection.
 *
 * This ties the deterministic record/replay harness to the v7 save path: all owned ships and their
 * loadouts survive a save → reload with no drift (AC#6), the same way [Uc08TradeSaveReloadReplayTest]
 * proved for the wallet. Cargo/fuel capacities are ship stats re-derived from type + loadout on load,
 * not persisted, so the reload still compares exactly equal.
 */
class Uc09OutfitSaveReloadReplayTest {
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

    /** The production game-state value the save layer persists, derived from a replayed snapshot. */
    private fun worldStateFrom(state: SimulationState): WorldState =
        WorldState(
            currentSector = state.currentSector,
            fleet = state.fleet,
            dockedStation = state.dockedStation,
            fieldDepletion = state.fieldDepletion,
            credits = state.credits,
        )

    @Test
    fun `a replayed outfit playthrough saves the whole fleet and reloads it exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC09_OUTFIT)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually grew the fleet, installed an engine, and switched —
        // so we persist a non-trivial multi-ship, with-loadout state.
        assertEquals("precondition: the fleet grew to two ships", 2, finalState.fleet.ships.size)
        assertEquals("precondition: the Swift is active", ShipId(1), finalState.fleet.activeShipId)
        assertEquals(
            "precondition: the starter carries its installed engine",
            1,
            finalState.fleet.ship(OwnedShip.STARTER_SHIP_ID)!!.loadout.installedCount(SlotCategory.ENGINES),
        )

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // EXACT equality — every ship (kinematics + type + cargo + fuel + loadout) + active id (AC#6).
        assertEquals(replayedWorld, reloaded)

        // Spot-check the fleet shape survived in detail.
        val reloadedFleet = reloaded!!.fleet
        assertEquals("both ships reload", 2, reloadedFleet.ships.size)
        assertEquals("the active ship survives as the Swift", ShipId(1), reloadedFleet.activeShipId)
        assertEquals(ShipRoster.SWIFT.id, reloadedFleet.active.type.id)
        assertEquals(
            "the starter's installed engine survives the round-trip",
            1,
            reloadedFleet.ship(OwnedShip.STARTER_SHIP_ID)!!.loadout.installedCount(SlotCategory.ENGINES),
        )
    }
}
