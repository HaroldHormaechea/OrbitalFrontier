package com.orbitalfrontier.economy

import kotlin.math.floor

/**
 * The player's refuel intent for a frame (UC07 AC#5). [NONE] is the common idle case; [REFUEL]
 * requests converting hydrogen cargo into fuel. A `sealed`-style closed set as an `enum` (coding-
 * guidelines § O) — new fuel sources would add a constant, not edit a central `when`.
 */
enum class RefuelAction { NONE, REFUEL }

/**
 * The outcome of a [Refueling.resolve] call — the new fuel + cargo and how many hydrogen units were
 * converted. A small explicit result type (coding-guidelines § error-handling: prefer explicit
 * returns over exceptions for expected outcomes): "nothing to refuel / tank already full" is a
 * normal, recoverable case reported via [transferredUnits] = 0, not an error.
 */
data class RefuelResult(
    /** Fuel after topping up (unchanged when nothing was converted). */
    val fuel: Fuel,
    /** Cargo after drawing out the converted hydrogen (unchanged when nothing was converted). */
    val cargo: Cargo,
    /** Hydrogen units actually converted into fuel — 0 when none were. */
    val transferredUnits: Int,
)

/**
 * Pure refuelling logic (UC07 AC#5): convert **Hydrogen** cargo into fuel, bounded by both the
 * hydrogen on board and the tank space remaining, at [FuelParams.hydrogenToFuelRatio].
 *
 * No engine types, fully JVM-testable (UC07 AC#7). The device path ([com.orbitalfrontier.screen
 * .PlayScreen], reachable from the station hub) and any sim path call this one function, so refuel
 * behaviour is identical everywhere.
 */
object Refueling {
    /**
     * Resolve a refuel [action] against [fuel] and [cargo].
     *
     * For [RefuelAction.REFUEL]: convert as many **whole** hydrogen units as both fit in the tank and
     * are available, each yielding [FuelParams.hydrogenToFuelRatio] fuel; draw exactly those units out
     * of the hold. Whole units only, so a partially-fillable last unit is left in the hold rather than
     * split. [RefuelAction.NONE] — or no hydrogen / a full tank — returns the inputs unchanged with
     * `transferredUnits = 0`.
     */
    fun resolve(
        fuel: Fuel,
        cargo: Cargo,
        action: RefuelAction,
        params: FuelParams,
    ): RefuelResult {
        if (action != RefuelAction.REFUEL) return RefuelResult(fuel, cargo, 0)

        val hydrogenAvailable = cargo.contents[ResourceType.HYDROGEN] ?: 0
        val tankRemaining = fuel.remainingCapacity
        if (hydrogenAvailable <= 0 || tankRemaining <= 0f) return RefuelResult(fuel, cargo, 0)

        val ratio = params.hydrogenToFuelRatio
        // Whole hydrogen units whose converted fuel fits without overflowing the tank.
        val unitsByTank = floor(tankRemaining / ratio).toInt()
        val unitsToConvert = minOf(hydrogenAvailable, unitsByTank)
        if (unitsToConvert <= 0) return RefuelResult(fuel, cargo, 0)

        val refueled = fuel.refill(unitsToConvert * ratio)
        val drained = cargo.remove(ResourceType.HYDROGEN, unitsToConvert)
        return RefuelResult(refueled, drained, unitsToConvert)
    }
}
