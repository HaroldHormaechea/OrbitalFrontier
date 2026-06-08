package com.orbitalfrontier.power

/**
 * Pure power/energy model (UC07 AC#4; docs/design/power-and-energy.md).
 *
 * Stateless function of (is-thrusting, [PowerParams]): [drawAt] is the single definition of the
 * ship's instantaneous energy demand — base hotel load plus thrust draw — and [status] exposes
 * reactor output, total draw and the resulting fuel burn rate for the HUD/tests. The economy's
 * [com.orbitalfrontier.economy.FuelBurn] consumes `drawAt · dt` each tick, so this is the one place
 * the burn rate is defined (no duplicated formula on the device and sim paths).
 *
 * No engine types — JVM-testable (UC07 AC#7). Reactor output is uncapped here (brownout/throttle
 * deferred); a cap would live in [status]/a future stepper, not by editing every call site.
 */
object PowerModel {
    /**
     * Instantaneous energy demand (units/s): [PowerParams.baseModuleDraw] always, plus
     * [PowerParams.thrustDraw] while [thrusting]. This is also the fuel burn rate (rate-based MVP).
     */
    fun drawAt(
        thrusting: Boolean,
        params: PowerParams,
    ): Float = params.baseModuleDraw + if (thrusting) params.thrustDraw else 0f

    /**
     * The full power snapshot for the current thrust state (UC07 AC#4) — reactor output, total draw,
     * and the fuel burn rate it produces.
     */
    fun status(
        thrusting: Boolean,
        params: PowerParams,
    ): PowerStatus {
        val draw = drawAt(thrusting, params)
        return PowerStatus(reactorOutput = params.reactorOutput, totalDraw = draw, burnRate = draw)
    }
}
