package com.orbitalfrontier.economy

import com.orbitalfrontier.power.PowerModel
import com.orbitalfrontier.power.PowerParams

/**
 * The single fuel-burn step (UC07 AC#2) — **the** shared function both the live device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the deterministic sim/replay path call. Neither
 * re-implements the formula, so live and replayed fuel match exactly (the determinism contract the
 * playthrough harness relies on, ADR 0006).
 *
 * Burn over [dt] is the power model's draw at the current thrust state ([PowerModel.drawAt]) times
 * [dt], drained from the tank and clamped at empty by [Fuel.consume] (never negative — the ship
 * coasts at the speed floor instead of failing). Pure, integer-free but deterministic (fixed `Float`
 * math, caller supplies a fixed `dt` on the sim path), no engine types — JVM-testable (UC07 AC#7).
 */
object FuelBurn {
    /**
     * Advance [fuel] by one [dt] step, burning [PowerModel.drawAt]`(thrusting, powerParams) · dt`.
     *
     * @param thrusting whether the engines/RCS are firing this step (higher draw).
     * @param dt the timestep in seconds (must be non-negative).
     */
    fun step(
        fuel: Fuel,
        thrusting: Boolean,
        powerParams: PowerParams,
        dt: Float,
    ): Fuel {
        require(dt >= 0f) { "dt must be non-negative: $dt" }
        val burned = PowerModel.drawAt(thrusting, powerParams) * dt
        return fuel.consume(burned)
    }
}
