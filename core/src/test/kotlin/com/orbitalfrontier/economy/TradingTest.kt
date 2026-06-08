package com.orbitalfrontier.economy

import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Trading] (UC08 AC#3/#4/#5/#6) — pure, deterministic inter-station trading.
 *
 * Trading is engine-free and side-effect-free, so these are plain JVM unit tests (AC#6). They pin the
 * contract the trade desk (and the sim/replay path) relies on:
 *  - **buy** is bounded by the requested amount, the wallet (`credits / buyPrice`), AND the cargo
 *    space all at once — partial fills when any bound bites, a no-op when none can move (AC#3);
 *  - **sell** is bounded by the units held, adds `sellable * sellPrice` credits and removes the cargo
 *    (AC#3);
 *  - it is a **no-op** (inputs returned unchanged, `tradedUnits = 0`, `kind = null`) for
 *    [TradeOrder.None], a null market, a not-offered resource, or nothing to move;
 *  - a balance beyond `Int` range is handled without overflow (money math is `Long`);
 *  - **cross-station arbitrage** (buy low at one station, sell high at another) actually profits using
 *    the authored [MvpSectorMap] markets (AC#4);
 *  - **buy hydrogen → refuel** composes: bought Hydrogen feeds [Refueling.resolve] and raises fuel (AC#5).
 */
class TradingTest {
    private val ironOffer = TradeOffer(buyPrice = 10, sellPrice = 8)
    private val market = StationMarket(mapOf(ResourceType.IRON_ORE to ironOffer))

    private fun cargo(
        contents: Map<ResourceType, Int> = emptyMap(),
        capacity: Int = 50,
    ): Cargo = Cargo(contents, capacity)

    // --- BUY: bounded by credits, capacity, and the requested amount ---

    @Test
    fun `buy is bounded by the wallet (partial fill when credits run short)`() {
        // credits 25 / buyPrice 10 = 2 whole units affordable, though 10 were requested and the hold fits more.
        val result = Trading.resolve(credits = 25, cargo = cargo(), market = market, order = TradeOrder.Buy(ResourceType.IRON_ORE, 10))

        assertEquals(TradeKind.BUY, result.kind)
        assertEquals("only 2 units are affordable", 2, result.tradedUnits)
        assertEquals("credits drop by 2 * 10", 5L, result.credits)
        assertEquals("2 iron land in the hold", 2, result.cargo.contents[ResourceType.IRON_ORE])
    }

    @Test
    fun `buy is bounded by the cargo space (partial fill when the hold is nearly full)`() {
        // Hold capacity 5 with 3 already held ⇒ only 2 free, though credits + request allow far more.
        val nearlyFull = cargo(mapOf(ResourceType.IRON_ORE to 3), capacity = 5)
        val result = Trading.resolve(credits = 1000, cargo = nearlyFull, market = market, order = TradeOrder.Buy(ResourceType.IRON_ORE, 10))

        assertEquals("only the 2 free slots are filled", 2, result.tradedUnits)
        assertEquals("credits drop by 2 * 10", 980L, result.credits)
        assertEquals("the hold ends full at 5", 5, result.cargo.contents[ResourceType.IRON_ORE])
        assertTrue(result.cargo.isFull)
    }

    @Test
    fun `buy is bounded by the requested amount (when wallet and hold allow more)`() {
        val result = Trading.resolve(credits = 1000, cargo = cargo(), market = market, order = TradeOrder.Buy(ResourceType.IRON_ORE, 3))

        assertEquals("exactly the 3 requested are bought", 3, result.tradedUnits)
        assertEquals(970L, result.credits)
        assertEquals(3, result.cargo.contents[ResourceType.IRON_ORE])
    }

    @Test
    fun `buy is a no-op when even one unit is unaffordable`() {
        val emptyHold = cargo()
        val result = Trading.resolve(credits = 5, cargo = emptyHold, market = market, order = TradeOrder.Buy(ResourceType.IRON_ORE, 10))

        assertEquals(0, result.tradedUnits)
        assertNull("no side traded", result.kind)
        assertEquals("credits unchanged", 5L, result.credits)
        assertSame("cargo returned unchanged", emptyHold, result.cargo)
    }

    @Test
    fun `buy is a no-op when the hold is already full`() {
        val full = cargo(mapOf(ResourceType.IRON_ORE to 5), capacity = 5)
        val result = Trading.resolve(credits = 1000, cargo = full, market = market, order = TradeOrder.Buy(ResourceType.IRON_ORE, 10))

        assertEquals(0, result.tradedUnits)
        assertNull(result.kind)
        assertEquals(1000L, result.credits)
        assertSame(full, result.cargo)
    }

    @Test
    fun `buying a non-positive quantity is a no-op`() {
        val emptyHold = cargo()
        val result = Trading.resolve(credits = 1000, cargo = emptyHold, market = market, order = TradeOrder.Buy(ResourceType.IRON_ORE, 0))

        assertEquals(0, result.tradedUnits)
        assertSame(emptyHold, result.cargo)
        assertEquals(1000L, result.credits)
    }

    // --- SELL: bounded by units held; adds credits and removes cargo ---

    @Test
    fun `sell adds credits and removes the sold cargo`() {
        val hold = cargo(mapOf(ResourceType.IRON_ORE to 5))
        val result = Trading.resolve(credits = 100, cargo = hold, market = market, order = TradeOrder.Sell(ResourceType.IRON_ORE, 3))

        assertEquals(TradeKind.SELL, result.kind)
        assertEquals(3, result.tradedUnits)
        assertEquals("credits rise by 3 * 8", 124L, result.credits)
        assertEquals("the hold drops to 2 iron", 2, result.cargo.contents[ResourceType.IRON_ORE])
    }

    @Test
    fun `sell is bounded by the units held (selling more than held sells only what is held)`() {
        val hold = cargo(mapOf(ResourceType.IRON_ORE to 2))
        val result = Trading.resolve(credits = 100, cargo = hold, market = market, order = TradeOrder.Sell(ResourceType.IRON_ORE, 10))

        assertEquals("only the 2 held are sold", 2, result.tradedUnits)
        assertEquals("credits rise by 2 * 8", 116L, result.credits)
        assertNull("the iron is fully sold, so the key is dropped", result.cargo.contents[ResourceType.IRON_ORE])
    }

    @Test
    fun `selling a resource the hold does not carry is a no-op`() {
        val hold = cargo(mapOf(ResourceType.COPPER to 5))
        val result = Trading.resolve(credits = 100, cargo = hold, market = market, order = TradeOrder.Sell(ResourceType.IRON_ORE, 5))

        assertEquals(0, result.tradedUnits)
        assertNull(result.kind)
        assertEquals(100L, result.credits)
        assertSame(hold, result.cargo)
    }

    // --- no-ops: None, null market, not-offered ---

    @Test
    fun `a None order returns the inputs unchanged`() {
        val hold = cargo(mapOf(ResourceType.IRON_ORE to 5))
        val result = Trading.resolve(credits = 100, cargo = hold, market = market, order = TradeOrder.None)

        assertEquals(0, result.tradedUnits)
        assertNull(result.kind)
        assertEquals(100L, result.credits)
        assertSame(hold, result.cargo)
    }

    @Test
    fun `a null market is a no-op (trading is gated on a docked station)`() {
        val hold = cargo(mapOf(ResourceType.IRON_ORE to 5))
        val result = Trading.resolve(credits = 100, cargo = hold, market = null, order = TradeOrder.Sell(ResourceType.IRON_ORE, 5))

        assertEquals(0, result.tradedUnits)
        assertEquals(100L, result.credits)
        assertSame(hold, result.cargo)
    }

    @Test
    fun `buying a resource not offered at this station is a no-op`() {
        val emptyHold = cargo()
        // The market only lists IRON_ORE; TITANIUM is not offered here.
        val result = Trading.resolve(credits = 1000, cargo = emptyHold, market = market, order = TradeOrder.Buy(ResourceType.TITANIUM, 5))

        assertEquals(0, result.tradedUnits)
        assertEquals(1000L, result.credits)
        assertSame(emptyHold, result.cargo)
    }

    // --- large-balance overflow guard: money math is Long ---

    @Test
    fun `a balance beyond Int range is handled without overflow`() {
        // credits / buyPrice = 3_000_000_000 (> Int.MAX_VALUE) and the request is Int.MAX_VALUE, so the
        // capacity (100 free) is the binding bound. The resolver mins as Long BEFORE narrowing to Int, so
        // it picks 100 — an all-Int computation would overflow the credits/buyPrice term and mis-clamp.
        val hugeBalance = 3_000_000_000L
        val cheap = StationMarket(mapOf(ResourceType.IRON_ORE to TradeOffer(buyPrice = 1, sellPrice = 1)))
        val result =
            Trading.resolve(
                credits = hugeBalance,
                cargo = cargo(capacity = 100),
                market = cheap,
                order = TradeOrder.Buy(ResourceType.IRON_ORE, Int.MAX_VALUE),
            )

        assertEquals("bounded by the 100 free slots, never an overflowed Int", 100, result.tradedUnits)
        assertEquals("credits drop by exactly 100 * 1, staying well above Int range", hugeBalance - 100L, result.credits)
        assertEquals(100, result.cargo.contents[ResourceType.IRON_ORE])
    }

    @Test
    fun `selling at a high price keeps a beyond-Int balance exact`() {
        val hold = cargo(mapOf(ResourceType.IRON_ORE to 1000))
        val richMarket = StationMarket(mapOf(ResourceType.IRON_ORE to TradeOffer(buyPrice = 10_000_000, sellPrice = 10_000_000)))
        val result = Trading.resolve(credits = 0, cargo = hold, market = richMarket, order = TradeOrder.Sell(ResourceType.IRON_ORE, 1000))

        // 1000 * 10_000_000 = 10_000_000_000 > Int.MAX_VALUE — only correct with Long math.
        assertEquals(10_000_000_000L, result.credits)
        assertEquals(1000, result.tradedUnits)
    }

    // --- AC#4: cross-station arbitrage profits using the authored MVP markets ---

    private val world = MvpSectorMap.build()
    private val alphaMarket = world.sector(SectorId("alpha")).station(PoiId("alpha-station"))!!.market
    private val betaMarket = world.sector(SectorId("beta")).station(PoiId("beta-station"))!!.market

    @Test
    fun `buy iron at Alpha and sell it at Beta turns a profit`() {
        // Alpha buys IRON_ORE out at 10/unit; Beta pays 15/unit to take it — a 5/unit arbitrage (AC#4).
        val units = 5
        val startCredits = 1000L

        val bought =
            Trading.resolve(
                credits = startCredits,
                cargo = cargo(),
                market = alphaMarket,
                order = TradeOrder.Buy(ResourceType.IRON_ORE, units),
            )
        assertEquals(units, bought.tradedUnits)
        assertEquals("paid 5 * 10 at Alpha", startCredits - units * 10L, bought.credits)

        val sold =
            Trading.resolve(
                credits = bought.credits,
                cargo = bought.cargo,
                market = betaMarket,
                order = TradeOrder.Sell(ResourceType.IRON_ORE, units),
            )
        assertEquals(units, sold.tradedUnits)

        assertEquals("net profit of 5/unit on the Alpha->Beta iron run", startCredits + units * 5L, sold.credits)
        assertTrue("the round trip ended richer than it started", sold.credits > startCredits)
        assertNull("all the iron was sold at Beta", sold.cargo.contents[ResourceType.IRON_ORE])
    }

    @Test
    fun `buy titanium at Beta and sell it at Alpha turns a profit (the opposite leg)`() {
        // Beta buys TITANIUM out at 40/unit; Alpha pays 50/unit — a 10/unit arbitrage the other way (AC#4).
        val units = 3
        val startCredits = 1000L

        val bought =
            Trading.resolve(
                credits = startCredits,
                cargo = cargo(),
                market = betaMarket,
                order = TradeOrder.Buy(ResourceType.TITANIUM, units),
            )
        assertEquals(units, bought.tradedUnits)
        assertEquals(startCredits - units * 40L, bought.credits)

        val sold =
            Trading.resolve(
                credits = bought.credits,
                cargo = bought.cargo,
                market = alphaMarket,
                order = TradeOrder.Sell(ResourceType.TITANIUM, units),
            )
        assertEquals("net profit of 10/unit on the Beta->Alpha titanium run", startCredits + units * 10L, sold.credits)
        assertTrue(sold.credits > startCredits)
    }

    // --- AC#5: buy hydrogen, then refuel converts it into fuel ---

    @Test
    fun `buying hydrogen then refuelling raises fuel (AC#5 composition)`() {
        val tank = Fuel(level = 50f, capacity = 100f)
        val fuelParams = FuelParams() // ratio 1.0

        // Buy 10 hydrogen at Alpha (buyPrice 6) into an empty hold.
        val bought =
            Trading.resolve(
                credits = 100,
                cargo = cargo(),
                market = alphaMarket,
                order = TradeOrder.Buy(ResourceType.HYDROGEN, 10),
            )
        assertEquals(10, bought.tradedUnits)
        assertEquals("paid 10 * 6 for hydrogen", 40L, bought.credits)
        assertEquals(10, bought.cargo.contents[ResourceType.HYDROGEN])

        // The existing hub REFUEL converts that bought hydrogen into fuel — no trading special-case needed.
        val refuelled = Refueling.resolve(tank, bought.cargo, RefuelAction.REFUEL, fuelParams)
        assertEquals("all 10 bought hydrogen are converted", 10, refuelled.transferredUnits)
        assertEquals("the tank rises by 10 fuel", 60f, refuelled.fuel.level)
        assertTrue("the bought hydrogen is drained from the hold", refuelled.cargo.contents[ResourceType.HYDROGEN] == null)
    }
}
