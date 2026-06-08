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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "take combat damage → save → reload" test (UC13 AC#5): replay the committed `uc13-combat`
 * artifact through the pure [ReplayRunner] — leaving the active ship with recorded per-section damage and
 * a recorded last-docked station — map the resulting [SimulationState] to the production [WorldState],
 * persist it through [SqlDelightGameStateRepository] (schema v11, the `ship_section_damage` table +
 * `game_state.last_docked_station_id` column), reload it, and assert the restored state is **exactly**
 * equal: the durable section damage and the respawn point both survive a save → reload.
 *
 * This ties the deterministic record/replay harness to the v11 save path: damage taken in a fight, and
 * the station the player will respawn at, persist across an app restart (AC#5), the same way
 * [Uc11CrewSaveReloadReplayTest] proved for crew. The **transient** combat (hostiles/projectiles/RNG) is
 * deliberately NOT persisted (ADR 0012) — and at the end of this playthrough the encounter has already
 * cleared, so the reloaded combat is [com.orbitalfrontier.combat.CombatState.NONE] either way.
 */
class Uc13CombatSaveReloadReplayTest {
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
            missions = state.missions,
            // UC13: the durable combat state — last docked station (combat itself is transient, not saved).
            lastDockedStation = state.lastDockedStation,
        )

    @Test
    fun `replayed combat damage and the last docked station save and reload exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC13_COMBAT)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Preconditions: the fight left recorded section damage on the active ship, the encounter cleared,
        // and the last docked station is set (the respawn point) — so we persist non-trivial combat state.
        assertFalse("precondition: the ship took recorded section damage", finalState.fleet.active.sectionDamage.isEmpty())
        assertFalse("precondition: the encounter cleared", finalState.combat.active)
        assertEquals("precondition: the last docked station is recorded", PoiId("alpha-station"), finalState.lastDockedStation)

        val replayedWorld = worldStateFrom(finalState)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        assertNotNull("the combat-damaged save must reload", reloaded)
        // EXACT equality — section damage + lastDockedStation survive alongside the fleet + sector (AC#5).
        assertEquals(replayedWorld, reloaded)
        assertEquals(
            "the per-section damage survives the round-trip",
            finalState.fleet.active.sectionDamage,
            reloaded!!.fleet.active.sectionDamage,
        )
        assertEquals("the last docked station survives the round-trip", PoiId("alpha-station"), reloaded.lastDockedStation)
    }
}
