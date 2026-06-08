package com.orbitalfrontier.power

/**
 * An immutable snapshot of the ship's instantaneous power/energy state (UC07 AC#4) — the accessor a
 * HUD or test reads to see reactor supply versus demand and the resulting fuel burn.
 *
 * For the rate-based MVP [burnRate] equals [totalDraw] (energy demand drives fuel burn 1:1; see
 * [PowerModel]); they are kept as distinct fields so a later reactor-efficiency or capacitor model
 * can diverge them without changing the accessor's shape. [reactorOutput] is reported even though it
 * does not yet cap [totalDraw] (brownout deferred).
 */
data class PowerStatus(
    /** Energy the reactor can supply (units/s). */
    val reactorOutput: Float,
    /** Total module + thrust energy demand (units/s). */
    val totalDraw: Float,
    /** Fuel consumed per second at this draw (units/s); equals [totalDraw] in the rate-based MVP. */
    val burnRate: Float,
)
