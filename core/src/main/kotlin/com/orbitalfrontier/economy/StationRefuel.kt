package com.orbitalfrontier.economy

import kotlin.math.floor

/**
 * The player's intent for a credits-based station refuel (UC18). [NONE] is the idle case; [BUY]
 * requests buying as much fuel as credits and tank space allow at the docked station's fuel price.
 *
 * Distinct from [RefuelAction] (the hydrogen-cargo→fuel conversion, UC07): UC18 adds a SECOND,
 * additive refuel path — paying credits for fuel at the docked station — without retiring the
 * conversion. A closed two-value set as an `enum` (coding-guidelines § O); a future fuel-purchase
 * mode would add a constant, not edit a central `when`.
 */
enum class StationRefuelAction { NONE, BUY }

/**
 * The outcome of a credits-based station refuel (UC18 AC#1–#4), reported as one of five mutually
 * exclusive [StationRefuelStatus] values so the caller can give clear, deterministic feedback rather
 * than a silent or partial failure (UC18 AC#4).
 *
 * - [StationRefuelStatus.REFUELED] — fuel was bought ([unitsBought] > 0); credits and fuel changed.
 * - [StationRefuelStatus.FULL] — the tank had no room (< 1 unit free); a no-op.
 * - [StationRefuelStatus.BROKE] — room existed but the player could not afford even one unit; a no-op.
 * - [StationRefuelStatus.UNAVAILABLE] — the docked station does not sell fuel (no price); a no-op.
 * - [StationRefuelStatus.NONE] — no purchase was requested ([StationRefuelAction.NONE]); a no-op.
 */
enum class StationRefuelStatus { REFUELED, FULL, BROKE, UNAVAILABLE, NONE }

/**
 * The result of a [StationRefuel.resolve] call — the new credit balance and fuel, how many whole
 * fuel units were bought, and the [status].
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes). On any non-[StationRefuelStatus.REFUELED] status the inputs are
 * returned unchanged with [unitsBought] = 0, so a no-op is cheap to detect and never mutates state.
 */
data class StationRefuelResult(
    /** Credit balance after the purchase (unchanged on a no-op). */
    val credits: Long,
    /** Fuel after the purchase (unchanged on a no-op). */
    val fuel: Fuel,
    /** Whole fuel units actually bought — 0 on any no-op. */
    val unitsBought: Long,
    /** Why the call resolved the way it did (UC18 AC#4). */
    val status: StationRefuelStatus,
)

/**
 * Pure, deterministic credits-for-fuel station refuelling (UC18) — the additive sibling of the
 * hydrogen-conversion [Refueling] (UC07), and the fuel analogue of [Trading.resolve]. A
 * side-effect-free function of (credits, fuel, price, action): identical inputs always yield an
 * identical result, with no I/O and no engine types, so it is fully JVM-unit-testable and slots into
 * the deterministic simulation/replay path. It does **not** mutate anything — the caller applies the
 * [StationRefuelResult].
 *
 * The price is the docked station's authored HYDROGEN buy price (credits per fuel unit),
 * reconstructed from the world map (ADR 0007), not a persisted row; a null price means the station
 * sells no fuel.
 */
object StationRefuel {
    /**
     * Resolve a credits-based refuel [action] for the player's [credits] and [fuel] at a station whose
     * fuel costs [fuelPricePerUnit] credits per unit (null when the station sells no fuel).
     *
     * Whole units only: `units = min(credits / price, floor(remainingCapacity))` and the player is
     * charged exactly `units * price`, gaining exactly `units` of fuel — so credits deducted always
     * match the fuel added at the station price (UC18 AC#3: no charge-without-fuel, no
     * fuel-without-charge). Because `units <= floor(remainingCapacity) <= remainingCapacity`, the fuel
     * added never overflows and nothing is wasted.
     *
     * Branch order matters (UC18 AC#4): a null price is [StationRefuelStatus.UNAVAILABLE]; an
     * effectively-full tank (< 1 unit free) is [StationRefuelStatus.FULL] — checked **before** the
     * broke test so a sub-unit sliver of room is never mis-reported as [StationRefuelStatus.BROKE];
     * room-but-can't-afford-one is [StationRefuelStatus.BROKE]; otherwise the purchase succeeds and is
     * [StationRefuelStatus.REFUELED].
     */
    fun resolve(
        credits: Long,
        fuel: Fuel,
        fuelPricePerUnit: Long?,
        action: StationRefuelAction,
    ): StationRefuelResult {
        if (action != StationRefuelAction.BUY) {
            return StationRefuelResult(credits, fuel, 0, StationRefuelStatus.NONE)
        }
        if (fuelPricePerUnit == null || fuelPricePerUnit <= 0) {
            return StationRefuelResult(credits, fuel, 0, StationRefuelStatus.UNAVAILABLE)
        }

        // Whole units of tank space free. Checked BEFORE the broke test so a < 1-unit sliver of room
        // reports FULL, not a false BROKE.
        val unitsByTank = floor(fuel.remainingCapacity).toLong()
        if (unitsByTank < 1L) {
            return StationRefuelResult(credits, fuel, 0, StationRefuelStatus.FULL)
        }

        // Bounded by the wallet (whole units we can pay for) and the tank.
        val units = minOf(credits / fuelPricePerUnit, unitsByTank)
        if (units <= 0L) {
            return StationRefuelResult(credits, fuel, 0, StationRefuelStatus.BROKE)
        }

        val cost = units * fuelPricePerUnit
        val newCredits = credits - cost
        val refueled = fuel.refill(units.toFloat())
        return StationRefuelResult(newCredits, refueled, units, StationRefuelStatus.REFUELED)
    }
}
