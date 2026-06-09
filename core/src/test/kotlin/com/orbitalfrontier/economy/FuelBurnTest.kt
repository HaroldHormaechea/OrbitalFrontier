package com.orbitalfrontier.economy

import com.orbitalfrontier.power.PowerParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [FuelBurn] (UC07 AC#2/#7) — the single shared fuel-burn step used by both the live
 * device loop and the deterministic sim/replay path.
 *
 * Burn is engine-free and deterministic, so these are plain JVM unit tests (AC#7). They pin AC#2:
 *  - one step burns `PowerModel.drawAt(thrusting) · dt` — base load while coasting, base + thrust
 *    while thrusting (coasting costs little, thrusting costs more);
 *  - burn is clamped at an empty tank (never negative — the ship coasts at the speed floor rather
 *    than failing, the "never stranded" guarantee);
 *  - a non-negative `dt` is required (fail-fast on a programmer error).
 */
class FuelBurnTest {
    // Default power tuning (UC16-calibrated): coast draw == baseModuleDraw, thrust draw ==
    // baseModuleDraw + thrustDraw. Expected burns below are derived from these fields, not frozen
    // literals, so a future re-tune doesn't re-break the formula assertions.
    private val power = PowerParams()
    private val dt = 0.5f
    private val tolerance = 1e-6

    @Test
    fun `coasting burns the base draw times dt`() {
        val start = Fuel(level = 50f, capacity = 100f)
        val after = FuelBurn.step(start, thrusting = false, powerParams = power, dt = dt)

        // coasting burns baseModuleDraw · dt (the always-on hotel load).
        val expected = start.level - power.baseModuleDraw * dt
        assertEquals(expected.toDouble(), after.level.toDouble(), tolerance)
    }

    @Test
    fun `thrusting burns the higher draw times dt`() {
        val start = Fuel(level = 50f, capacity = 100f)
        val after = FuelBurn.step(start, thrusting = true, powerParams = power, dt = dt)

        // thrusting burns (baseModuleDraw + thrustDraw) · dt.
        val expected = start.level - (power.baseModuleDraw + power.thrustDraw) * dt
        assertEquals(expected.toDouble(), after.level.toDouble(), tolerance)
    }

    @Test
    fun `thrusting burns strictly more than coasting over the same step`() {
        val start = Fuel(level = 50f, capacity = 100f)
        val coasted = FuelBurn.step(start, thrusting = false, powerParams = power, dt = dt)
        val thrusted = FuelBurn.step(start, thrusting = true, powerParams = power, dt = dt)

        assertEquals(true, thrusted.level < coasted.level)
    }

    @Test
    fun `a near-empty tank clamps at zero rather than going negative`() {
        // Start with strictly less fuel than a single thrust step would burn, so the step overshoots
        // empty regardless of the tuning numbers — derived from the draw, not a frozen 0.1 literal.
        val oneStepBurn = (power.baseModuleDraw + power.thrustDraw) * dt
        val after = FuelBurn.step(Fuel(level = oneStepBurn * 0.5f, capacity = 100f), thrusting = true, powerParams = power, dt = dt)

        assertEquals("burning past empty clamps at zero (never stranded)", 0f, after.level)
    }

    @Test
    fun `a zero-length step burns nothing and returns the same fuel value`() {
        val fuel = Fuel(level = 50f, capacity = 100f)

        // drawAt * 0 == 0, and Fuel.consume(0) returns the same instance.
        assertSame(fuel, FuelBurn.step(fuel, thrusting = true, powerParams = power, dt = 0f))
    }

    @Test
    fun `a negative dt is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FuelBurn.step(Fuel.full(), thrusting = false, powerParams = power, dt = -0.1f)
        }
    }
}
