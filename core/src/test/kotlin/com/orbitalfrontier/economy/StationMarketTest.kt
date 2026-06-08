package com.orbitalfrontier.economy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TradeOffer] + [StationMarket] (UC08 AC#2/#4/#6) — the pure, immutable per-station
 * trade desk.
 *
 * The market model is engine-free and deterministic, so these are plain JVM unit tests (AC#6). They
 * pin the [TradeOffer] invariant the affordability math relies on and the [StationMarket] lookup the
 * trade desk + [Trading] resolve against:
 *  - `buyPrice > 0` (a positive buy price keeps the `credits / buyPrice` division safe — never /0);
 *  - `0 <= sellPrice <= buyPrice` (a station never pays more to buy back than it charges to sell — no
 *    single-station money-printing loop; cross-station arbitrage is the intended income path, AC#4);
 *  - [StationMarket.offerFor] returns the listed offer or null (an unlisted resource is not traded);
 *  - [StationMarket.EMPTY] trades nothing.
 */
class StationMarketTest {
    // --- TradeOffer invariant: buyPrice must be positive ---

    @Test
    fun `a zero buy price is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TradeOffer(buyPrice = 0, sellPrice = 0) }
    }

    @Test
    fun `a negative buy price is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TradeOffer(buyPrice = -5, sellPrice = 0) }
    }

    // --- TradeOffer invariant: sellPrice must be in 0..buyPrice ---

    @Test
    fun `a sell price greater than the buy price is rejected (no money-printing loop)`() {
        assertThrows(IllegalArgumentException::class.java) { TradeOffer(buyPrice = 10, sellPrice = 11) }
    }

    @Test
    fun `a negative sell price is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TradeOffer(buyPrice = 10, sellPrice = -1) }
    }

    // --- TradeOffer: valid offers at the boundaries are accepted ---

    @Test
    fun `a valid offer with sellPrice below buyPrice is accepted`() {
        val offer = TradeOffer(buyPrice = 10, sellPrice = 8)
        assertEquals(10L, offer.buyPrice)
        assertEquals(8L, offer.sellPrice)
    }

    @Test
    fun `sellPrice may equal buyPrice (the upper boundary is inclusive)`() {
        val offer = TradeOffer(buyPrice = 10, sellPrice = 10)
        assertEquals(10L, offer.sellPrice)
    }

    @Test
    fun `a zero sell price is accepted (the lower boundary is inclusive)`() {
        val offer = TradeOffer(buyPrice = 10, sellPrice = 0)
        assertEquals(0L, offer.sellPrice)
    }

    // --- StationMarket.offerFor / EMPTY ---

    @Test
    fun `offerFor returns the listed offer for a traded resource`() {
        val iron = TradeOffer(buyPrice = 10, sellPrice = 8)
        val market = StationMarket(mapOf(ResourceType.IRON_ORE to iron))

        assertEquals(iron, market.offerFor(ResourceType.IRON_ORE))
    }

    @Test
    fun `offerFor returns null for a resource the station does not trade`() {
        val market = StationMarket(mapOf(ResourceType.IRON_ORE to TradeOffer(buyPrice = 10, sellPrice = 8)))

        assertNull("an unlisted resource is not traded here", market.offerFor(ResourceType.TITANIUM))
    }

    @Test
    fun `EMPTY trades nothing`() {
        assertTrue("EMPTY has no offers", StationMarket.EMPTY.offers.isEmpty())
        for (resource in ResourceType.entries) {
            assertNull("EMPTY offers nothing for $resource", StationMarket.EMPTY.offerFor(resource))
        }
    }
}
