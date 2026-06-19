package com.orbitalfrontier.outfit

import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * Unit tests for [UsedPartPricing] (UC47 AC#1/#3/#4) — the pure used-part discount curve and the
 * deterministic baseline-stock draw.
 *
 * [UsedPartPricing.usedPrice] is the data-driven discount curve: the discount lives entirely in
 * [UsedPartParams.discountFraction] (so it applies consistently across junkyards — AC#4) and the
 * result is clamped to `>= 1`. [UsedPartPricing.baselineStock] is a deterministic uniform draw in
 * `[minStock, maxStock]` keyed purely on the stable junkyard/part slugs (AC#3) — reproducible on any
 * JVM, varying by key, and carrying no epoch (time-based restock is deliberately not modelled).
 */
class UsedPartPricingTest {
    private val params = UsedPartParams() // discount 0.6, stock 1..3

    // --- usedPrice: the data-driven discount curve (AC#1/#4) ---

    @Test
    fun `used price is round of new price times the discount fraction`() {
        // At the default 0.6 fraction, the catalogued tier-I parts discount to clean integers.
        assertEquals(180L, UsedPartPricing.usedPrice(300L, params)) // engine-tune-i
        assertEquals(150L, UsedPartPricing.usedPrice(250L, params)) // cargo-pod-i
        assertEquals(108L, UsedPartPricing.usedPrice(180L, params)) // scanner-i
        assertEquals(132L, UsedPartPricing.usedPrice(220L, params)) // fuel-tank-i
    }

    @Test
    fun `used price is data-driven by the discount fraction (consistent across junkyards)`() {
        // The same new price yields a different used price purely as a function of the params — no magic
        // per-call number — so a retune is one place and every junkyard prices identically (AC#4).
        assertEquals(150L, UsedPartPricing.usedPrice(300L, UsedPartParams(discountFraction = 0.5)))
        assertEquals(240L, UsedPartPricing.usedPrice(300L, UsedPartParams(discountFraction = 0.8)))
        assertEquals(30L, UsedPartPricing.usedPrice(300L, UsedPartParams(discountFraction = 0.1)))
    }

    @Test
    fun `used price is clamped to at least one credit (never free)`() {
        // A zero-priced (or rounds-to-zero) part still costs 1 credit used.
        assertEquals(1L, UsedPartPricing.usedPrice(0L, params))
        // 3 * 0.1 = 0.3 -> rounds to 0 -> clamped to 1.
        assertEquals(1L, UsedPartPricing.usedPrice(3L, UsedPartParams(discountFraction = 0.1)))
    }

    @Test
    fun `used price rounds to the nearest credit`() {
        // 175 * 0.6 = 105.0 (exact); 177 * 0.6 = 106.2 -> 106; 179 * 0.6 = 107.4 -> 107.
        assertEquals(105L, UsedPartPricing.usedPrice(175L, params))
        assertEquals(106L, UsedPartPricing.usedPrice(177L, params))
        assertEquals(107L, UsedPartPricing.usedPrice(179L, params))
        // Cross-check against the same rounding primitive the implementation uses.
        assertEquals((353L * 0.6).roundToLong(), UsedPartPricing.usedPrice(353L, params))
    }

    // --- baselineStock: deterministic draw in [minStock, maxStock] (AC#3) ---

    private val gammaJunkyard = PoiId("gamma-junkyard")

    @Test
    fun `baseline stock is within the params min and max for every offered part`() {
        for (part in listOf(
            UpgradeCatalog.ENGINE_TUNE_I,
            UpgradeCatalog.CARGO_POD_I,
            UpgradeCatalog.SCANNER_I,
            UpgradeCatalog.FUEL_TANK_I,
        )) {
            val baseline = UsedPartPricing.baselineStock(gammaJunkyard, part, params)
            assertTrue("baseline $baseline must be >= minStock", baseline >= params.minStock)
            assertTrue("baseline $baseline must be <= maxStock", baseline <= params.maxStock)
        }
    }

    @Test
    fun `baseline stock is deterministic for the same junkyard and part`() {
        val first = UsedPartPricing.baselineStock(gammaJunkyard, UpgradeCatalog.ENGINE_TUNE_I, params)
        val second = UsedPartPricing.baselineStock(gammaJunkyard, UpgradeCatalog.ENGINE_TUNE_I, params)
        assertEquals("same (junkyard, part) -> same baseline", first, second)
    }

    @Test
    fun `baseline stock matches the documented hash to LCG keying formula`() {
        // Re-derive via the same DeterministicRng primitives + key the docs pin, locking the keying so a
        // refactor that changes the key (and thus every recorded replay's stock) fails loudly here.
        val span = params.maxStock - params.minStock + 1
        val expected =
            DeterministicRng.boundedInt(
                DeterministicRng.lcgAdvance(DeterministicRng.fnv1a("usedstock:gamma-junkyard:engine-tune-i")),
                span,
            ) + params.minStock
        assertEquals(expected, UsedPartPricing.baselineStock(gammaJunkyard, UpgradeCatalog.ENGINE_TUNE_I, params))
    }

    @Test
    fun `baseline stock varies by key (not a constant)`() {
        // Across many distinct part keys the draw must spread over more than one value — proof it is keyed,
        // not a fixed shelf. (A 3-wide range over 24 keys makes a single-value collapse astronomically unlikely.)
        val values =
            (0 until 24)
                .map { UsedPartPricing.baselineStock(gammaJunkyard, UpgradeId("synthetic-part-$it"), params) }
                .toSet()
        assertTrue("baseline must vary across distinct part keys, got $values", values.size > 1)
        // The key includes the junkyard too: the same part at a different junkyard can draw differently.
        val partKeyedByJunkyard =
            listOf(PoiId("gamma-junkyard"), PoiId("delta-junkyard"), PoiId("epsilon-junkyard"))
                .map { UsedPartPricing.baselineStock(it, UpgradeCatalog.CARGO_POD_I, params) }
        partKeyedByJunkyard.forEach { assertTrue(it in params.minStock..params.maxStock) }
    }

    @Test
    fun `a wider stock band is honoured (data-driven bounds)`() {
        val wide = UsedPartParams(minStock = 5, maxStock = 9)
        for (i in 0 until 40) {
            val baseline = UsedPartPricing.baselineStock(gammaJunkyard, UpgradeId("p$i"), wide)
            assertTrue("baseline $baseline within 5..9", baseline in 5..9)
        }
    }
}
