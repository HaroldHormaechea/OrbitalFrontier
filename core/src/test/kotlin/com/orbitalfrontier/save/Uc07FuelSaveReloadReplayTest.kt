package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.playthrough.PlaythroughFixtures
import com.orbitalfrontier.playthrough.PlaythroughResources
import com.orbitalfrontier.playthrough.ReplayRunner
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "burn fuel → save → reload" test (UC07 AC#6): replay the committed `uc07-low-fuel`
 * artifact through the pure [ReplayRunner], map the resulting [SimulationState] (including its burned-
 * down [com.orbitalfrontier.economy.Fuel]) to the production [WorldState], persist it through
 * [SqlDelightGameStateRepository] (schema v5 with the `ship.fuel` column), reload it, and assert the
 * restored state is **exactly** equal — including [WorldState.fuel].
 *
 * This ties the deterministic record/replay harness to the v5 save path: the active ship's fuel level
 * is part of game state and survives a save → reload with no drift (the Float→Double→Float boundary is
 * exact), the same way [Uc06MiningSaveReloadReplayTest] proved for cargo + field depletion. Capacity is
 * a ship stat reconstructed from [FuelParams.DEFAULT_TANK_CAPACITY] on load, not persisted.
 */
class Uc07FuelSaveReloadReplayTest {
    private val fuelParams = FuelParams()

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
            ship = state.ship,
            dockedStation = state.dockedStation,
            cargo = state.cargo,
            fieldDepletion = state.fieldDepletion,
            fuel = state.fuel,
        )

    @Test
    fun `a replayed low-fuel playthrough saves the fuel level and reloads it exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC07_LOW_FUEL)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually burned the tank into the low-fuel regime (a non-
        // trivial, non-full fuel level to persist) yet did not strand the ship.
        assertTrue("precondition: the replay should have burned below the threshold", finalState.fuel.isLow(fuelParams))
        assertTrue("precondition: the replay should retain some fuel", finalState.fuel.level > 0f)
        assertTrue("precondition: the tank should not be full", finalState.fuel.level < FuelParams.DEFAULT_TANK_CAPACITY)

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // EXACT equality — sector + kinematics + cargo + field depletion + fuel (AC#6).
        assertEquals(replayedWorld, reloaded)
        assertEquals("fuel must survive the save/reload", replayedWorld.fuel, reloaded?.fuel)
        assertEquals(
            "the fuel level must round-trip exactly through the Float<->Double boundary",
            replayedWorld.fuel.level,
            reloaded?.fuel?.level,
        )
        assertEquals(
            "capacity is reconstructed as a ship stat, not persisted",
            FuelParams.DEFAULT_TANK_CAPACITY,
            reloaded?.fuel?.capacity,
        )
    }
}
