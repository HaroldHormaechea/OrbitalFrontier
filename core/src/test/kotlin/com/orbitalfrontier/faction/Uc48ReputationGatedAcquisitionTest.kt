package com.orbitalfrontier.faction

import com.orbitalfrontier.economy.FactionPricing
import com.orbitalfrontier.economy.PricingParams
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.Outfitting
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.FleetResolver
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.Shipyard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UC48 AC#5 anchor — the deterministic standing-transition proof, exercised against the **real**
 * authored catalog ([UpgradeCatalog.MVP]) and roster ([ShipRoster]) through the pure resolvers
 * ([Outfitting] / [FleetResolver]), the same path the headless sim/replay drives.
 *
 * The contract the AC pins: at neutral standing a premium part/hull is **locked** (the buy is a
 * no-op), and once the player raises their league standing to the authored unlock threshold the same
 * buy **succeeds AND its effective price reflects the standing discount**. Both the gate and the price
 * are derived at read time from live [Reputation] — no schema, no fixture regeneration — so this also
 * stands in for AC#3 ("update as standing changes"). Pure inputs ⇒ fully deterministic.
 *
 * The screen wiring of the same gate is pinned separately by
 * [com.orbitalfrontier.screen.Uc48ReputationGatingSourceTest]; this test proves the *behaviour*.
 */
class Uc48ReputationGatedAcquisitionTest {
    private val league = FactionId("league")
    private val pricingParams = PricingParams()

    // Authored gated content (see UpgradeCatalog / ShipRoster): both threshold 10 at league stations.
    private val gatedPart = UpgradeCatalog.ENGINE_TUNE_II // an UpgradeId; price 700
    private val gatedPartPrice = UpgradeCatalog.MVP.upgrade(gatedPart)!!.price
    private val gatedHull = ShipRoster.PROSPECTOR // a ShipType; price 2500
    private val unlock = 10

    private val outfitMarket = OutfitMarket.of(listOf(gatedPart))
    private val shipyard = Shipyard.of(listOf(gatedHull.id))

    private fun resolveOutfit(reputation: Reputation) =
        Outfitting.resolve(
            credits = 100_000L,
            loadout = Loadout.EMPTY,
            slotCounts = ShipRoster.STARTER.slotCounts,
            outfitMarket = outfitMarket,
            isJunkyard = false,
            order = OutfitOrder.BuyInstall(gatedPart),
            factionId = league,
            reputation = reputation,
            pricingParams = pricingParams,
        )

    private fun resolveFleet(reputation: Reputation) =
        FleetResolver.resolve(
            Fleet.starter(),
            credits = 100_000L,
            shipyard = shipyard,
            order = FleetOrder.BuyShip(gatedHull.id),
            factionId = league,
            reputation = reputation,
            pricingParams = pricingParams,
        )

    @Test
    fun `at neutral standing both the gated part and the gated hull are locked (no-op)`() {
        val outfit = resolveOutfit(Reputation.EMPTY)
        assertFalse("ENGINE_TUNE_II is locked at neutral standing", outfit.changed)
        assertTrue("nothing is installed", outfit.loadout.isEmpty)
        assertEquals("no credits spent", 100_000L, outfit.credits)

        val fleet = resolveFleet(Reputation.EMPTY)
        assertFalse("PROSPECTOR is locked at neutral standing", fleet.changed)
        assertEquals("the fleet is unchanged", 1, fleet.fleet.ships.size)
        assertEquals("no credits spent", 100_000L, fleet.credits)
    }

    @Test
    fun `raising league standing to the threshold unlocks both purchases at the discounted price`() {
        // The player earns standing (e.g. one league mining mission turn-in grants +10 — UC14).
        val raised = Reputation(mapOf(league to unlock))

        // --- The gated part becomes purchasable, charged at the discounted effective price. ---
        val partPrice = FactionPricing.adjustedPrice(gatedPartPrice, league, raised, pricingParams)
        assertTrue("the unlocked part is discounted below its base", partPrice < gatedPartPrice)
        assertEquals("round(700 * 0.99) = 693", 693L, partPrice)

        val outfit = resolveOutfit(raised)
        assertTrue("ENGINE_TUNE_II becomes purchasable at standing >= 10", outfit.changed)
        assertEquals(gatedPart, outfit.loadout.upgradeAt(SlotCategory.ENGINES, 0))
        assertEquals("the discounted price is deducted (display==charge)", 100_000L - partPrice, outfit.credits)

        // --- The gated hull becomes purchasable, charged at the discounted effective price. ---
        val hullPrice = FactionPricing.adjustedPrice(gatedHull.price, league, raised, pricingParams)
        assertTrue("the unlocked hull is discounted below its base", hullPrice < gatedHull.price)
        assertEquals("round(2500 * 0.99) = 2475", 2475L, hullPrice)

        val fleet = resolveFleet(raised)
        assertTrue("PROSPECTOR becomes purchasable at standing >= 10", fleet.changed)
        assertEquals(2, fleet.fleet.ships.size)
        assertEquals(gatedHull.id, fleet.fleet.ship(ShipId(1))!!.type.id)
        assertEquals("the discounted price is deducted (display==charge)", 100_000L - hullPrice, fleet.credits)
    }

    @Test
    fun `the transition is deterministic — identical inputs yield identical results`() {
        val raised = Reputation(mapOf(league to unlock))
        assertEquals(resolveOutfit(raised).credits, resolveOutfit(raised).credits)
        assertEquals(resolveFleet(raised).credits, resolveFleet(raised).credits)
    }
}
