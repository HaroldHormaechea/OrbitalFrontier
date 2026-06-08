package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
 * End-to-end "trade → save → reload" test (UC08 AC#1): replay the committed `uc08-trade` artifact
 * through the pure [ReplayRunner], map the resulting [SimulationState] (including its post-sale
 * [WorldState.credits]) to the production [WorldState], persist it through
 * [SqlDelightGameStateRepository] (schema v6 with the `game_state.credits` column), reload it, and
 * assert the restored state is **exactly** equal — including the credit balance.
 *
 * This ties the deterministic record/replay harness to the v6 save path: the player's wallet is part
 * of game state and survives a save → reload with no drift, the same way [Uc07FuelSaveReloadReplayTest]
 * proved for fuel and [Uc06MiningSaveReloadReplayTest] for cargo + field depletion. Credits are
 * save-wide (not per-ship), persisted on the `game_state` header.
 */
class Uc08TradeSaveReloadReplayTest {
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
    fun `a replayed trade playthrough saves the credit balance and reloads it exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC08_TRADE)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually sold cargo for a non-trivial, non-zero balance to persist.
        assertTrue(
            "precondition: the sale should have raised credits above the starting wallet",
            finalState.credits > PlaythroughFixtures.UC08_STARTING_CREDITS,
        )

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // EXACT equality — sector + kinematics + dock + cargo + field depletion + fuel + credits (AC#1).
        assertEquals(replayedWorld, reloaded)
        assertEquals("credits must survive the save/reload", replayedWorld.credits, reloaded?.credits)
    }
}
