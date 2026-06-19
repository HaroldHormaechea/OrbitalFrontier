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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "scan → save → reload" test (UC10 AC#4): replay the committed `uc10-scan` artifact
 * through the pure [ReplayRunner], map the resulting [SimulationState] (with a non-empty
 * `revealedContacts`) to the production [WorldState], persist it through
 * [SqlDelightGameStateRepository] (schema v8 with the `revealed_contact` table), reload it, and assert
 * the restored state is **exactly** equal — the revealed hidden contacts survive a save → reload.
 *
 * This ties the deterministic record/replay harness to the v8 save path: a contact revealed by an
 * active scan stays known across an app restart (AC#4 — "revealed contacts persist"), the same way
 * [Uc09OutfitSaveReloadReplayTest] proved for the fleet. Reveal is monotonic, so the reload compares
 * exactly equal with no drift.
 */
class Uc10ScanSaveReloadReplayTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier

    private val derelict = PoiId("alpha-derelict")
    private val ghost = PoiId("alpha-ghost")

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
    fun `a replayed scan saves the revealed contacts and reloads them exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC10_SCAN)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded scan actually revealed the derelict (and only it) — so we persist a
        // non-trivial, non-empty revealed set.
        assertEquals("precondition: the scan revealed exactly the derelict", setOf(derelict), finalState.revealedContacts)

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock).loadGameState()

        // EXACT equality — revealedContacts survive the round-trip alongside the fleet + sector (AC#4).
        assertEquals(replayedWorld, reloaded)

        // Spot-check the revealed set survived in detail.
        assertTrue("the revealed derelict survives the round-trip", derelict in reloaded!!.revealedContacts)
        assertFalse("the never-scanned ghost stays hidden after reload", ghost in reloaded.revealedContacts)
        assertEquals("exactly one contact stays revealed", 1, reloaded.revealedContacts.size)
    }
}
