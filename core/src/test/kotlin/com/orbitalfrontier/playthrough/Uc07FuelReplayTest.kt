package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.FuelParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC07 low-fuel playthrough (AC#3/#7), following the record→persist→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc07-low-fuel` artifact starts the ship at a nearly-empty tank and thrusts north
 * while a pinned thirsty power draw burns the tank below the low-fuel threshold. This test is a
 * **control-vs-depleted** experiment: it replays the *exact same input script* twice — once at the
 * recorded low starting fuel (depleted), once with the initial tank swapped to full (control) — and
 * asserts the central UC07 claim (AC#3):
 *  - the depleted run's **terminal speed is strictly slower** than the control's (low fuel reduces
 *    effective max speed);
 *  - the depleted run ends **below the low-fuel threshold** (the speed-penalty regime actually
 *    engaged) with **nonzero remaining fuel** (never stranded — fuel never causes a hard stop);
 *  - the control run stays above the threshold (so its full speed is the honest baseline).
 *
 * It also pins the determinism contract: replaying the depleted artifact twice yields bit-identical
 * end states. The artifact is reproduced from [PlaythroughFixtures.uc07LowFuel] and guarded by
 * [PlaythroughFixtureTest].
 */
class Uc07FuelReplayTest {
    private val fuelParams = FuelParams()

    private fun loadLowFuel(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC07_LOW_FUEL)

    /** The same playthrough with its initial tank swapped to full — the control for the comparison. */
    private fun toFullFuelControl(depleted: Playthrough): Playthrough {
        val initial = requireNotNull(depleted.initialState) { "the low-fuel fixture must carry an initial snapshot" }
        return depleted.copy(initialState = initial.copy(fuel = FuelParams.DEFAULT_TANK_CAPACITY))
    }

    @Test
    fun `low fuel reduces terminal speed but never strands the ship`() {
        val depleted = loadLowFuel()
        val control = toFullFuelControl(depleted)

        val depletedFinal = ReplayRunner().run(depleted).finalState
        val controlFinal = ReplayRunner().run(control).finalState

        // AC#3: the depleted run ends below the threshold — the speed-penalty regime actually engaged.
        assertTrue(
            "the depleted run must end below the low-fuel threshold (fraction=${depletedFinal.fuel.fraction})",
            depletedFinal.fuel.isLow(fuelParams),
        )
        // AC#3: never stranded — fuel never causes a hard stop, so some fuel always remains.
        assertTrue(
            "the depleted run must retain nonzero fuel (never stranded), was ${depletedFinal.fuel.level}",
            depletedFinal.fuel.level > 0f,
        )
        // The control stays above the threshold, so its full speed is the honest baseline.
        assertEquals(
            "the control run (full tank) must stay out of the low-fuel regime",
            false,
            controlFinal.fuel.isLow(fuelParams),
        )

        // AC#3 core claim: same inputs, lower fuel ⇒ strictly slower terminal speed.
        assertTrue(
            "low fuel must reduce terminal speed: depleted=${depletedFinal.ship.speed} control=${controlFinal.ship.speed}",
            depletedFinal.ship.speed < controlFinal.ship.speed,
        )
        // Sanity: the penalty is substantial, not a rounding wisp — guards against the speed cap not
        // being applied at all (a regression where fuel no longer feeds FuelLimitedMovement).
        assertTrue(
            "the speed penalty should be substantial: depleted=${depletedFinal.ship.speed} control=${controlFinal.ship.speed}",
            controlFinal.ship.speed - depletedFinal.ship.speed > 10f,
        )
    }

    @Test
    fun `replay through fuel burn is deterministic`() {
        val first = ReplayRunner().run(loadLowFuel()).finalState
        val second = ReplayRunner().run(loadLowFuel()).finalState

        // SimulationState data-class equality covers fuel as well as kinematics.
        assertEquals(first, second)
    }
}
