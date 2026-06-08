package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.outfit.InstallResult
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.StatDelta
import com.orbitalfrontier.outfit.Upgrade
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.outfit.UpgradeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OwnedShip] (UC09 AC#2/#5) — focused on [OwnedShip.withLoadout], the **single
 * re-derivation point** for cargo/fuel capacities after a fit change.
 *
 * Pins the Δ-capacity propagation contract: installing a cargo pod / fuel tank raises the matching
 * capacity, removing it **reverts** the capacity, contents/level are preserved across a fit change,
 * and a capacity that shrinks below what is held clamps the contents (deterministically) so the
 * [Cargo]/[Fuel] invariants always hold.
 */
class OwnedShipTest {
    private val starterType = ShipRoster.STARTER
    private val engineSlots = starterType.slotCount(SlotCategory.ENGINES)
    private val cargoSlots = starterType.slotCount(SlotCategory.CARGO)
    private val fuelSlots = starterType.slotCount(SlotCategory.FUEL_TANK)

    private fun loadoutOf(
        category: SlotCategory,
        slotCount: Int,
        id: com.orbitalfrontier.outfit.UpgradeId,
    ): Loadout = (Loadout.EMPTY.install(category, slotCount, id) as InstallResult.Installed).loadout

    @Test
    fun `a fresh starter derives exactly today's capacities`() {
        val ship = OwnedShip.starter()

        assertEquals(OwnedShip.STARTER_SHIP_ID, ship.id)
        assertEquals(Cargo.DEFAULT_CAPACITY, ship.cargo.capacity)
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, ship.fuel.capacity, 0f)
        assertEquals("a fresh ship starts with a full tank", FuelParams.DEFAULT_TANK_CAPACITY, ship.fuel.level, 0f)
        assertTrue(ship.cargo.contents.isEmpty())
        assertTrue(ship.loadout.isEmpty)
    }

    @Test
    fun `fresh spawns a bought hull at the given position with derived capacities`() {
        val ship = OwnedShip.fresh(ShipId(2), ShipRoster.SWIFT, Vec2(10f, -5f))

        assertEquals(Vec2(10f, -5f), ship.kinematics.position)
        assertEquals(ShipRoster.SWIFT.baseCargoCapacity, ship.cargo.capacity)
        assertEquals(ShipRoster.SWIFT.baseFuelCapacity, ship.fuel.capacity, 0f)
    }

    @Test
    fun `installing a cargo pod raises cargo capacity and preserves contents`() {
        val ship =
            OwnedShip.starter().copy(cargo = Cargo(mapOf(ResourceType.IRON_ORE to 30), Cargo.DEFAULT_CAPACITY))

        val refit = ship.withLoadout(loadoutOf(SlotCategory.CARGO, cargoSlots, UpgradeCatalog.CARGO_POD_I))

        assertEquals(Cargo.DEFAULT_CAPACITY + 25, refit.cargo.capacity)
        assertEquals("contents survive the refit", mapOf(ResourceType.IRON_ORE to 30), refit.cargo.contents)
        // Fuel is untouched by a cargo pod.
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, refit.fuel.capacity, 0f)
    }

    @Test
    fun `installing a fuel tank raises fuel capacity and preserves the level`() {
        val ship = OwnedShip.starter().copy(fuel = Fuel(level = 40f, capacity = FuelParams.DEFAULT_TANK_CAPACITY))

        val refit = ship.withLoadout(loadoutOf(SlotCategory.FUEL_TANK, fuelSlots, UpgradeCatalog.FUEL_TANK_I))

        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY + 50f, refit.fuel.capacity, 0f)
        assertEquals("the fuel level survives the refit", 40f, refit.fuel.level, 0f)
    }

    @Test
    fun `removing an upgrade reverts the capacity back to the base`() {
        val withPod =
            OwnedShip.starter().withLoadout(loadoutOf(SlotCategory.CARGO, cargoSlots, UpgradeCatalog.CARGO_POD_I))
        assertEquals(Cargo.DEFAULT_CAPACITY + 25, withPod.cargo.capacity)

        // Revert to an empty fit — the capacity drops back to the starter base.
        val reverted = withPod.withLoadout(Loadout.EMPTY)
        assertEquals(Cargo.DEFAULT_CAPACITY, reverted.cargo.capacity)
    }

    @Test
    fun `shrinking cargo capacity below what is held clamps the contents to fit`() {
        // A hold of 70 units in a pod-expanded 75-cap ship (CARGO_POD_II = +60 ⇒ 110, but pin to 75 here).
        val expanded =
            OwnedShip.starter()
                .withLoadout(loadoutOf(SlotCategory.CARGO, cargoSlots, UpgradeCatalog.CARGO_POD_I)) // cap 75
                .let { it.copy(cargo = Cargo(mapOf(ResourceType.IRON_ORE to 70), it.cargo.capacity)) }
        assertEquals(70, expanded.cargo.usedUnits)

        // Removing the pod drops capacity to 50; the 70 units must clamp down to 50 (never over capacity).
        val shrunk = expanded.withLoadout(Loadout.EMPTY)

        assertEquals(Cargo.DEFAULT_CAPACITY, shrunk.cargo.capacity)
        assertEquals("contents clamp to the new capacity", 50, shrunk.cargo.usedUnits)
        assertTrue("the cargo invariant holds (used <= capacity)", shrunk.cargo.usedUnits <= shrunk.cargo.capacity)
    }

    @Test
    fun `shrinking fuel capacity coerces the level down to the new capacity`() {
        // A ship with a fuel tank (cap 150) filled to 150, then the tank removed (cap back to 100).
        val withTank =
            OwnedShip.starter()
                .withLoadout(loadoutOf(SlotCategory.FUEL_TANK, fuelSlots, UpgradeCatalog.FUEL_TANK_I)) // cap 150
                .let { it.copy(fuel = Fuel(level = it.fuel.capacity, capacity = it.fuel.capacity)) } // full at 150
        assertEquals(150f, withTank.fuel.level, 0f)

        val shrunk = withTank.withLoadout(Loadout.EMPTY)

        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, shrunk.fuel.capacity, 0f)
        assertEquals("the level coerces down to the new capacity", FuelParams.DEFAULT_TANK_CAPACITY, shrunk.fuel.level, 0f)
    }

    // --- UC11: crew is the single-mutation point [OwnedShip.withCrew], clamped to 0..crewCapacity ---

    /** A crew-quarters slot category count for the starter (the starter ships one CREW_QUARTERS slot). */
    private val crewSlots = starterType.slotCount(SlotCategory.CREW_QUARTERS)

    /** A crew-quarters upgrade id used only in this test (no crew-quarters part exists in the MVP catalog yet). */
    private val crewQuartersI = UpgradeId("crew-quarters-i-test")

    /**
     * A synthetic catalog whose sole part is a CREW_QUARTERS upgrade adding +2 crew capacity. The MVP
     * catalog ships no crew-quarters part yet, so without this the "shrink crew capacity" branch of
     * [OwnedShip.withLoadout] would be dead code in tests — this catalog makes it reachable.
     */
    private val crewCatalog: UpgradeCatalog =
        UpgradeCatalog(
            listOf(
                Upgrade(
                    id = crewQuartersI,
                    category = SlotCategory.CREW_QUARTERS,
                    displayName = "Crew Quarters I (test)",
                    price = 200,
                    statDeltas = StatDelta(crew = 2),
                ),
            ),
        )

    @Test
    fun `withCrew clamps a request above capacity down to capacity`() {
        // The starter's base crew capacity is 2 (no crew-quarters part installed).
        val crewed = OwnedShip.starter().withCrew(5)

        assertEquals("an over-capacity request clamps to capacity", 2, crewed.crew)
    }

    @Test
    fun `withCrew clamps a negative request up to zero`() {
        val crewed = OwnedShip.starter().withCrew(-3)

        assertEquals("a negative request clamps to zero", 0, crewed.crew)
    }

    @Test
    fun `withCrew accepts an in-range crew count verbatim`() {
        val crewed = OwnedShip.starter().withCrew(1)

        assertEquals(1, crewed.crew)
    }

    @Test
    fun `removing a crew-quarters part that shrinks capacity clamps crew down to fit`() {
        // Install the synthetic crew-quarters part (capacity 2 -> 4), crew it to a full 4, then remove
        // the part: capacity drops back to the base 2 and the 4 crew must clamp down to 2 (never over
        // capacity — the withLoadout re-clamp, exercised here against a real capacity shrink).
        val expanded =
            OwnedShip.starter()
                .withLoadout(loadoutOf(SlotCategory.CREW_QUARTERS, crewSlots, crewQuartersI), crewCatalog)
                .withCrew(4, crewCatalog)
        assertEquals("precondition: crew filled to the expanded capacity of 4", 4, expanded.crew)

        val shrunk = expanded.withLoadout(Loadout.EMPTY, crewCatalog)

        assertEquals("crew clamps down to the new (base) capacity", 2, shrunk.crew)
        assertTrue("the crew invariant holds (crew <= capacity)", shrunk.crew <= 2)
    }
}
