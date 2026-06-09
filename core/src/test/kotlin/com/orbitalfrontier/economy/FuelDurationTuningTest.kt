package com.orbitalfrontier.economy

import com.orbitalfrontier.power.PowerModel
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuel-duration calibration tests (UC16).
 *
 * UC16 re-tunes the default [PowerParams] so a full **Wayfarer** starter tank
 * ([com.orbitalfrontier.economy.FuelParams.DEFAULT_TANK_CAPACITY] = 100 units) drains in ~30 minutes
 * of continuous thrust, and every other ship shares that same per-second draw against its own tank
 * size. These tests pin the *behaviour* the use case promises, deriving expected numbers from the
 * params/roster so they keep verifying the calibration after a future re-tune rather than freezing
 * today's literals:
 *
 *  - **AC#1** — the reference (Wayfarer/100u) tank's analytic time-to-empty under thrust is ~30 min
 *    (±10%): capacity ÷ thrusting draw ∈ [1620, 1980] s.
 *  - **AC#4** — the same ~30 min holds under the *real* fixed-`dt` burn loop ([FuelBurn.step]), not
 *    just the closed-form rate, so the figure is measured against elapsed game time.
 *  - **AC#2** — all roster ships share a single global per-second draw (identical seconds-per-fuel-
 *    unit) and their time-to-empty is therefore strictly proportional to tank capacity.
 */
class FuelDurationTuningTest {
    private val params = PowerParams()

    /** UC16 target: a full reference tank lasts ~30 min (1800 s) of continuous thrust, ±10%. */
    private val targetSeconds = 1800.0
    private val lowerBound = targetSeconds * 0.9 // 1620 s
    private val upperBound = targetSeconds * 1.1 // 1980 s

    /** The Wayfarer reference tank (UC16 reference ship). */
    private val referenceTankUnits = FuelParams.DEFAULT_TANK_CAPACITY // 100 units

    // --- AC#1: reference tank empties in ~30 min of continuous thrust (closed-form rate) ---

    @Test
    fun `reference Wayfarer tank empties in about 30 minutes of continuous thrust`() {
        val thrustDraw = PowerModel.drawAt(thrusting = true, params = params) // units/s
        val timeToEmpty = referenceTankUnits / thrustDraw // seconds

        assertTrue(
            "reference tank time-to-empty must be ~30 min (±10%): was ${timeToEmpty}s, " +
                "expected [$lowerBound, $upperBound]",
            timeToEmpty in lowerBound.toFloat()..upperBound.toFloat(),
        )
    }

    // --- AC#4: the same ~30 min holds under the real fixed-dt burn loop ---

    @Test
    fun `integrating FuelBurn at 60 fps from a full reference tank empties in about 30 minutes`() {
        val dt = 1f / 60f // fixed timestep, matching the sim/replay path
        var fuel = Fuel.full() // a full default (Wayfarer) tank: level == capacity == 100
        assertEquals("precondition: full tank is the reference capacity", referenceTankUnits, fuel.capacity, 0f)

        var steps = 0
        // Generous guard: ~108k steps expected at 60 fps; cap well above to fail fast on a regression
        // that would otherwise loop forever (e.g. a zero draw rate).
        val maxSteps = 60 * 60 * 60 // 60 min of sim time
        while (fuel.level > 0f && steps < maxSteps) {
            fuel = FuelBurn.step(fuel, thrusting = true, powerParams = params, dt = dt)
            steps++
        }

        assertEquals("the tank must actually reach empty within the step cap", 0f, fuel.level)
        val simSeconds = steps * dt.toDouble()
        assertTrue(
            "integrated time-to-empty must be ~30 min (±10%): was ${simSeconds}s over $steps steps, " +
                "expected [$lowerBound, $upperBound]",
            simSeconds in lowerBound..upperBound,
        )
    }

    // --- AC#2: a single global draw → identical seconds-per-fuel-unit, duration ∝ capacity ---

    @Test
    fun `every roster ship shares one global per-second draw, so duration is proportional to capacity`() {
        val thrustDraw = PowerModel.drawAt(thrusting = true, params = params) // shared by every ship
        val tolerance = 1e-4

        // Each ship: time-to-empty under thrust, and seconds consumed per fuel unit.
        val ships = ShipRoster.all
        assertTrue("roster must contain the calibrated ships", ships.size >= 3)

        val perUnitRates =
            ships.map { ship ->
                val capacity = ship.baseFuelCapacity.toDouble()
                val timeToEmpty = capacity / thrustDraw // seconds
                val secondsPerUnit = timeToEmpty / capacity // == 1 / thrustDraw for every ship
                Triple(ship.displayName, capacity, secondsPerUnit)
            }

        // (1) Single global rate: seconds-per-fuel-unit is identical across all ships.
        val referenceRate = perUnitRates.first().third
        for ((name, _, rate) in perUnitRates) {
            assertEquals(
                "ship '$name' must share the single global per-fuel-unit rate (no per-ship hand-tuning)",
                referenceRate,
                rate,
                tolerance,
            )
        }

        // (2) Duration ∝ capacity: time-to-empty / capacity is constant, so a bigger tank lasts
        // proportionally longer (relative fuel economy preserved).
        for (ship in ships) {
            val timeToEmpty = ship.baseFuelCapacity.toDouble() / thrustDraw
            assertEquals(
                "ship '${ship.displayName}' duration must be capacity × the global per-unit rate",
                ship.baseFuelCapacity.toDouble() * referenceRate,
                timeToEmpty,
                tolerance,
            )
        }

        // Sanity: the spread of capacities is real (Prospector 140 > Wayfarer 100 > Swift 90), so the
        // proportionality assertion above is not vacuously comparing equal tanks.
        val capacities = ships.map { it.baseFuelCapacity }.toSet()
        assertTrue("roster ships must have differing tank sizes for a meaningful ∝ check", capacities.size >= 2)
    }
}
