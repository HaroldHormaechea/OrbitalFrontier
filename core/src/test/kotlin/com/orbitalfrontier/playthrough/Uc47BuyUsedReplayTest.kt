package com.orbitalfrontier.playthrough

import com.orbitalfrontier.outfit.JunkyardStock
import com.orbitalfrontier.outfit.Outfitting
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.outfit.UsedPartParams
import com.orbitalfrontier.outfit.UsedPartPricing
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC47 junkyard buy-used playthrough (AC#2/#3/#5), following the
 * record→replay→assert pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc47-buy-used-part` artifact starts the ship **docked at the Gamma Junkyard** holding
 * a [PlaythroughFixtures.UC47_STARTING_CREDITS] wallet and, at tick 0, issues a single
 * **BuyUsed(engine-tune-i)** order. This test replays it headlessly and pins the central UC47 claims:
 *  - the **discounted** used price was charged (round(catalog × 0.6), strictly below the new price, and
 *    strictly above the 0.5 sell-refund so a buy-then-sell loses money — no arbitrage loop);
 *  - the part was **installed** into the engine slot via the same flow a new part uses (AC#2);
 *  - the junkyard's **deterministic stock decremented** by exactly one purchased unit (AC#3);
 *  - a trailing held tick leaves the depletion byte-identical (no restock); and
 *  - the replay is **bit-for-bit deterministic** (AC#5).
 *
 * Catalog/used prices are derived from the production [UpgradeCatalog] + [UsedPartPricing] rather than
 * magic numbers. The artifact is reproduced from [PlaythroughFixtures.uc47BuyUsedPart] and guarded by
 * [PlaythroughFixtureTest].
 */
class Uc47BuyUsedReplayTest {
    private val gammaJunkyard = PoiId("gamma-junkyard")
    private val engine = UpgradeCatalog.ENGINE_TUNE_I

    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC47_BUY_USED)

    /** The used-part tuning the fixture was recorded under (pinned in its config). */
    private fun params(): UsedPartParams = load().usedPartConfig.toUsedPartParams()

    /** The authored catalog (new) price of the engine, read from the production catalog. */
    private val engineNewPrice: Long = UpgradeCatalog.MVP.upgrade(engine)!!.price

    @Test
    fun `the fixture genuinely starts docked at the junkyard with a known wallet and an empty engine slot`() {
        val initial = requireNotNull(load().initialState) { "the buy-used fixture must carry an initial snapshot" }

        assertEquals("precondition: starts docked at the Gamma Junkyard", gammaJunkyard.value, initial.dockedStation)
        assertEquals("precondition: in the Gamma sector (where the junkyard lives)", "gamma", initial.currentSector)
        assertEquals("precondition: starts with the authored wallet", PlaythroughFixtures.UC47_STARTING_CREDITS, initial.credits)

        // Precondition: the Gamma junkyard actually offers this part used, and it has baseline stock to buy.
        val junkyard =
            MvpSectorMap.build().sector(SectorId("gamma")).station(gammaJunkyard)!!
        assertTrue("precondition: the junkyard offers engine-tune-i used", junkyard.usedPartMarket.offers(engine))
        assertTrue(
            "precondition: the junkyard has at least one baseline unit in stock",
            UsedPartPricing.baselineStock(gammaJunkyard, engine, params()) >= 1,
        )
    }

    @Test
    fun `buying the used part charges the discounted price and installs the engine`() {
        val playthrough = load()
        val states = ReplayRunner().run(playthrough, capturePerTickStates = true).perTickStates

        val before = states.first()
        val afterBuy = states[1] // state right after the tick-0 BuyUsed

        // The DISCOUNTED used price was charged — exactly the data-driven curve, not the catalog price.
        val expectedUsedPrice = UsedPartPricing.usedPrice(engineNewPrice, params())
        val charged = before.credits - afterBuy.credits
        assertEquals("the discounted used price was charged", expectedUsedPrice, charged)
        assertTrue("the used price is a genuine discount on the new price", expectedUsedPrice < engineNewPrice)
        assertEquals("wallet after the buy is starting - used price", before.credits - expectedUsedPrice, afterBuy.credits)

        // The part installed via the SAME flow as a new part — one engine slot now filled with the engine.
        assertEquals(
            "one engine slot is filled after the buy",
            1,
            afterBuy.fleet.active.loadout.installedCount(SlotCategory.ENGINES),
        )
        assertEquals(
            "the installed part is the bought used engine",
            engine,
            afterBuy.fleet.active.loadout.upgradeAt(SlotCategory.ENGINES, 0),
        )
    }

    @Test
    fun `buying the used part decrements the junkyard's deterministic stock by one`() {
        val finalState = ReplayRunner().run(load()).finalState

        // Exactly one unit of depletion is recorded against (junkyard, part), so available drops by one.
        assertEquals(
            "the player bought exactly one used engine here",
            1,
            finalState.junkyardStock.purchasedCount(gammaJunkyard, engine),
        )
        val baseline = UsedPartPricing.baselineStock(gammaJunkyard, engine, params())
        val available = baseline - finalState.junkyardStock.purchasedCount(gammaJunkyard, engine)
        assertEquals("available stock = baseline - 1 after the single buy", baseline - 1, available)
        assertTrue("the depletion is non-zero (so save/reload has something to persist)", available < baseline)
    }

    @Test
    fun `a buy-used then sell-refund round-trip loses money (no arbitrage loop)`() {
        // Used buy = 0.6 of catalog; junkyard sell-refund = 0.5 of catalog. The buy fraction is strictly
        // above the refund fraction, so re-selling a just-bought used part always nets a loss — there is
        // no infinite credit loop, the invariant UsedPartParams pins by construction.
        val usedPrice = UsedPartPricing.usedPrice(engineNewPrice, params())
        val refund = (engineNewPrice * Outfitting.USED_PART_REFUND_FRACTION).toLong()
        assertTrue("buy-used cost ($usedPrice) must exceed the sell refund ($refund)", usedPrice > refund)
    }

    @Test
    fun `the trailing held tick leaves the depletion byte-identical (no restock)`() {
        val states = ReplayRunner().run(load(), capturePerTickStates = true).perTickStates

        // After the buy (state index 1) and after the trailing held tick (index 2) the depletion must be the
        // SAME instance — a None tick threads junkyardStock through untouched (the anti-restock-exploit).
        val afterBuy = states[1].junkyardStock
        val afterHeld = states[2].junkyardStock
        assertSame("a held tick must not restock or copy the depletion", afterBuy, afterHeld)
        assertEquals(1, afterHeld.purchasedCount(gammaJunkyard, engine))
    }

    @Test
    fun `a fresh game (no buy-used) carries an empty depletion`() {
        // Sanity: the EMPTY default is what every non-buying state holds, so byte-identity holds for the
        // 18 pre-UC47 fixtures (none buys used) — the depletion only ever appears once a BuyUsed lands.
        val initial = load().initialState!!.toSimulationState()
        assertEquals("the recorded initial snapshot has no depletion yet", JunkyardStock.EMPTY, initial.junkyardStock)
    }

    @Test
    fun `replay through a junkyard buy-used is deterministic`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState

        // SimulationState data-class equality covers credits + the fleet loadouts + the junkyardStock map.
        assertEquals("two replays are bit-for-bit identical", first, second)
    }
}
