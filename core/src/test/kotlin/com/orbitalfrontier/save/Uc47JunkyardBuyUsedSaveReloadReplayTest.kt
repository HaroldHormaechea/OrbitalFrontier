package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.outfit.UpgradeCatalog
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
 * End-to-end "buy used → save → reload" test for the junkyard buy-used depletion (UC47 AC#3):
 * replay the committed `uc47-buy-used-part` artifact through the pure [ReplayRunner], map the resulting
 * [SimulationState] (including its accumulated [WorldState.junkyardStock] depletion) to the production
 * [WorldState], persist it through [SqlDelightGameStateRepository] (schema v21 with the `junkyard_stock`
 * table), reload it from a fresh repository over the same DB (== app restart), and assert the restored
 * state — and specifically the depletion — is **exactly** equal.
 *
 * This is the anti-restock-exploit proof: the player's purchase is durable. A reload reads the depletion
 * back unchanged — it does NOT replenish the junkyard — so `available = baseline − purchased` stays
 * decremented across restarts. Modelled on [Uc46DynamicPricingSaveReloadReplayTest] (which proved it for
 * the dynamic market pressure); only NON-ZERO purchases are stored, so a never-bought game reads back
 * [WorldState.junkyardStock] EMPTY.
 */
class Uc47JunkyardBuyUsedSaveReloadReplayTest {
    private val gammaJunkyard = PoiId("gamma-junkyard")
    private val engine = UpgradeCatalog.ENGINE_TUNE_I

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

    /** The production game-state value the save layer persists — carrying the junkyard depletion. */
    private fun worldStateFrom(state: SimulationState): WorldState =
        WorldState(
            currentSector = state.currentSector,
            fleet = state.fleet,
            dockedStation = state.dockedStation,
            fieldDepletion = state.fieldDepletion,
            credits = state.credits,
            marketState = state.marketState,
            junkyardStock = state.junkyardStock,
        )

    @Test
    fun `a replayed buy-used playthrough saves the depletion and reloads it exactly`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC47_BUY_USED)
        val finalState = ReplayRunner().run(playthrough).finalState

        // Precondition: the recorded run actually bought a used part, leaving non-zero depletion to persist.
        val purchased = finalState.junkyardStock.purchasedCount(gammaJunkyard, engine)
        assertTrue("precondition: the buy-used should have depleted the junkyard by one", purchased == 1)

        val replayedWorld = worldStateFrom(finalState)

        repo().saveGameState(replayedWorld)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = repo().loadGameState()

        assertNotNull(reloaded)
        // EXACT equality — sector + kinematics + dock + cargo + credits + fleet loadouts + the depletion (AC#3).
        assertEquals(replayedWorld, reloaded)
        assertEquals("the junkyard depletion survives the save/reload", replayedWorld.junkyardStock, reloaded!!.junkyardStock)
        assertEquals(
            "the purchased count at the Gamma Junkyard round-trips exactly",
            1,
            reloaded.junkyardStock.purchasedCount(gammaJunkyard, engine),
        )
    }

    @Test
    fun `a reload does NOT restock the junkyard (the purchase stays durable)`() {
        val playthrough = PlaythroughResources.load(PlaythroughFixtures.UC47_BUY_USED)
        val finalState = ReplayRunner().run(playthrough).finalState
        val saved = worldStateFrom(finalState)

        repo().saveGameState(saved)

        // Reload twice (two app restarts in a row): the depletion is recomputed from rows each time and must
        // never replenish — the purchased count is monotonic across reloads, the anti-restock-exploit core.
        val firstReload = repo().loadGameState()!!
        repo().saveGameState(firstReload)
        val secondReload = repo().loadGameState()!!

        assertEquals(
            "reload #1 keeps the purchase (no restock)",
            1,
            firstReload.junkyardStock.purchasedCount(gammaJunkyard, engine),
        )
        assertEquals(
            "reload #2 still keeps the purchase (no restock)",
            1,
            secondReload.junkyardStock.purchasedCount(gammaJunkyard, engine),
        )
        assertEquals("both reloads carry an identical depletion", firstReload.junkyardStock, secondReload.junkyardStock)
    }

    @Test
    fun `a fresh game with no buy-used reads back an EMPTY junkyard stock`() {
        repo().saveGameState(WorldState())

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertTrue(
            "a never-bought-used save persists no junkyard_stock rows ⇒ reads back EMPTY",
            reloaded!!.junkyardStock.purchasedByStation.isEmpty(),
        )
    }
}
