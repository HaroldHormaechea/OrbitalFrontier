package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.playthrough.PlaythroughFixtures
import com.orbitalfrontier.playthrough.PlaythroughResources
import com.orbitalfrontier.playthrough.ReplayRunner
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end "trade → save → reload" test for the dynamic per-station market pressure (UC46 AC#3):
 * replay the committed `uc46-dynamic-pricing` artifact through the pure [ReplayRunner], map the
 * resulting [SimulationState] (including its accumulated [WorldState.marketState] supply/demand
 * pressure) to the production [WorldState], persist it through [SqlDelightGameStateRepository] (schema
 * v20 with the `station_market` table), reload it from a fresh repository over the same DB (== app
 * restart), and assert the restored state — and specifically the market pressure — is **exactly** equal.
 *
 * This ties the deterministic record/replay harness to the v20 save path: the mutable part of the
 * economy survives a save → reload with no drift, the same way [Uc08TradeSaveReloadReplayTest] proved
 * for the wallet and [ReputationPersistenceTest] for standings. Only NON-ZERO pressure is stored (the
 * cargo / reputation snapshot contract); a fresh game with no trades reads back [marketState] EMPTY.
 */
class Uc46DynamicPricingSaveReloadReplayTest {
    private val alphaStation = PoiId("alpha-station")

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

    private fun repo() = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)

    /** The production game-state value the save layer persists — carrying the dynamic market pressure. */
    private fun worldStateFrom(state: SimulationState): WorldState =
        WorldState(
            currentSector = state.currentSector,
            fleet = state.fleet,
            dockedStation = state.dockedStation,
            fieldDepletion = state.fieldDepletion,
            credits = state.credits,
            marketState = state.marketState,
        )

    @Test
    fun `a replayed dynamic-pricing playthrough saves the market pressure and reloads it exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC46_DYNAMIC_PRICING)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually accrued non-zero supply/demand pressure to persist.
        val pressure = finalState.marketState.pressureFor(alphaStation)[ResourceType.TITANIUM]
        assertNotNull("precondition: the sales should have left some Titanium pressure at Alpha Station", pressure)
        assertTrue("precondition: the accrued pressure is non-zero", (pressure ?: 0) != 0)

        val replayedWorld = worldStateFrom(finalState)

        repo().saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = repo().loadGameState()

        assertNotNull(reloaded)
        // EXACT equality — sector + kinematics + dock + cargo + credits + the dynamic market pressure (AC#3).
        assertEquals(replayedWorld, reloaded)
        assertEquals("the market pressure survives the save/reload", replayedWorld.marketState, reloaded!!.marketState)
        assertEquals(
            "the Titanium pressure at Alpha Station round-trips exactly",
            pressure,
            reloaded.marketState.pressureFor(alphaStation)[ResourceType.TITANIUM],
        )
    }

    @Test
    fun `a fresh game with no trades reads back an EMPTY market state`() {
        repo().saveGameState(WorldState())

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertTrue(
            "a never-traded save persists no station_market rows ⇒ reads back EMPTY",
            reloaded!!.marketState.pressureByStation.isEmpty(),
        )
    }
}
