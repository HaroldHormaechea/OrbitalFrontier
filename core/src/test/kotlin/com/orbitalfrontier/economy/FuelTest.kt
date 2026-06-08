package com.orbitalfrontier.economy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Fuel] (UC07 AC#1/#3/#7) — the pure, immutable fuel tank and its speed-penalty model.
 *
 * Fuel is engine-free and deterministic, so these are plain JVM unit tests (AC#7). They pin the
 * contract movement and refuelling rely on:
 *  - [Fuel.speedFactor] is **exactly `1.0f`** at or above the low-fuel threshold (the byte-identical
 *    guarantee for pre-UC07 movement) and ramps **linearly** down to — never below — the floor as the
 *    tank empties (AC#3: low fuel slows the ship but never strands it: the factor is never 0);
 *  - [Fuel.isLow] flips at the threshold;
 *  - [Fuel.consume]/[Fuel.refill] clamp at empty/full and never mutate (every change returns a new value);
 *  - the value-object invariants (`capacity > 0`, `level in 0..capacity`).
 */
class FuelTest {
    private val params = FuelParams()
    private val tolerance = 1e-6

    @Test
    fun `a full default tank reports full fraction and no free space`() {
        val fuel = Fuel.full()

        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, fuel.level)
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, fuel.capacity)
        assertEquals(1.0, fuel.fraction.toDouble(), tolerance)
        assertEquals(0.0, fuel.remainingCapacity.toDouble(), tolerance)
    }

    @Test
    fun `fraction and remainingCapacity are derived from level over capacity`() {
        val fuel = Fuel(level = 30f, capacity = 100f)

        assertEquals(0.30, fuel.fraction.toDouble(), tolerance)
        assertEquals(70.0, fuel.remainingCapacity.toDouble(), tolerance)
    }

    // --- AC#3: speedFactor — exactly 1.0 at/above threshold, linear ramp, floor at empty, never 0 ---

    @Test
    fun `speedFactor is exactly 1f at a full tank`() {
        // Exactly 1.0f matters: FuelLimitedMovement short-circuits on it to keep movement byte-identical.
        assertEquals(1.0f, Fuel.full().speedFactor(params))
    }

    @Test
    fun `speedFactor is exactly 1f exactly at the low-fuel threshold`() {
        // fraction == lowFuelThreshold (0.20) is the boundary: still unaffected (>=, not >).
        val atThreshold = Fuel(level = 20f, capacity = 100f)

        assertEquals(0.20, atThreshold.fraction.toDouble(), tolerance)
        assertEquals(1.0f, atThreshold.speedFactor(params))
    }

    @Test
    fun `speedFactor is exactly 1f just above the threshold`() {
        assertEquals(1.0f, Fuel(level = 21f, capacity = 100f).speedFactor(params))
    }

    @Test
    fun `speedFactor ramps linearly below the threshold`() {
        // fraction 0.10 (half of the 0.20 threshold): floor + (1-floor) * (0.10/0.20)
        //   = 0.25 + 0.75 * 0.5 = 0.625.
        val half = Fuel(level = 10f, capacity = 100f)
        assertEquals(0.625, half.speedFactor(params).toDouble(), tolerance)

        // fraction 0.05 (quarter of the threshold): 0.25 + 0.75 * 0.25 = 0.4375.
        val quarter = Fuel(level = 5f, capacity = 100f)
        assertEquals(0.4375, quarter.speedFactor(params).toDouble(), tolerance)
    }

    @Test
    fun `speedFactor reaches exactly the floor at an empty tank and is never 0`() {
        val empty = Fuel(level = 0f, capacity = 100f)

        // AC#3: at empty the factor is the floor (0.25), never 0 — the ship can always limp to refuel.
        assertEquals(params.floorSpeedFraction, empty.speedFactor(params))
        assertEquals(0.25f, empty.speedFactor(params))
        assertTrue("the speed factor must never be 0 (never stranded)", empty.speedFactor(params) > 0f)
    }

    @Test
    fun `speedFactor is continuous at the threshold (no jump from the ramp to the flat region)`() {
        val justBelow = Fuel(level = 19.999f, capacity = 100f).speedFactor(params)
        val atThreshold = Fuel(level = 20f, capacity = 100f).speedFactor(params)

        // Approaching the threshold from below, the ramp tends to 1.0 — no discontinuity.
        assertEquals(atThreshold.toDouble(), justBelow.toDouble(), 1e-3)
    }

    // --- isLow ---

    @Test
    fun `isLow is false at or above the threshold and true below it`() {
        assertFalse("at the threshold the tank is not yet low", Fuel(level = 20f, capacity = 100f).isLow(params))
        assertFalse("above the threshold the tank is not low", Fuel(level = 50f, capacity = 100f).isLow(params))
        assertTrue("below the threshold the tank is low", Fuel(level = 19f, capacity = 100f).isLow(params))
        assertTrue("an empty tank is low", Fuel(level = 0f, capacity = 100f).isLow(params))
    }

    // --- consume / refill: clamping + immutability ---

    @Test
    fun `consume reduces the level by the burned amount`() {
        val after = Fuel(level = 50f, capacity = 100f).consume(12.5f)

        assertEquals(37.5, after.level.toDouble(), tolerance)
    }

    @Test
    fun `consume clamps at an empty tank rather than going negative`() {
        val after = Fuel(level = 3f, capacity = 100f).consume(10f)

        assertEquals("burning more than is left empties the tank, never negative", 0f, after.level)
    }

    @Test
    fun `consume of zero returns the same instance`() {
        val fuel = Fuel(level = 40f, capacity = 100f)

        assertSame(fuel, fuel.consume(0f))
    }

    @Test
    fun `consume rejects a negative amount`() {
        assertThrows(IllegalArgumentException::class.java) { Fuel.full().consume(-1f) }
    }

    @Test
    fun `refill raises the level by the added amount`() {
        val after = Fuel(level = 40f, capacity = 100f).refill(25f)

        assertEquals(65.0, after.level.toDouble(), tolerance)
    }

    @Test
    fun `refill clamps at capacity, discarding overflow`() {
        val after = Fuel(level = 90f, capacity = 100f).refill(25f)

        assertEquals("topping past full clamps at capacity", 100f, after.level)
    }

    @Test
    fun `refill of zero returns the same instance`() {
        val fuel = Fuel(level = 40f, capacity = 100f)

        assertSame(fuel, fuel.refill(0f))
    }

    @Test
    fun `refill rejects a negative amount`() {
        assertThrows(IllegalArgumentException::class.java) { Fuel(level = 10f, capacity = 100f).refill(-1f) }
    }

    @Test
    fun `consume and refill never mutate the original value`() {
        val fuel = Fuel(level = 50f, capacity = 100f)

        val burned = fuel.consume(10f)
        val filled = fuel.refill(10f)

        assertEquals("the original is untouched", 50f, fuel.level)
        assertNotSame(fuel, burned)
        assertNotSame(fuel, filled)
    }

    // --- value-object invariants ---

    @Test
    fun `a non-positive capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Fuel(level = 0f, capacity = 0f) }
        assertThrows(IllegalArgumentException::class.java) { Fuel(level = 0f, capacity = -5f) }
    }

    @Test
    fun `a level outside 0_capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Fuel(level = -1f, capacity = 100f) }
        assertThrows(IllegalArgumentException::class.java) { Fuel(level = 101f, capacity = 100f) }
    }
}
