package com.orbitalfrontier.economy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Refueling] (UC07 AC#5/#7) — converting **Hydrogen** cargo into fuel.
 *
 * Refuelling is engine-free and deterministic, so these are plain JVM unit tests (AC#7). They pin the
 * contract the station hub (and the sim/replay path) relies on:
 *  - the converted amount is bounded by **both** the hydrogen on board and the tank space remaining;
 *  - conversion is **whole-unit** — a partially-fillable last unit is left in the hold, never split;
 *  - the [FuelParams.hydrogenToFuelRatio] is applied to whole hydrogen units;
 *  - it is a **no-op** (inputs returned unchanged, `transferredUnits = 0`) when the action is
 *    [RefuelAction.NONE], when there is no hydrogen, or when the tank is already full.
 */
class RefuelingTest {
    private val params = FuelParams() // ratio 1.0, capacity 100
    private val tolerance = 1e-6

    private fun cargoWith(hydrogen: Int): Cargo = Cargo(mapOf(ResourceType.HYDROGEN to hydrogen), 50)

    // --- happy path: convert hydrogen into fuel, drawing it out of the hold ---

    @Test
    fun `refuelling converts hydrogen into fuel and removes it from the hold`() {
        // Tank: 60/100 (40 free). Hold: 20 hydrogen. Ratio 1.0 ⇒ convert all 20 (both bounds allow it).
        val result = Refueling.resolve(Fuel(level = 60f, capacity = 100f), cargoWith(20), RefuelAction.REFUEL, params)

        assertEquals("all 20 hydrogen converted", 20, result.transferredUnits)
        assertEquals("tank topped up by 20 fuel", 80.0, result.fuel.level.toDouble(), tolerance)
        assertTrue("the hold's hydrogen is fully drained", result.cargo.contents[ResourceType.HYDROGEN] == null)
        assertEquals("the drained hold is now empty", 0, result.cargo.usedUnits)
    }

    // --- AC#5: bounded by tank space remaining ---

    @Test
    fun `conversion is bounded by the tank space remaining`() {
        // Tank: 90/100 (only 10 free). Hold: 50 hydrogen. Only 10 units fit.
        val result = Refueling.resolve(Fuel(level = 90f, capacity = 100f), cargoWith(50), RefuelAction.REFUEL, params)

        assertEquals("only the 10 units that fit are converted", 10, result.transferredUnits)
        assertEquals("the tank ends exactly full", 100f, result.fuel.level)
        assertEquals("the unconverted hydrogen stays in the hold", 40, result.cargo.contents[ResourceType.HYDROGEN])
    }

    // --- AC#5: bounded by hydrogen available ---

    @Test
    fun `conversion is bounded by the hydrogen available`() {
        // Tank: 10/100 (90 free). Hold: 7 hydrogen. Only 7 units can be converted.
        val result = Refueling.resolve(Fuel(level = 10f, capacity = 100f), cargoWith(7), RefuelAction.REFUEL, params)

        assertEquals("only the 7 available hydrogen are converted", 7, result.transferredUnits)
        assertEquals(17.0, result.fuel.level.toDouble(), tolerance)
        assertTrue("the hold's hydrogen is fully drained", result.cargo.contents[ResourceType.HYDROGEN] == null)
    }

    // --- whole-unit behaviour: a partially-fillable last unit is left in the hold ---

    @Test
    fun `only whole hydrogen units are converted, leaving a partially-fillable last unit in the hold`() {
        // Tank: 89.5/100 (10.5 free). Ratio 1.0 ⇒ 10 whole units fit (the 11th would overflow by 0.5),
        // so the half-unit of tank space is left unused and that hydrogen stays in the hold.
        val result = Refueling.resolve(Fuel(level = 89.5f, capacity = 100f), cargoWith(20), RefuelAction.REFUEL, params)

        assertEquals("10 whole units convert; the partial 11th is left", 10, result.transferredUnits)
        assertEquals(99.5, result.fuel.level.toDouble(), tolerance)
        assertEquals("the un-converted hydrogen remains", 10, result.cargo.contents[ResourceType.HYDROGEN])
    }

    @Test
    fun `the hydrogen-to-fuel ratio is applied to whole units`() {
        // ratio 2.0: each hydrogen yields 2 fuel. Tank: 90/100 (10 free) ⇒ only 5 whole units fit
        // (5 * 2 = 10 fuel), leaving 5 hydrogen in the hold.
        val richer = FuelParams(hydrogenToFuelRatio = 2.0f)
        val result = Refueling.resolve(Fuel(level = 90f, capacity = 100f), cargoWith(10), RefuelAction.REFUEL, richer)

        assertEquals("5 whole units fit at ratio 2.0", 5, result.transferredUnits)
        assertEquals("the tank ends exactly full", 100f, result.fuel.level)
        assertEquals("the remaining hydrogen stays in the hold", 5, result.cargo.contents[ResourceType.HYDROGEN])
    }

    // --- no-ops: action NONE, no hydrogen, full tank ---

    @Test
    fun `a NONE action returns the inputs unchanged`() {
        val fuel = Fuel(level = 50f, capacity = 100f)
        val cargo = cargoWith(20)

        val result = Refueling.resolve(fuel, cargo, RefuelAction.NONE, params)

        assertEquals(0, result.transferredUnits)
        assertSame("fuel is returned unchanged", fuel, result.fuel)
        assertSame("cargo is returned unchanged", cargo, result.cargo)
    }

    @Test
    fun `refuelling with no hydrogen aboard is a no-op`() {
        val fuel = Fuel(level = 50f, capacity = 100f)
        val emptyHold = Cargo.empty(50)

        val result = Refueling.resolve(fuel, emptyHold, RefuelAction.REFUEL, params)

        assertEquals(0, result.transferredUnits)
        assertSame(fuel, result.fuel)
        assertSame(emptyHold, result.cargo)
    }

    @Test
    fun `refuelling a full tank is a no-op even with hydrogen aboard`() {
        val fullTank = Fuel.full()
        val cargo = cargoWith(20)

        val result = Refueling.resolve(fullTank, cargo, RefuelAction.REFUEL, params)

        assertEquals(0, result.transferredUnits)
        assertSame("a full tank leaves the fuel unchanged", fullTank, result.fuel)
        assertSame("a full tank leaves the cargo unchanged", cargo, result.cargo)
    }

    @Test
    fun `refuelling converts only hydrogen, leaving other resources untouched`() {
        val mixedHold = Cargo(mapOf(ResourceType.HYDROGEN to 10, ResourceType.IRON_ORE to 15), 50)
        val result = Refueling.resolve(Fuel(level = 50f, capacity = 100f), mixedHold, RefuelAction.REFUEL, params)

        assertEquals(10, result.transferredUnits)
        assertTrue("hydrogen is drained", result.cargo.contents[ResourceType.HYDROGEN] == null)
        assertEquals("iron ore is untouched", 15, result.cargo.contents[ResourceType.IRON_ORE])
    }
}
