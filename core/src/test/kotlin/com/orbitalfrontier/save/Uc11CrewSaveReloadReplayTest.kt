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
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "hire crew → save → reload" test (UC11 AC#4): replay the committed `uc11-crew` artifact
 * through the pure [ReplayRunner], map the resulting [SimulationState] (whose active ship now carries
 * 1 crew) to the production [WorldState], persist it through [SqlDelightGameStateRepository] (schema
 * v9 with the `ship.crew` column), reload it, and assert the restored state is **exactly** equal — the
 * hired crew survives a save → reload.
 *
 * This ties the deterministic record/replay harness to the v9 save path: crew hired at a station stays
 * aboard across an app restart (AC#4 — "crew state persists"), the same way [Uc10ScanSaveReloadReplayTest]
 * proved for revealed contacts.
 */
class Uc11CrewSaveReloadReplayTest {
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
            revealedContacts = state.revealedContacts,
        )

    @Test
    fun `a replayed hire saves the crew count and reloads it exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC11_CREW)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded hire actually put one crew aboard the active ship — so we persist a
        // non-trivial, non-zero crew count.
        assertEquals("precondition: the hire put one crew aboard", 1, finalState.fleet.active.crew)

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // EXACT equality — the crew count survives the round-trip alongside the fleet + sector (AC#4).
        assertEquals(replayedWorld, reloaded)
        assertEquals("the hired crew survives the round-trip", 1, reloaded!!.fleet.active.crew)
    }
}
