package com.orbitalfrontier.economy

/**
 * Per-ship fuel tuning parameters (UC07; docs/design/economy-and-resources.md "Fuel",
 * docs/design/power-and-energy.md).
 *
 * Fuel is the **Hydrogen** resource: a full tank is [DEFAULT_TANK_CAPACITY] units, drained by
 * [FuelBurn] at the power model's draw rate and topped up by [Refueling] from hydrogen cargo. These
 * are authored **[TUNE]** placeholders to balance later (the design note flags burn-rate/output
 * numbers as provisional); they live in the model's own units, not hard-coded at call sites, so a
 * later reactor/fuel-tank upgrade can source them from ship config.
 *
 * Pure value, no engine types, so the fuel system stays JVM-testable (UC07 AC#7).
 *
 * @property lowFuelThreshold fuel **fraction** (level / capacity) at or above which speed is
 *   unaffected; below it, effective max speed ramps down (UC07 AC#3).
 * @property floorSpeedFraction the lowest effective-speed multiplier, reached at an empty tank —
 *   never 0, so the player can always limp to refuel ("never stranded", UC07 AC#3).
 * @property hydrogenToFuelRatio fuel units produced per hydrogen-cargo unit when refuelling
 *   (UC07 AC#5). 1.0 = one hydrogen unit becomes one fuel unit.
 */
data class FuelParams(
    val lowFuelThreshold: Float = DEFAULT_LOW_FUEL_THRESHOLD,
    val floorSpeedFraction: Float = DEFAULT_FLOOR_SPEED_FRACTION,
    val hydrogenToFuelRatio: Float = DEFAULT_HYDROGEN_TO_FUEL_RATIO,
) {
    init {
        require(lowFuelThreshold in 0f..1f) { "lowFuelThreshold must be a 0..1 fraction: $lowFuelThreshold" }
        require(floorSpeedFraction in 0f..1f) { "floorSpeedFraction must be a 0..1 fraction: $floorSpeedFraction" }
        require(floorSpeedFraction > 0f) { "floorSpeedFraction must be > 0 (never stranded): $floorSpeedFraction" }
        require(hydrogenToFuelRatio > 0f) { "hydrogenToFuelRatio must be positive: $hydrogenToFuelRatio" }
    }

    companion object {
        /**
         * A starter ship's tank capacity in fuel units. An authored tunable; later UCs derive
         * capacity from fuel-tank upgrades. Mirrored by the `ship.fuel` column's SQL default. [TUNE]
         */
        const val DEFAULT_TANK_CAPACITY: Float = 100f

        /** Default low-fuel fraction (20%) below which effective max speed starts ramping down. [TUNE] */
        const val DEFAULT_LOW_FUEL_THRESHOLD: Float = 0.20f

        /** Default empty-tank speed floor (25% of max) — the player can always limp to refuel. [TUNE] */
        const val DEFAULT_FLOOR_SPEED_FRACTION: Float = 0.25f

        /** Default hydrogen→fuel conversion (1:1). [TUNE] */
        const val DEFAULT_HYDROGEN_TO_FUEL_RATIO: Float = 1.0f
    }
}
