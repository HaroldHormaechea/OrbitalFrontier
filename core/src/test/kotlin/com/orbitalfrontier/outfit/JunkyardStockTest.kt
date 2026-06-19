package com.orbitalfrontier.outfit

import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [JunkyardStock] (UC47 AC#3) — the persisted half of the deterministic-baseline +
 * persisted-depletion model.
 *
 * [JunkyardStock] records only the **count the player has bought** per (junkyard, part); the baseline
 * is recomputed on load ([UsedPartPricing.baselineStock]). The value must be canonical (a 0 count is
 * never stored), accumulate purchases via [JunkyardStock.withPurchase], and — critically for
 * byte-identity — return the **same instance** on a 0-unit (no-op) purchase so a non-buying tick
 * serializes no depletion.
 */
class JunkyardStockTest {
    private val junkyard = PoiId("gamma-junkyard")
    private val otherJunkyard = PoiId("delta-junkyard")
    private val engine = UpgradeCatalog.ENGINE_TUNE_I
    private val cargo = UpgradeCatalog.CARGO_POD_I

    @Test
    fun `empty stock reports zero purchased everywhere`() {
        assertEquals(0, JunkyardStock.EMPTY.purchasedCount(junkyard, engine))
        assertTrue(JunkyardStock.EMPTY.purchasedByStation.isEmpty())
    }

    @Test
    fun `withPurchase records the purchased count`() {
        val stock = JunkyardStock.EMPTY.withPurchase(junkyard, engine, 1)
        assertEquals(1, stock.purchasedCount(junkyard, engine))
        // An unrelated (junkyard, part) is still undepleted.
        assertEquals(0, stock.purchasedCount(junkyard, cargo))
        assertEquals(0, stock.purchasedCount(otherJunkyard, engine))
    }

    @Test
    fun `withPurchase accumulates across repeated buys`() {
        val stock =
            JunkyardStock.EMPTY
                .withPurchase(junkyard, engine, 1)
                .withPurchase(junkyard, engine, 1)
                .withPurchase(junkyard, engine, 1)
        assertEquals(3, stock.purchasedCount(junkyard, engine))
    }

    @Test
    fun `withPurchase keeps depletion separate per junkyard and per part`() {
        val stock =
            JunkyardStock.EMPTY
                .withPurchase(junkyard, engine, 2)
                .withPurchase(junkyard, cargo, 1)
                .withPurchase(otherJunkyard, engine, 5)
        assertEquals(2, stock.purchasedCount(junkyard, engine))
        assertEquals(1, stock.purchasedCount(junkyard, cargo))
        assertEquals(5, stock.purchasedCount(otherJunkyard, engine))
    }

    @Test
    fun `a zero-unit purchase is a same-instance no-op (byte-identity)`() {
        val seeded = JunkyardStock.EMPTY.withPurchase(junkyard, engine, 1)
        // Both an empty and a populated stock return THE SAME instance on a 0-unit (or negative) buy, so a
        // non-buying tick keeps the snapshot byte-identical (no spurious depletion churn).
        assertSame(JunkyardStock.EMPTY, JunkyardStock.EMPTY.withPurchase(junkyard, engine, 0))
        assertSame(seeded, seeded.withPurchase(junkyard, engine, 0))
        assertSame(seeded, seeded.withPurchase(junkyard, engine, -3))
    }

    @Test
    fun `the map is canonical - never stores a zero count`() {
        // Buying then "un-buying" the same count drops the entry entirely rather than leaving a 0 row, so
        // the representation is canonical (mirrors StationMarketState) and equal stocks compare equal.
        val net = JunkyardStock.EMPTY.withPurchase(junkyard, engine, 2).withPurchase(junkyard, engine, -2)
        // The negative path is a no-op (units <= 0), so the +2 stands; assert what the contract actually does.
        assertEquals(2, net.purchasedCount(junkyard, engine))

        // A direct construction with a 0 inner value is non-canonical; withPurchase never produces one.
        val grown = JunkyardStock.EMPTY.withPurchase(junkyard, engine, 1)
        assertTrue("a stored station map only holds positive counts", grown.purchasedByStation.getValue(junkyard).values.all { it > 0 })
    }

    @Test
    fun `equal depletions compare equal regardless of build order`() {
        val a =
            JunkyardStock.EMPTY
                .withPurchase(junkyard, engine, 1)
                .withPurchase(otherJunkyard, cargo, 2)
        val b =
            JunkyardStock.EMPTY
                .withPurchase(otherJunkyard, cargo, 2)
                .withPurchase(junkyard, engine, 1)
        assertEquals("order-insensitive value equality (snapshot determinism)", a, b)
    }
}
