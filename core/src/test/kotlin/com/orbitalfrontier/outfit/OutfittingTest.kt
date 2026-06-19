package com.orbitalfrontier.outfit

import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Outfitting] (UC09 AC#2/#3/#4/#7) — the pure buy-install / remove-sell resolver.
 *
 * Every gate miss (not catalogued, not offered, can't afford, no free slot, not a junkyard, empty
 * slot) must be a **no-op** reporting `changed = false` with credits/loadout unchanged. A successful
 * BuyInstall installs into the lowest free slot and deducts the price; a successful RemoveSell (only
 * at a junkyard) frees the slot and refunds [Outfitting.USED_PART_REFUND_FRACTION] of the price.
 */
class OutfittingTest {
    private val slotCounts = ShipRoster.STARTER.slotCounts
    private val engine = UpgradeCatalog.ENGINE_TUNE_I // ENGINES, price 300
    private val enginePrice = 300L

    /** A market that offers the engine-tune-i part. */
    private val market = OutfitMarket.of(listOf(engine))

    private fun loadoutWithEngine(): Loadout =
        (Loadout.EMPTY.install(SlotCategory.ENGINES, slotCounts.getValue(SlotCategory.ENGINES), engine) as InstallResult.Installed).loadout

    // --- BuyInstall ---

    @Test
    fun `buy-install installs the part and deducts its price when offered and affordable`() {
        val result =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(engine),
            )

        assertTrue(result.changed)
        assertEquals(1000L - enginePrice, result.credits)
        assertEquals(engine, result.loadout.upgradeAt(SlotCategory.ENGINES, 0))
    }

    @Test
    fun `buy-install is a no-op when the part is not catalogued`() {
        val result =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = OutfitMarket.of(listOf(UpgradeId("ghost"))),
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(UpgradeId("ghost")),
            )

        assertFalse(result.changed)
        assertEquals(1000L, result.credits)
        assertTrue(result.loadout.isEmpty)
    }

    @Test
    fun `buy-install is a no-op when the station does not offer the part`() {
        val result =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                // OutfitMarket.EMPTY offers nothing.
                outfitMarket = OutfitMarket.EMPTY,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(engine),
            )

        assertFalse(result.changed)
        assertEquals(1000L, result.credits)
    }

    @Test
    fun `buy-install is a no-op when the player cannot afford it`() {
        val result =
            Outfitting.resolve(
                // One credit short of the engine price.
                credits = enginePrice - 1,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(engine),
            )

        assertFalse(result.changed)
        assertEquals(enginePrice - 1, result.credits)
        assertTrue(result.loadout.isEmpty)
    }

    @Test
    fun `buy-install is a no-op when the category has no free slot`() {
        // The starter exposes 2 engine slots; fill both, then a third buy must fail.
        var loadout = Loadout.EMPTY
        loadout = (loadout.install(SlotCategory.ENGINES, 2, engine) as InstallResult.Installed).loadout
        loadout = (loadout.install(SlotCategory.ENGINES, 2, UpgradeCatalog.ENGINE_TUNE_II) as InstallResult.Installed).loadout

        val result =
            Outfitting.resolve(
                credits = 10_000L,
                loadout = loadout,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(engine),
            )

        assertFalse("both engine slots full ⇒ no-op", result.changed)
        assertEquals(10_000L, result.credits)
        assertEquals(loadout, result.loadout)
    }

    // --- RemoveSell (junkyard-only) ---

    @Test
    fun `remove-sell at a junkyard frees the slot and refunds the used-part fraction`() {
        val loadout = loadoutWithEngine()

        val result =
            Outfitting.resolve(
                credits = 0L,
                loadout = loadout,
                slotCounts = slotCounts,
                outfitMarket = OutfitMarket.EMPTY,
                isJunkyard = true,
                order = OutfitOrder.RemoveSell(SlotCategory.ENGINES, 0),
            )

        assertTrue(result.changed)
        // refund = floor(300 * 0.5) = 150.
        val expectedRefund = (enginePrice * Outfitting.USED_PART_REFUND_FRACTION).toLong()
        assertEquals(expectedRefund, result.credits)
        assertTrue("the slot is freed", result.loadout.isEmpty)
    }

    @Test
    fun `remove-sell at a dealer (not a junkyard) is a no-op`() {
        val loadout = loadoutWithEngine()

        val result =
            Outfitting.resolve(
                credits = 0L,
                loadout = loadout,
                slotCounts = slotCounts,
                outfitMarket = OutfitMarket.EMPTY,
                // A normal dealer (not a junkyard).
                isJunkyard = false,
                order = OutfitOrder.RemoveSell(SlotCategory.ENGINES, 0),
            )

        assertFalse("used parts can only be sold at a junkyard (AC#4)", result.changed)
        assertEquals(0L, result.credits)
        assertEquals(loadout, result.loadout)
    }

    @Test
    fun `remove-sell of an empty slot is a no-op even at a junkyard`() {
        val result =
            Outfitting.resolve(
                credits = 500L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = OutfitMarket.EMPTY,
                isJunkyard = true,
                order = OutfitOrder.RemoveSell(SlotCategory.ENGINES, 0),
            )

        assertFalse(result.changed)
        assertEquals(500L, result.credits)
    }

    // --- None ---

    @Test
    fun `the None order returns the inputs unchanged`() {
        val loadout = loadoutWithEngine()
        val result =
            Outfitting.resolve(
                credits = 777L,
                loadout = loadout,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = true,
                order = OutfitOrder.None,
            )

        assertFalse(result.changed)
        assertEquals(777L, result.credits)
        assertSame(loadout, result.loadout)
    }

    // --- BuyUsed (UC47 — junkyard-only discounted buy) ---

    private val junkyardId = PoiId("gamma-junkyard")
    private val usedParams = UsedPartParams() // discount 0.6, stock 1..3
    private val usedMarket = OutfitMarket.of(listOf(engine))

    /** The discounted used price the resolver charges for the engine (300 * 0.6 = 180). */
    private val usedEnginePrice = UsedPartPricing.usedPrice(enginePrice, usedParams)

    /** The deterministic baseline used-stock the gamma junkyard holds of the engine. */
    private val engineBaseline = UsedPartPricing.baselineStock(junkyardId, engine, usedParams)

    private fun resolveBuyUsed(
        credits: Long,
        loadout: Loadout = Loadout.EMPTY,
        isJunkyard: Boolean = true,
        usedPartMarket: OutfitMarket = usedMarket,
        junkyardStock: JunkyardStock = JunkyardStock.EMPTY,
        stationId: PoiId? = junkyardId,
        order: OutfitOrder = OutfitOrder.BuyUsed(engine),
    ): OutfitResult =
        Outfitting.resolve(
            credits = credits,
            loadout = loadout,
            slotCounts = slotCounts,
            outfitMarket = OutfitMarket.EMPTY,
            isJunkyard = isJunkyard,
            order = order,
            usedPartMarket = usedPartMarket,
            junkyardStock = junkyardStock,
            stationId = stationId,
            usedPartParams = usedParams,
        )

    @Test
    fun `the used price is below the new price (a discount)`() {
        assertTrue("used part must be strictly cheaper than new", usedEnginePrice < enginePrice)
        assertEquals(180L, usedEnginePrice)
    }

    @Test
    fun `buy-used installs the part, deducts the USED price, and records one depletion`() {
        val result = resolveBuyUsed(credits = 1000L)

        assertTrue(result.changed)
        // The DISCOUNTED price is charged, not the full catalog price.
        assertEquals(1000L - usedEnginePrice, result.credits)
        // Installed via the SAME flow as a new part — lowest free slot of the category (AC#2).
        assertEquals(engine, result.loadout.upgradeAt(SlotCategory.ENGINES, 0))
        // Exactly one unit of depletion is recorded at this junkyard for this part (AC#3).
        assertEquals(1, result.junkyardStock.purchasedCount(junkyardId, engine))
    }

    @Test
    fun `buy-used installs into the same slot a buy-install would (same outfitting flow)`() {
        val used = resolveBuyUsed(credits = 1000L)
        val new =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = true,
                order = OutfitOrder.BuyInstall(engine),
            )
        assertEquals("buy-used reuses Loadout.install, so the resulting loadout matches buy-install", new.loadout, used.loadout)
    }

    @Test
    fun `buy-used is a no-op at a non-junkyard (carries the input depletion through)`() {
        val seeded = JunkyardStock.EMPTY.withPurchase(junkyardId, cargoUsed, 1)
        val result = resolveBuyUsed(credits = 1000L, isJunkyard = false, junkyardStock = seeded)

        assertFalse("buying used is junkyard-only (AC#1)", result.changed)
        assertEquals(1000L, result.credits)
        assertTrue(result.loadout.isEmpty)
        assertSame("depletion threaded through unchanged", seeded, result.junkyardStock)
    }

    @Test
    fun `buy-used is a no-op when the junkyard does not offer the part used`() {
        val result = resolveBuyUsed(credits = 1000L, usedPartMarket = OutfitMarket.EMPTY)

        assertFalse("not stocked used here (AC#1)", result.changed)
        assertEquals(1000L, result.credits)
        assertTrue(result.loadout.isEmpty)
    }

    @Test
    fun `buy-used is a no-op when there is no station key to track depletion`() {
        val result = resolveBuyUsed(credits = 1000L, stationId = null)

        assertFalse("a null stationId cannot key the per-junkyard depletion", result.changed)
        assertEquals(1000L, result.credits)
    }

    @Test
    fun `buy-used is a no-op when the used stock is depleted`() {
        // Pre-deplete the junkyard to exactly its baseline: available = baseline - purchased = 0.
        val depleted = JunkyardStock.EMPTY.withPurchase(junkyardId, engine, engineBaseline)
        val result = resolveBuyUsed(credits = 100_000L, junkyardStock = depleted)

        assertFalse("baseline - purchased == 0 ⇒ out of stock (AC#3)", result.changed)
        assertEquals(100_000L, result.credits)
        assertTrue(result.loadout.isEmpty)
        assertSame("the depleted stock is carried through unchanged", depleted, result.junkyardStock)
    }

    @Test
    fun `buy-used is a no-op when the player cannot afford the used price`() {
        val result = resolveBuyUsed(credits = usedEnginePrice - 1)

        assertFalse(result.changed)
        assertEquals(usedEnginePrice - 1, result.credits)
        assertTrue(result.loadout.isEmpty)
    }

    @Test
    fun `buy-used is a no-op when the category has no free slot`() {
        // Fill both starter engine slots, then a used buy of a third engine must fail.
        var loadout = Loadout.EMPTY
        loadout = (loadout.install(SlotCategory.ENGINES, 2, engine) as InstallResult.Installed).loadout
        loadout = (loadout.install(SlotCategory.ENGINES, 2, UpgradeCatalog.ENGINE_TUNE_II) as InstallResult.Installed).loadout

        val result = resolveBuyUsed(credits = 100_000L, loadout = loadout)

        assertFalse("both engine slots full ⇒ no-op", result.changed)
        assertEquals(100_000L, result.credits)
        assertEquals(loadout, result.loadout)
    }

    @Test
    fun `buy-used can be repeated until the deterministic baseline is exhausted`() {
        var credits = 1_000_000L
        var stock = JunkyardStock.EMPTY
        var loadout = Loadout.EMPTY
        var bought = 0
        // The starter only has 2 engine slots; cap the loop so a large baseline can't run past slot space.
        repeat(engineBaseline.coerceAtMost(2)) {
            val result = resolveBuyUsed(credits = credits, loadout = loadout, junkyardStock = stock)
            assertTrue("buy #${it + 1} should succeed while stock and slots remain", result.changed)
            credits = result.credits
            stock = result.junkyardStock
            loadout = result.loadout
            bought++
        }
        assertEquals(bought, stock.purchasedCount(junkyardId, engine))
    }

    // --- Anti-exploit invariant (AC#3): non-BuyUsed paths never wipe the depletion ---

    @Test
    fun `at a junkyard the None order returns the SAME input depletion unchanged`() {
        val seeded = JunkyardStock.EMPTY.withPurchase(junkyardId, engine, 1)
        val result =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = true,
                order = OutfitOrder.None,
                usedPartMarket = usedMarket,
                junkyardStock = seeded,
                stationId = junkyardId,
                usedPartParams = usedParams,
            )
        // A free None tick must keep SimulationState.junkyardStock byte-identical — same instance, not a copy.
        assertSame("None must not silently reset the depletion (anti-restock-exploit)", seeded, result.junkyardStock)
    }

    @Test
    fun `at a junkyard a successful BuyInstall carries the input depletion through unchanged`() {
        val seeded = JunkyardStock.EMPTY.withPurchase(junkyardId, engine, 1)
        val result =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                // A full-price refit at the junkyard.
                outfitMarket = market,
                isJunkyard = true,
                order = OutfitOrder.BuyInstall(engine),
                usedPartMarket = usedMarket,
                junkyardStock = seeded,
                stationId = junkyardId,
                usedPartParams = usedParams,
            )
        assertTrue("the new-part install still succeeds", result.changed)
        assertSame("BuyInstall must thread the depletion through untouched", seeded, result.junkyardStock)
    }

    @Test
    fun `at a junkyard a successful RemoveSell carries the input depletion through unchanged`() {
        val seeded = JunkyardStock.EMPTY.withPurchase(junkyardId, engine, 1)
        val result =
            Outfitting.resolve(
                credits = 0L,
                loadout = loadoutWithEngine(),
                slotCounts = slotCounts,
                outfitMarket = OutfitMarket.EMPTY,
                isJunkyard = true,
                order = OutfitOrder.RemoveSell(SlotCategory.ENGINES, 0),
                usedPartMarket = usedMarket,
                junkyardStock = seeded,
                stationId = junkyardId,
                usedPartParams = usedParams,
            )
        assertTrue("the sell still succeeds", result.changed)
        assertSame("RemoveSell must not restock — depletion threaded through", seeded, result.junkyardStock)
    }

    private companion object {
        val cargoUsed: UpgradeId = UpgradeCatalog.CARGO_POD_I
    }
}
