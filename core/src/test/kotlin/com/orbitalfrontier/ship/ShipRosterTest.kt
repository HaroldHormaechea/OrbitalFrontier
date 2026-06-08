package com.orbitalfrontier.ship

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.outfit.SlotCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ShipRoster] (UC09 AC#1) — the data-driven ship-type catalog.
 *
 * The load-bearing assertion is the **byte-identical pin**: the starter type's empty-fit base stats
 * equal exactly today's constants ([Cargo.DEFAULT_CAPACITY], [FuelParams.DEFAULT_TANK_CAPACITY],
 * identity movement), so the Stage A Fleet refactor lands with zero behaviour change. Also pins
 * id-resolution (unknown slug → null, the graceful-degradation seam) and the per-role slot layouts.
 */
class ShipRosterTest {
    @Test
    fun `the starter type's empty-fit stats are pinned to today's constants (byte-identical)`() {
        val starter = ShipRoster.STARTER

        assertEquals(ShipTypeId("starter"), starter.id)
        assertEquals(ShipRoster.STARTER_TYPE_ID, starter.id)
        assertEquals(Cargo.DEFAULT_CAPACITY, starter.baseCargoCapacity)
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, starter.baseFuelCapacity, 0f)
        assertEquals("the starter hull uses the identity movement profile", MovementProfile.IDENTITY, starter.movement)
    }

    @Test
    fun `byId resolves every roster type and returns null for an unknown slug`() {
        assertSame(ShipRoster.STARTER, ShipRoster.byId(ShipTypeId("starter")))
        assertSame(ShipRoster.PROSPECTOR, ShipRoster.byId(ShipRoster.PROSPECTOR.id))
        assertSame(ShipRoster.SWIFT, ShipRoster.byId(ShipRoster.SWIFT.id))
        assertNull("an unknown slug resolves to null (caller degrades to starter)", ShipRoster.byId(ShipTypeId("dreadnought")))
    }

    @Test
    fun `the roster lists the starter first followed by the alternates`() {
        assertEquals(listOf(ShipRoster.STARTER, ShipRoster.PROSPECTOR, ShipRoster.SWIFT), ShipRoster.all)
    }

    @Test
    fun `the starter exposes its authored per-category slot layout`() {
        val starter = ShipRoster.STARTER

        assertEquals(2, starter.slotCount(SlotCategory.ENGINES))
        assertEquals(2, starter.slotCount(SlotCategory.CARGO))
        assertEquals(1, starter.slotCount(SlotCategory.FUEL_TANK))
        // A category the ship type does not list has zero slots.
        assertEquals(0, starter.slotCount(SlotCategory.WEAPONS))
    }

    @Test
    fun `purchasable hulls carry a positive price`() {
        assertTrue("PROSPECTOR is for sale", ShipRoster.PROSPECTOR.price > 0)
        assertTrue("SWIFT is for sale", ShipRoster.SWIFT.price > 0)
        // The starter is never for sale, so its price is unused (defaults to 0).
        assertEquals(0L, ShipRoster.STARTER.price)
    }
}
