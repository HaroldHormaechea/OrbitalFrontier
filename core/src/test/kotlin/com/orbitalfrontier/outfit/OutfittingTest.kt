package com.orbitalfrontier.outfit

import com.orbitalfrontier.ship.ShipRoster
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
                outfitMarket = OutfitMarket.EMPTY, // offers nothing
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
                credits = enginePrice - 1, // one credit short
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
                isJunkyard = false, // a normal dealer
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
}
