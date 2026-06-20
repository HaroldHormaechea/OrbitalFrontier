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
 * no engine types — JVM-testable (UC07 AC#7).
 *
 * [weaponsDraw] / [scannerDraw] (UC49) are **budget-only** sheddable draws: they feed the [Brownout]
 * power-budget cap ONLY and are deliberately NOT part of [PowerModel.drawAt] / fuel burn, so adding
 * them leaves the UC16 25/75 base:thrust fuel tuning (and every recorded fixture) untouched. Both
 * default 0, so by default demand ≈ base+thrust and no brownout occurs.
 */
data class PowerParams(
    val reactorOutput: Float = DEFAULT_REACTOR_OUTPUT,
    val baseModuleDraw: Float = DEFAULT_BASE_MODULE_DRAW,
    val thrustDraw: Float = DEFAULT_THRUST_DRAW,
    val weaponsDraw: Float = DEFAULT_WEAPONS_DRAW,
    val scannerDraw: Float = DEFAULT_SCANNER_DRAW,
) {
    init {
        require(reactorOutput > 0f) { "reactorOutput must be positive: $reactorOutput" }
        require(baseModuleDraw >= 0f) { "baseModuleDraw must be non-negative: $baseModuleDraw" }
        require(thrustDraw >= 0f) { "thrustDraw must be non-negative: $thrustDraw" }
        require(weaponsDraw >= 0f) { "weaponsDraw must be non-negative: $weaponsDraw" }
        require(scannerDraw >= 0f) { "scannerDraw must be non-negative: $scannerDraw" }
    }

    companion object {
        /** Default reactor energy output (units/s). Display-only (uncapped); not part of fuel-burn tuning. [TUNE] */
        const val DEFAULT_REACTOR_OUTPUT: Float = 2.0f

        /**
         * Fuel-duration calibration (UC16). The **reference ship is the Wayfarer starter**
         * ([com.orbitalfrontier.ship.ShipRoster.STARTER]), whose tank is
         * [com.orbitalfrontier.economy.FuelParams.DEFAULT_TANK_CAPACITY] (100 units). Total draw is
         * tuned so a full Wayfarer tank drains in ~30 minutes of continuous thrust (UC16 AC#1),
         * preserving the existing 25/75 base:thrust split. Every other ship shares this same
         * per-second draw applied against its own tank size, so relative fuel economy stays
         * proportional (UC16 AC#2).
         *
         * The reference tank size is duplicated as a literal here (rather than imported from
         * [com.orbitalfrontier.economy.FuelParams.DEFAULT_TANK_CAPACITY]) on purpose: it avoids an
         * economy→power package dependency cycle, and this comment keeps the link discoverable.
         */
        private const val REFERENCE_TANK_UNITS: Float = 100f // = Wayfarer starter tank (FuelParams.DEFAULT_TANK_CAPACITY)
        private const val REFERENCE_THRUST_SECONDS: Float = 1800f // ~30 min target (UC16 AC#1)

        /** Total continuous-thrust draw (units/s) that drains the reference tank in [REFERENCE_THRUST_SECONDS]. */
        private const val REFERENCE_TOTAL_DRAW: Float = REFERENCE_TANK_UNITS / REFERENCE_THRUST_SECONDS

        /** Default always-on module draw (units/s) — fuel sipped even while coasting. 25% of total (UC16). ≈0.013889 */
        const val DEFAULT_BASE_MODULE_DRAW: Float = REFERENCE_TOTAL_DRAW * 0.25f

        /** Default extra draw while thrusting (units/s) — hard maneuvering costs more. 75% of total (UC16). ≈0.041667 */
        const val DEFAULT_THRUST_DRAW: Float = REFERENCE_TOTAL_DRAW * 0.75f

        /**
         * Default budget-only weapons draw (units/s) for the [Brownout] cap (UC49). **0** by default:
         * the MVP starter ship carries no powered weapons load, so default play never browns out and
         * every recorded fixture stays byte-identical. A fixture/ship that fits powered weapons pins a
         * positive value. NOT part of fuel burn ([PowerModel.drawAt]). [TUNE]
         */
        const val DEFAULT_WEAPONS_DRAW: Float = 0f

        /** Default budget-only scanner draw (units/s) for the [Brownout] cap (UC49). 0 by default — see [DEFAULT_WEAPONS_DRAW]. [TUNE] */
        const val DEFAULT_SCANNER_DRAW: Float = 0f
    }
}
