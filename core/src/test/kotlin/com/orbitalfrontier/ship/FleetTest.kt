package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.FactionPricing
import com.orbitalfrontier.economy.PricingParams
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Fleet] and [FleetResolver] (UC09 AC#5/#7) — the multi-ship roster, its invariants,
 * and the pure buy-ship / switch-active resolver.
 *
 * [Fleet] keeps ships sorted by [ShipId] with a separately-stored [Fleet.activeShipId] (so a switch
 * never reorders the list — record/replay determinism). [FleetResolver] gates BuyShip on the
 * shipyard offering the type, the type being catalogued, and affordability, and SwitchActive on
 * ownership — every miss a no-op (`changed = false`).
 */
class FleetTest {
    private val starter = OwnedShip.starter()

    private fun shipOf(
        id: Long,
        type: ShipType = ShipRoster.STARTER,
        position: Vec2 = Vec2.ZERO,
    ): OwnedShip = OwnedShip.fresh(ShipId(id), type, position)

    // --- Fleet construction + invariants ---

    @Test
    fun `the starter fleet is one starter ship, active`() {
        val fleet = Fleet.starter()

        assertEquals(1, fleet.ships.size)
        assertEquals(OwnedShip.STARTER_SHIP_ID, fleet.activeShipId)
        assertEquals(OwnedShip.STARTER_SHIP_ID, fleet.active.id)
        assertFalse(fleet.hasMultipleShips)
    }

    @Test
    fun `a fleet must contain at least one ship`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fleet(emptyList(), OwnedShip.STARTER_SHIP_ID)
        }
    }

    @Test
    fun `a fleet rejects duplicate ship ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fleet(listOf(shipOf(0), shipOf(0)), ShipId(0))
        }
    }

    @Test
    fun `a fleet rejects an unsorted ship list`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fleet(listOf(shipOf(1), shipOf(0)), ShipId(0))
        }
    }

    @Test
    fun `a fleet rejects an active id it does not own`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fleet(listOf(shipOf(0)), ShipId(99))
        }
    }

    // --- lookups / mutators ---

    @Test
    fun `ship resolves an owned id and returns null for an unowned one`() {
        val fleet = Fleet(listOf(shipOf(0), shipOf(1)), ShipId(0))

        assertEquals(ShipId(1), fleet.ship(ShipId(1))?.id)
        assertNull(fleet.ship(ShipId(7)))
    }

    @Test
    fun `withActive replaces the active ship preserving id and order`() {
        val fleet = Fleet(listOf(shipOf(0), shipOf(1)), ShipId(0))
        val movedActive = fleet.active.copy(kinematics = ShipKinematics(position = Vec2(5f, 6f)))

        val updated = fleet.withActive(movedActive)

        assertEquals(Vec2(5f, 6f), updated.active.kinematics.position)
        assertEquals("order is preserved", listOf(0L, 1L), updated.ships.map { it.id.value })
    }

    @Test
    fun `withActive rejects a ship that is not the active one`() {
        val fleet = Fleet(listOf(shipOf(0), shipOf(1)), ShipId(0))
        assertThrows(IllegalArgumentException::class.java) {
            fleet.withActive(fleet.ship(ShipId(1))!!.copy(kinematics = ShipKinematics(position = Vec2(1f, 1f))))
        }
    }

    @Test
    fun `switchActive changes the active id without reordering and is a no-op when already active`() {
        val fleet = Fleet(listOf(shipOf(0), shipOf(1)), ShipId(0))

        val switched = fleet.switchActive(ShipId(1))
        assertEquals(ShipId(1), switched.activeShipId)
        assertEquals("the ship list is unchanged", fleet.ships, switched.ships)

        assertSame("switching to the already-active ship is a no-op (same instance)", switched, switched.switchActive(ShipId(1)))
    }

    @Test
    fun `switchActive rejects an unowned id`() {
        val fleet = Fleet.starter()
        assertThrows(IllegalArgumentException::class.java) { fleet.switchActive(ShipId(42)) }
    }

    @Test
    fun `addShip appends keeping the list sorted by id and leaves the active ship unchanged`() {
        val fleet = Fleet.starter() // ship 0
        val added = fleet.addShip(shipOf(fleet.nextShipId().value, ShipRoster.SWIFT))

        assertEquals(listOf(0L, 1L), added.ships.map { it.id.value })
        assertEquals("active is unchanged by a buy", OwnedShip.STARTER_SHIP_ID, added.activeShipId)
        assertTrue(added.hasMultipleShips)
    }

    @Test
    fun `addShip rejects an already-owned id`() {
        val fleet = Fleet.starter()
        assertThrows(IllegalArgumentException::class.java) { fleet.addShip(shipOf(0)) }
    }

    @Test
    fun `nextShipId is one past the current maximum`() {
        val fleet = Fleet(listOf(shipOf(0), shipOf(3)), ShipId(0))
        assertEquals(ShipId(4), fleet.nextShipId())
    }

    // --- FleetResolver: BuyShip ---

    @Test
    fun `buy-ship adds the hull at the active ship position and deducts its price`() {
        val fleet = Fleet.starter().withActive(starter.copy(kinematics = ShipKinematics(position = Vec2(10f, 20f))))
        val yard = Shipyard.of(listOf(ShipRoster.SWIFT.id)) // SWIFT, price 1800

        val result = FleetResolver.resolve(fleet, credits = 5000L, shipyard = yard, order = FleetOrder.BuyShip(ShipRoster.SWIFT.id))

        assertTrue(result.changed)
        assertEquals(5000L - 1800L, result.credits)
        assertEquals(2, result.fleet.ships.size)
        val bought = result.fleet.ship(ShipId(1))!!
        assertEquals(ShipRoster.SWIFT.id, bought.type.id)
        assertEquals("the bought ship spawns where the player is docked", Vec2(10f, 20f), bought.kinematics.position)
        assertEquals("the active ship is unchanged by a buy", OwnedShip.STARTER_SHIP_ID, result.fleet.activeShipId)
    }

    @Test
    fun `buy-ship is a no-op when the yard does not offer the type`() {
        val fleet = Fleet.starter()
        val result =
            FleetResolver.resolve(
                fleet,
                credits = 99_999L,
                shipyard = Shipyard.EMPTY,
                order = FleetOrder.BuyShip(ShipRoster.SWIFT.id),
            )

        assertFalse(result.changed)
        assertEquals(99_999L, result.credits)
        assertEquals(1, result.fleet.ships.size)
    }

    @Test
    fun `buy-ship is a no-op when the player cannot afford the hull`() {
        val fleet = Fleet.starter()
        val yard = Shipyard.of(listOf(ShipRoster.PROSPECTOR.id)) // price 2500
        val result = FleetResolver.resolve(fleet, credits = 2499L, shipyard = yard, order = FleetOrder.BuyShip(ShipRoster.PROSPECTOR.id))

        assertFalse(result.changed)
        assertEquals(2499L, result.credits)
        assertEquals(1, result.fleet.ships.size)
    }

    // --- FleetResolver: SwitchActive ---

    @Test
    fun `switch-active changes the active ship when owned`() {
        val fleet = Fleet(listOf(shipOf(0), shipOf(1, ShipRoster.SWIFT)), ShipId(0))

        val result = FleetResolver.resolve(fleet, credits = 0L, shipyard = Shipyard.EMPTY, order = FleetOrder.SwitchActive(ShipId(1)))

        assertTrue(result.changed)
        assertEquals(ShipId(1), result.fleet.activeShipId)
    }

    @Test
    fun `switch-active is a no-op for an unowned ship`() {
        val fleet = Fleet.starter()
        val result = FleetResolver.resolve(fleet, credits = 0L, shipyard = Shipyard.EMPTY, order = FleetOrder.SwitchActive(ShipId(5)))

        assertFalse(result.changed)
        assertEquals(OwnedShip.STARTER_SHIP_ID, result.fleet.activeShipId)
    }

    @Test
    fun `switch-active is a no-op when the target is already active`() {
        val fleet = Fleet.starter()
        val result =
            FleetResolver.resolve(
                fleet,
                credits = 0L,
                shipyard = Shipyard.EMPTY,
                order = FleetOrder.SwitchActive(OwnedShip.STARTER_SHIP_ID),
            )

        assertFalse(result.changed)
    }

    @Test
    fun `the None order returns the inputs unchanged`() {
        val fleet = Fleet.starter()
        val result = FleetResolver.resolve(fleet, credits = 123L, shipyard = Shipyard.EMPTY, order = FleetOrder.None)

        assertFalse(result.changed)
        assertEquals(123L, result.credits)
        assertSame(fleet, result.fleet)
    }

    // --- UC48: reputation-gated BuyShip + faction-adjusted price -----------------------------------------

    private val league = FactionId("league")
    private val pricingParams = PricingParams()

    @Test
    fun `UC48 buy-ship of a gated hull is a no-op below the standing threshold`() {
        // PROSPECTOR (price 2500, unlockThreshold 10) is offered and affordable, but the player is neutral.
        val fleet = Fleet.starter()
        val yard = Shipyard.of(listOf(ShipRoster.PROSPECTOR.id))

        val result =
            FleetResolver.resolve(
                fleet,
                credits = 10_000L,
                shipyard = yard,
                order = FleetOrder.BuyShip(ShipRoster.PROSPECTOR.id),
                factionId = league,
                reputation = Reputation.EMPTY,
                pricingParams = pricingParams,
            )

        assertFalse("a gated hull below threshold must be a no-op (locked)", result.changed)
        assertEquals(10_000L, result.credits)
        assertEquals(1, result.fleet.ships.size)
    }

    @Test
    fun `UC48 buy-ship of a gated hull succeeds at or above threshold and charges the adjusted price`() {
        val standing = 10
        val fleet = Fleet.starter()
        val yard = Shipyard.of(listOf(ShipRoster.PROSPECTOR.id))
        val expectedPrice =
            FactionPricing.adjustedPrice(ShipRoster.PROSPECTOR.price, league, Reputation(mapOf(league to standing)), pricingParams)
        // mul = 0.99 at +10 ⇒ round(2500 * 0.99) = 2475 — a discount versus the 2500 base.
        assertEquals(2475L, expectedPrice)

        val result =
            FleetResolver.resolve(
                fleet,
                credits = 10_000L,
                shipyard = yard,
                order = FleetOrder.BuyShip(ShipRoster.PROSPECTOR.id),
                factionId = league,
                reputation = Reputation(mapOf(league to standing)),
                pricingParams = pricingParams,
            )

        assertTrue("unlocked at the threshold (>=)", result.changed)
        assertEquals("the faction-adjusted price is deducted (display==charge)", 10_000L - expectedPrice, result.credits)
        assertEquals(2, result.fleet.ships.size)
        assertEquals(ShipRoster.PROSPECTOR.id, result.fleet.ship(ShipId(1))!!.type.id)
    }

    @Test
    fun `UC48 a gated hull at a faction-less shipyard is permanently locked (authoring error)`() {
        val fleet = Fleet.starter()
        val yard = Shipyard.of(listOf(ShipRoster.PROSPECTOR.id))

        val result =
            FleetResolver.resolve(
                fleet,
                credits = 10_000L,
                shipyard = yard,
                order = FleetOrder.BuyShip(ShipRoster.PROSPECTOR.id),
                factionId = null,
                reputation = Reputation(mapOf(league to 999)),
                pricingParams = pricingParams,
            )

        assertFalse("a positive threshold at a null-faction shipyard is locked", result.changed)
        assertEquals(10_000L, result.credits)
    }

    @Test
    fun `UC48 an ungated hull still charges the faction-adjusted price at an allied standing`() {
        // SWIFT is ungated (threshold 0) but its price is still graded by standing (AC#2).
        val standing = 10
        val fleet = Fleet.starter()
        val yard = Shipyard.of(listOf(ShipRoster.SWIFT.id))
        val expected =
            FactionPricing.adjustedPrice(ShipRoster.SWIFT.price, league, Reputation(mapOf(league to standing)), pricingParams)
        // round(1800 * 0.99) = 1782.
        assertEquals(1782L, expected)

        val result =
            FleetResolver.resolve(
                fleet,
                credits = 10_000L,
                shipyard = yard,
                order = FleetOrder.BuyShip(ShipRoster.SWIFT.id),
                factionId = league,
                reputation = Reputation(mapOf(league to standing)),
                pricingParams = pricingParams,
            )

        assertTrue(result.changed)
        assertEquals(10_000L - expected, result.credits)
    }
}
