package com.orbitalfrontier.outfit

import com.orbitalfrontier.economy.FactionPricing
import com.orbitalfrontier.economy.PricingParams
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.ship.ShipMovementParams
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

    // --- UC48: reputation-gated BuyInstall + faction-adjusted effective price ----------------------------

    private val league = FactionId("league")
    private val pricingParams = PricingParams()
    private val gatedPart = UpgradeCatalog.ENGINE_TUNE_II // ENGINES, price 700, unlockThreshold 10
    private val gatedPartPrice = 700L
    private val gatedMarket = OutfitMarket.of(listOf(gatedPart))

    @Test
    fun `UC48 buy-install of a gated part is a no-op below the standing threshold`() {
        // Offered and affordable, but the player is at neutral standing (< the part's threshold of 10).
        val result =
            Outfitting.resolve(
                credits = 10_000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = gatedMarket,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(gatedPart),
                factionId = league,
                reputation = Reputation.EMPTY,
                pricingParams = pricingParams,
            )

        assertFalse("a gated part below threshold must be a no-op (locked)", result.changed)
        assertEquals(10_000L, result.credits)
        assertTrue(result.loadout.isEmpty)
    }

    @Test
    fun `UC48 buy-install of a gated part succeeds at or above threshold and charges the adjusted price`() {
        val standing = 10
        val expectedPrice = FactionPricing.adjustedPrice(gatedPartPrice, league, Reputation(mapOf(league to standing)), pricingParams)
        // mul = 0.99 at +10 ⇒ round(700 * 0.99) = 693 — a discount versus the 700 base.
        assertEquals(693L, expectedPrice)

        val result =
            Outfitting.resolve(
                credits = 10_000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = gatedMarket,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(gatedPart),
                factionId = league,
                reputation = Reputation(mapOf(league to standing)),
                pricingParams = pricingParams,
            )

        assertTrue("unlocked at the threshold (>=)", result.changed)
        assertEquals("the faction-adjusted price is deducted (display==charge)", 10_000L - expectedPrice, result.credits)
        assertEquals(gatedPart, result.loadout.upgradeAt(SlotCategory.ENGINES, 0))
    }

    @Test
    fun `UC48 a gated part at a faction-less station is permanently locked (authoring error)`() {
        val result =
            Outfitting.resolve(
                credits = 10_000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = gatedMarket,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(gatedPart),
                // A positive threshold with a null faction can never be met.
                factionId = null,
                reputation = Reputation(mapOf(league to 999)),
                pricingParams = pricingParams,
            )

        assertFalse("a positive threshold at a null-faction station is locked", result.changed)
        assertEquals(10_000L, result.credits)
    }

    @Test
    fun `UC48 an ungated part still charges the faction-adjusted price at an allied standing`() {
        // engine-tune-i is ungated (threshold 0) but its price is still graded by standing (AC#2).
        val standing = 50
        val expected = FactionPricing.adjustedPrice(enginePrice, league, Reputation(mapOf(league to standing)), pricingParams)
        assertEquals("round(300 * 0.95) = 285", 285L, expected)

        val result =
            Outfitting.resolve(
                credits = 1000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = market,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(engine),
                factionId = league,
                reputation = Reputation(mapOf(league to standing)),
                pricingParams = pricingParams,
            )

        assertTrue(result.changed)
        assertEquals(1000L - expected, result.credits)
    }

    // --- UC48: reputation-gated BuyUsed (compose-on-base price) ------------------------------------------

    @Test
    fun `UC48 buy-used of a gated part is a no-op below the standing threshold`() {
        // The junkyard offers the gated tier-II part used, the player can afford it, but is below threshold.
        val result =
            resolveGatedBuyUsed(
                credits = 10_000L,
                usedPartMarket = OutfitMarket.of(listOf(gatedPart)),
                order = OutfitOrder.BuyUsed(gatedPart),
                factionId = league,
                reputation = Reputation.EMPTY,
            )

        assertFalse("a gated used part below threshold must be a no-op (locked)", result.changed)
        assertEquals(10_000L, result.credits)
    }

    @Test
    fun `UC48 buy-used of a gated part succeeds above threshold and composes used discount on the faction base`() {
        val standing = 50
        // Compose-on-base: faction-adjust the catalog price first, THEN the used discount on top.
        val factionBase = FactionPricing.adjustedPrice(gatedPartPrice, league, Reputation(mapOf(league to standing)), pricingParams)
        val expectedUsed = UsedPartPricing.usedPrice(factionBase, usedParams)
        // factionBase = round(700 * 0.95) = 665; used = round(665 * 0.6) = 399.
        assertEquals(665L, factionBase)
        assertEquals(399L, expectedUsed)

        val result =
            resolveGatedBuyUsed(
                credits = 10_000L,
                usedPartMarket = OutfitMarket.of(listOf(gatedPart)),
                order = OutfitOrder.BuyUsed(gatedPart),
                factionId = league,
                reputation = Reputation(mapOf(league to standing)),
            )

        assertTrue("unlocked above threshold", result.changed)
        assertEquals("the composed used price is deducted", 10_000L - expectedUsed, result.credits)
        assertEquals(gatedPart, result.loadout.upgradeAt(SlotCategory.ENGINES, 0))
    }

    // --- UC48 edge case: no confiscation — gating governs purchase, not retained inventory ---------------

    @Test
    fun `UC48 a part installed at high standing stays installed and keeps its stats after standing drops`() {
        // 1) Buy + install the gated part while allied (standing 50).
        val installed =
            Outfitting.resolve(
                credits = 10_000L,
                loadout = Loadout.EMPTY,
                slotCounts = slotCounts,
                outfitMarket = gatedMarket,
                isJunkyard = false,
                order = OutfitOrder.BuyInstall(gatedPart),
                factionId = league,
                reputation = Reputation(mapOf(league to 50)),
                pricingParams = pricingParams,
            )
        assertTrue("precondition: the gated part installs while allied", installed.changed)
        val loadoutWithGatedPart = installed.loadout

        // Stats derived while the part is installed (stat derivation never consults standing).
        val baseParams = ShipMovementParams()
        val statsAtHighStanding =
            ShipStats.effectiveMovementParams(baseParams, ShipRoster.STARTER, loadoutWithGatedPart)

        // 2) Standing collapses to neutral (e.g. via combat reputation, UC43). Any later non-buy tick at the
        //    now-locked standing must NOT strip the installed part — gating only governs purchase availability.
        val afterDrop =
            Outfitting.resolve(
                credits = installed.credits,
                loadout = loadoutWithGatedPart,
                slotCounts = slotCounts,
                outfitMarket = gatedMarket,
                isJunkyard = false,
                order = OutfitOrder.None,
                factionId = league,
                // Reputation.EMPTY is below the part's threshold now.
                reputation = Reputation.EMPTY,
                pricingParams = pricingParams,
            )

        assertFalse("a None tick changes nothing", afterDrop.changed)
        assertEquals("the installed gated part is retained — never confiscated", loadoutWithGatedPart, afterDrop.loadout)
        assertEquals(gatedPart, afterDrop.loadout.upgradeAt(SlotCategory.ENGINES, 0))

        // 3) Derived stats are unchanged after the standing drop — the loadout still contributes its delta.
        val statsAfterDrop =
            ShipStats.effectiveMovementParams(baseParams, ShipRoster.STARTER, afterDrop.loadout)
        assertEquals("derived stats are unaffected by the standing drop", statsAtHighStanding, statsAfterDrop)
        assertTrue("the engine delta is still applied", statsAfterDrop.maxSpeed > baseParams.maxSpeed)
    }

    private fun resolveGatedBuyUsed(
        credits: Long,
        loadout: Loadout = Loadout.EMPTY,
        isJunkyard: Boolean = true,
        usedPartMarket: OutfitMarket,
        junkyardStock: JunkyardStock = JunkyardStock.EMPTY,
        stationId: PoiId? = junkyardId,
        order: OutfitOrder,
        factionId: FactionId?,
        reputation: Reputation,
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
            factionId = factionId,
            reputation = reputation,
            pricingParams = pricingParams,
        )

    private companion object {
        val cargoUsed: UpgradeId = UpgradeCatalog.CARGO_POD_I
    }
}
