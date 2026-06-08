package com.orbitalfrontier.power

/**
 * Ship power/energy tuning parameters (UC07 AC#4; docs/design/power-and-energy.md).
 *
 * The MVP models power as a **draw rate** that feeds fuel burn (no separate energy pool / brownout
 * yet — both deferred per the design note's "pool vs. rate" open question). [reactorOutput] is the
 * energy the reactor can supply; [baseModuleDraw] is the always-on hotel load (a loaded ship sips
 * fuel even idle); [thrustDraw] is the extra draw while the engines/RCS are firing (hard maneuvering
 * spikes burn). Total draw = base + (thrusting ? thrust : 0), and that draw is the fuel burn rate.
 *
 * Authored **[TUNE]** placeholders (the design note flags these numbers as provisional). Pure value,
 * no engine types — JVM-testable (UC07 AC#7). Reactor output is currently **uncapped**: total draw
 * may exceed it without a brownout/throttle (deferred); [PowerModel.status] still reports both so the
 * relationship is observable and a later cap is a localized change.
 */
data class PowerParams(
    val reactorOutput: Float = DEFAULT_REACTOR_OUTPUT,
    val baseModuleDraw: Float = DEFAULT_BASE_MODULE_DRAW,
    val thrustDraw: Float = DEFAULT_THRUST_DRAW,
) {
    init {
        require(reactorOutput > 0f) { "reactorOutput must be positive: $reactorOutput" }
        require(baseModuleDraw >= 0f) { "baseModuleDraw must be non-negative: $baseModuleDraw" }
        require(thrustDraw >= 0f) { "thrustDraw must be non-negative: $thrustDraw" }
    }

    companion object {
        /** Default reactor energy output (units/s). [TUNE] */
        const val DEFAULT_REACTOR_OUTPUT: Float = 2.0f

        /** Default always-on module draw (units/s) — fuel sipped even while coasting. [TUNE] */
        const val DEFAULT_BASE_MODULE_DRAW: Float = 0.5f

        /** Default extra draw while thrusting (units/s) — hard maneuvering costs more. [TUNE] */
        const val DEFAULT_THRUST_DRAW: Float = 1.5f
    }
}
