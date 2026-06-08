package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.playthrough.PlaythroughFixtures
import com.orbitalfrontier.playthrough.PlaythroughResources
import com.orbitalfrontier.playthrough.ReplayRunner
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "mine → save → reload" test (UC06 AC#4/#5): replay the committed `uc06-mine` artifact
 * through the pure [ReplayRunner], map the resulting [SimulationState] (cargo + field depletion) to
 * the production [WorldState], persist it through [SqlDelightGameStateRepository] (schema v4 with the
 * `cargo` + `field_deposit` tables), reload it, and assert the restored state is **exactly** equal —
 * including [WorldState.cargo] and [WorldState.fieldDepletion].
 *
 * This ties the deterministic record/replay harness to the v4 save path: mined cargo (AC#5) and
 * asteroid-field depletion (AC#4) are part of game state and survive a save → reload with no drift,
 * the same way [Uc05DockSaveReloadReplayTest] proved for dock state.
 */
class Uc06MiningSaveReloadReplayTest {
    private val alphaBelt = PoiId("alpha-belt")

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
        )

    @Test
    fun `a replayed mining playthrough saves cargo and field depletion and reloads them exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC06_MINE)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually mined (non-trivial cargo + depletion to persist).
        assertTrue("precondition: the replay should have filled the hold", finalState.cargo.isFull)
        assertTrue(
            "precondition: the replay should have depleted the alpha-belt",
            finalState.fieldDepletion[alphaBelt]?.values?.sum()?.let { it > 0 } == true,
        )

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // EXACT equality — sector + kinematics + cargo + field depletion (AC#4/#5).
        assertEquals(replayedWorld, reloaded)
        assertEquals("cargo must survive the save/reload", replayedWorld.cargo, reloaded?.cargo)
        assertEquals(
            "field depletion must survive the save/reload",
            replayedWorld.fieldDepletion,
            reloaded?.fieldDepletion,
        )
    }
}
