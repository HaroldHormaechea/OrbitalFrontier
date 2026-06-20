package com.orbitalfrontier.station

import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.UpgradeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OwnedStationMarkets] (UC51 AC#3/#5) — the authored desks an owned station's modules
 * expose.
 *
 * AC#5 requires that using a built module is a **real** end-to-end action, so the commerce desk must be
 * **non-empty** (the player can actually sell at it) and the retrofit desk must offer real (tier-I)
 * parts. These desks are reconstructed authored data (ADR 0007/0008), so the assertions pin their shape,
 * not a persisted row.
 */
class OwnedStationMarketsTest {
    @Test
    fun `the commerce desk is non-empty so a sale is a real action (AC#5)`() {
        assertTrue(
            "the COMMERCE desk must offer at least one resource so docking-to-trade is genuine (AC#5)",
            OwnedStationMarkets.COMMERCE_DESK.offers.isNotEmpty(),
        )
    }

    @Test
    fun `every commerce offer honours the no-money-printing invariant`() {
        // TradeOffer already enforces 0 <= sellPrice <= buyPrice in its init; assert the desk is well-formed
        // (a positive buy price) so the affordability division downstream is always safe.
        for ((resource, offer) in OwnedStationMarkets.COMMERCE_DESK.offers) {
            assertTrue("$resource buyPrice must be positive", offer.buyPrice > 0)
            assertTrue("$resource sellPrice must not exceed buyPrice", offer.sellPrice <= offer.buyPrice)
        }
    }

    @Test
    fun `the retrofit desk offers exactly the MVP tier-I upgrades`() {
        val expected =
            OutfitMarket.of(
                listOf(
                    UpgradeCatalog.ENGINE_TUNE_I,
                    UpgradeCatalog.CARGO_POD_I,
                    UpgradeCatalog.FUEL_TANK_I,
                    UpgradeCatalog.SCANNER_I,
                ),
            )
        assertEquals(
            "the RETROFIT desk must stock the tier-I outfit set (a working refit desk, AC#3)",
            expected,
            OwnedStationMarkets.RETROFIT_DESK,
        )
        assertTrue("the retrofit desk must not be empty", OwnedStationMarkets.RETROFIT_DESK.offered.isNotEmpty())
    }
}
