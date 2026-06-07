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
 * End-to-end persistence-of-a-playthrough test (UC04 AC#8): replay the committed `uc03-jump`
 * artifact through the pure [ReplayRunner], map the resulting [SimulationState] to the production
 * [WorldState], persist it through [SqlDelightGameStateRepository], reload it, and assert the
 * restored state is **exactly** equal to the replayed state.
 *
 * This ties the deterministic record/replay harness (docs/PLAYTESTING.md) to the real SQLite save
 * path: the same kinematics a recorded session produces survive a save → reload with no drift,
 * because the Float→Double→Float conversion at the persistence boundary is exact (no tolerance is
 * used here, unlike the in-replay snapshot assertions).
 */
class Uc04SaveReloadReplayTest {
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
    private fun worldStateFrom(state: SimulationState): WorldState = WorldState(currentSector = state.currentSector, ship = state.ship)

    @Test
    fun `a replayed jump playthrough saves and reloads to an exactly-equal world state`() {
        // Replay the committed uc03-jump artifact headlessly on the pure model.
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC03_JUMP)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Sanity: the recorded run actually jumped (so we are persisting a non-trivial world state).
        assertTrue(
            "precondition: the replay should have moved the ship off the origin",
            finalState.ship.position != com.orbitalfrontier.common.Vec2.ZERO,
        )

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // EXACT equality — sector + every ship kinematic field, Float→Double→Float is lossless (AC#8).
        assertEquals(replayedWorld, reloaded)
    }
}
