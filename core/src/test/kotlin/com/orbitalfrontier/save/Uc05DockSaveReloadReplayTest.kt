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
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "save while docked → reload docked" test (UC05 AC#4): replay the committed `uc05-dock`
 * artifact through the pure [ReplayRunner], map the resulting docked [SimulationState] to the
 * production [WorldState], persist it through [SqlDelightGameStateRepository] (schema v3 with the
 * `docked_station_id` column), reload it, and assert the restored state is **exactly** equal —
 * including [WorldState.dockedStation].
 *
 * This ties the deterministic record/replay harness to the v3 save path: the dock state is part of
 * game state and survives a save → reload with no drift, the same way UC04's
 * [Uc04SaveReloadReplayTest] proved for ship kinematics + sector.
 */
class Uc05DockSaveReloadReplayTest {
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
        WorldState(currentSector = state.currentSector, fleet = state.fleet, dockedStation = state.dockedStation)

    @Test
    fun `a replayed dock playthrough saves while docked and reloads docked`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC05_DOCK)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually ended docked (so we persist a non-trivial dock state).
        assertEquals(
            "precondition: the replay should have ended docked at alpha-station",
            PoiId("alpha-station"),
            finalState.dockedStation,
        )

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock).loadGameState()

        // EXACT equality — sector + every ship kinematic field + the docked station (AC#4).
        assertEquals(replayedWorld, reloaded)
        assertEquals("the docked station must survive the save/reload", PoiId("alpha-station"), reloaded?.dockedStation)
    }
}
