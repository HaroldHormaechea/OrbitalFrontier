package com.orbitalfrontier.economy

/**
 * The player ship's fuel tank: a [level] of fuel (the Hydrogen resource) bounded by a [capacity]
 * (UC07 AC#1).
 *
 * Pure, immutable value (coding-guidelines § immutability): every change returns a new [Fuel], so it
 * composes safely into the immutable [com.orbitalfrontier.world.WorldState] snapshot the autosave
 * thread reads. No engine types, so fuel + power logic is fully JVM-testable (UC07 AC#7).
 *
 * **Low fuel slows the ship but never strands it.** [speedFactor] is exactly `1.0f` at or above the
 * params' low-fuel threshold (so movement is byte-identical to a no-fuel build, UC07 composition
 * guarantee) and ramps **down to — never below — `floorSpeedFraction`** as the tank empties
 * (UC07 AC#3). Burn ([FuelBurn]) and refuel ([Refueling]) drive [level]; capacity is a ship stat
 * reconstructed on load, not save data (mirrors [Cargo]).
 */
data class Fuel(
    val level: Float,
    val capacity: Float,
) {
    init {
        require(capacity > 0f) { "Fuel capacity must be positive: $capacity" }
        require(level in 0f..capacity) { "Fuel level must be in 0..capacity: level=$level capacity=$capacity" }
    }

    /** Current fill fraction in 0..1 (level / capacity). */
    val fraction: Float get() = level / capacity

    /** Free tank space remaining in fuel units (never negative). */
    val remainingCapacity: Float get() = capacity - level

    /** Whether the tank is below the low-fuel threshold (UC07 AC#3) — the speed-penalty regime. */
    fun isLow(params: FuelParams): Boolean = fraction < params.lowFuelThreshold

    /**
     * Effective max-speed multiplier for the current fuel level (UC07 AC#3).
     *
     * Returns **exactly `1.0f`** at or above [FuelParams.lowFuelThreshold] — so a caller can
     * short-circuit and keep movement byte-identical to a fuel-less build. Below the threshold it
     * ramps **linearly** from `1.0f` (at the threshold) down to [FuelParams.floorSpeedFraction] (at an
     * empty tank), never reaching 0: the ship always retains the floor fraction of its top speed.
     */
    fun speedFactor(params: FuelParams): Float {
        val fraction = this.fraction
        if (fraction >= params.lowFuelThreshold) return 1.0f
        val floor = params.floorSpeedFraction
        return floor + (1.0f - floor) * (fraction / params.lowFuelThreshold)
    }

    /**
     * Burn [units] of fuel, clamped at an empty tank (never negative — the ship coasts at the speed
     * floor rather than failing). [units] must be non-negative (a programmer error otherwise).
     */
    fun consume(units: Float): Fuel {
        require(units >= 0f) { "Cannot consume a negative amount of fuel: $units" }
        if (units == 0f) return this
        return copy(level = (level - units).coerceAtLeast(0f))
    }

    /**
     * Add [units] of fuel, clamped at [capacity] (overflow is discarded). [Refueling] bounds the
     * amount by hydrogen available *and* tank space before calling this, so nothing is wasted there.
     */
    fun refill(units: Float): Fuel {
        require(units >= 0f) { "Cannot refill by a negative amount: $units" }
        if (units == 0f) return this
        return copy(level = (level + units).coerceAtMost(capacity))
    }

    companion object {
        /** A brand-new ship's tank: full, at the default capacity ([FuelParams.DEFAULT_TANK_CAPACITY]). */
        fun full(): Fuel = Fuel(FuelParams.DEFAULT_TANK_CAPACITY, FuelParams.DEFAULT_TANK_CAPACITY)

        /** The default starting fuel state (a full default tank). */
        val DEFAULT: Fuel = full()
    }
}
