package com.orbitalfrontier.power

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PowerModel] + [PowerParams] + [PowerStatus] (UC07 AC#4/#7) — the pure, stateless
 * power/energy model that drives fuel burn.
 *
 * Power is engine-free and deterministic, so these are plain JVM unit tests (AC#7). They pin AC#4:
 *  - [PowerModel.drawAt] is the base hotel load while coasting and base + thrust while thrusting
 *    (a loaded ship sips fuel even idle; hard maneuvering spikes it);
 *  - [PowerModel.status] exposes **reactor output, total module draw, and the burn rate**, with the
 *    burn rate equal to the total draw for the rate-based MVP;
 *  - the [PowerParams] value-object invariants.
 */
class PowerModelTest {
    private val params = PowerParams()
    private val tolerance = 1e-6

    // --- AC#4: draw rate = base + (thrusting ? thrust : 0) ---

    @Test
    fun `drawAt while coasting is the always-on base module load`() {
        assertEquals(params.baseModuleDraw.toDouble(), PowerModel.drawAt(false, params).toDouble(), tolerance)
        assertEquals(0.5, PowerModel.drawAt(false, params).toDouble(), tolerance)
    }

    @Test
    fun `drawAt while thrusting adds the thrust draw on top of the base load`() {
        assertEquals(
            (params.baseModuleDraw + params.thrustDraw).toDouble(),
            PowerModel.drawAt(true, params).toDouble(),
            tolerance,
        )
        assertEquals(2.0, PowerModel.drawAt(true, params).toDouble(), tolerance)
    }

    @Test
    fun `thrusting draws strictly more fuel than coasting`() {
        // The "coasting is cheap, hard maneuvering spikes it" contract (AC#2 underpinning).
        assertEquals(true, PowerModel.drawAt(true, params) > PowerModel.drawAt(false, params))
    }

    // --- AC#4: status exposes reactorOutput + totalDraw + burnRate ---

    @Test
    fun `status while coasting reports reactor output, total draw, and the matching burn rate`() {
        val status = PowerModel.status(false, params)

        assertEquals(params.reactorOutput.toDouble(), status.reactorOutput.toDouble(), tolerance)
        assertEquals(params.baseModuleDraw.toDouble(), status.totalDraw.toDouble(), tolerance)
        // Rate-based MVP: the burn rate equals the total draw 1:1.
        assertEquals(status.totalDraw.toDouble(), status.burnRate.toDouble(), tolerance)
    }

    @Test
    fun `status while thrusting reflects the higher total draw and burn rate`() {
        val status = PowerModel.status(true, params)

        assertEquals(params.reactorOutput.toDouble(), status.reactorOutput.toDouble(), tolerance)
        assertEquals((params.baseModuleDraw + params.thrustDraw).toDouble(), status.totalDraw.toDouble(), tolerance)
        assertEquals(status.totalDraw.toDouble(), status.burnRate.toDouble(), tolerance)
    }

    @Test
    fun `status burn rate equals drawAt for both thrust states`() {
        // status() must report exactly the rate FuelBurn consumes (drawAt) — one definition, no drift.
        assertEquals(PowerModel.drawAt(false, params), PowerModel.status(false, params).burnRate)
        assertEquals(PowerModel.drawAt(true, params), PowerModel.status(true, params).burnRate)
    }

    @Test
    fun `reactor output is reported even though it does not yet cap the draw`() {
        // Brownout/throttle is deferred: total draw may exceed reactor output, but both are observable.
        val thirsty = PowerParams(reactorOutput = 1.0f, baseModuleDraw = 0.5f, thrustDraw = 1.5f)
        val status = PowerModel.status(true, thirsty)

        assertEquals(1.0, status.reactorOutput.toDouble(), tolerance)
        assertEquals(2.0, status.totalDraw.toDouble(), tolerance)
        assertEquals("draw is uncapped for the MVP — it may exceed reactor output", true, status.totalDraw > status.reactorOutput)
    }

    // --- PowerParams invariants ---

    @Test
    fun `a non-positive reactor output is rejected`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PowerParams(reactorOutput = 0f) }
    }

    @Test
    fun `a negative base or thrust draw is rejected`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PowerParams(baseModuleDraw = -0.1f) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PowerParams(thrustDraw = -0.1f) }
    }
}
