package com.orbitalfrontier.crew

/**
 * The outcome of a single [Hiring.resolve] call — the new credit balance, the new crew count, how
 * many crew were actually hired, and whether anything changed.
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes): "can't afford it / already at capacity / station doesn't hire"
 * is a normal, recoverable case reported via [hired] = 0 and [changed] = false (with [credits]/[crew]
 * unchanged), not an error. The caller (the play screen on device, the simulation on the JVM) folds
 * the values back into its state.
 */
data class HireResult(
    /** Credit balance after the hire (unchanged on a no-op). */
    val credits: Long,
    /** Crew count after the hire (unchanged on a no-op). */
    val crew: Int,
    /** Crew actually hired — 0 on a no-op. */
    val hired: Int,
    /** True only on a real hire; false on every no-op (so a caller can cheaply detect "nothing happened"). */
    val changed: Boolean,
)

/**
 * Pure, deterministic crew hiring (UC11 AC#2/#5) — the crew analogue of
 * [com.orbitalfrontier.economy.Trading] / [com.orbitalfrontier.ship.FleetResolver]. A side-effect-free
 * function of (credits, currentCrew, crewCapacity, offersCrew, order): identical inputs always yield
 * an identical result, with no I/O and no engine types, so it slots into the deterministic
 * simulation/replay path and is fully JVM-unit-testable (UC11 AC#5). It does **not** mutate anything —
 * the caller applies the [HireResult].
 *
 * Hiring only resolves at a station that **offers crew** ([offersCrew] = true — the docked station's
 * [com.orbitalfrontier.world.Station.hiresCrew]); the device path supplies the docked station's flag,
 * so hiring is implicitly gated on being docked at a crew-hiring station. A station that doesn't hire
 * crew is a no-op.
 *
 * For the MVP hiring is a **one-time credit cost** ([pricePerCrew] per crew) with no ongoing wages
 * (UC11 pitfall — wages are future work). Over-capacity is **clamp-to-remaining**: the excess request
 * is rejected and the rest is hired (mirroring [com.orbitalfrontier.economy.Trading]'s buy path),
 * never an error.
 *
 * All money math is [Long] (credits and the per-crew price are Long) so a large balance can never
 * overflow.
 */
object Hiring {
    /** One-time credit cost to hire one crew member (no ongoing wages in the MVP). [TUNE] */
    const val HIRE_COST_PER_CREW: Long = 100L

    /**
     * Resolve a single hire [order] against the player's [credits], the active ship's [currentCrew]
     * and its derived [crewCapacity], and whether the docked station [offersCrew].
     *
     * Returns the inputs **unchanged** ([hired] = 0, [changed] = false) on any non-hire: the order is
     * [HireOrder.None]; the station does not hire crew; the ship is already at capacity; the wallet
     * can't afford even one; or the requested quantity is non-positive.
     *
     * The hired amount is `min(requested, crewCapacity - currentCrew, credits / pricePerCrew)` —
     * bounded by the request, the remaining berths, and the wallet all at once. That many crew are
     * added and `hired * pricePerCrew` credits deducted.
     */
    fun resolve(
        credits: Long,
        currentCrew: Int,
        crewCapacity: Int,
        offersCrew: Boolean,
        order: HireOrder,
        pricePerCrew: Long = HIRE_COST_PER_CREW,
    ): HireResult {
        require(pricePerCrew > 0) { "pricePerCrew must be positive (div-by-zero guard): $pricePerCrew" }
        val unchanged = HireResult(credits, currentCrew, 0, false)
        if (!offersCrew) return unchanged
        val requested =
            when (order) {
                HireOrder.None -> return unchanged
                is HireOrder.Hire -> order.units
            }
        if (requested <= 0) return unchanged

        // Bounded by the requested amount, the remaining berths (clamp-to-remaining), and the wallet.
        val remainingCapacity = (crewCapacity - currentCrew).coerceAtLeast(0)
        val hired =
            minOf(
                requested.toLong(),
                remainingCapacity.toLong(),
                credits / pricePerCrew,
            ).toInt()
        if (hired <= 0) return unchanged

        val newCredits = credits - hired.toLong() * pricePerCrew
        return HireResult(newCredits, currentCrew + hired, hired, true)
    }
}
