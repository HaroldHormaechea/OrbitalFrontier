package com.orbitalfrontier.economy

/*
 * The pure, engine-free decision core for the UC40 confirm-gate that fronts every economy spend
 * (Trade, Outfit, Shipyard, Hire, refuel).
 *
 * It answers exactly one question for a single user *intent* (one buy tap): given the credit cost of
 * what was tapped and the player's current balance, should the spend go through immediately, pause for
 * an explicit confirmation, or be refused for want of funds? That is the whole of its responsibility —
 * it owns the confirm-threshold + affordability axis and nothing else (SRP). Whether the action then
 * actually happens, and why not when it doesn't (a full hold, no free slot, an item not offered), stays
 * the authority of the pure economy RESOLVERS (Trading/Outfitting/FleetResolver/Hiring/StationRefuel). A
 * confirmed buy the resolver still no-ops is not this gate's concern — it falls through to the
 * styled-error branch at the call site.
 *
 * Pure value logic with no libGDX types, so it is JVM-unit-testable headlessly (ADR 0001) — the single
 * source of truth the five economy screens and PlayScreen all consult, so the threshold and the "can I
 * afford this?" rule live in one place rather than being re-derived per screen.
 */

/** What the gate decided for one tapped spend (UC40 AC#1/#3). */
enum class SpendDecision {
    /** Affordable and below the confirm threshold — fire the intent straight away (today's behaviour). */
    PROCEED,

    /** Affordable but at/above the confirm threshold — surface the confirmation dialog before committing. */
    CONFIRM,

    /** The cost exceeds the balance — refuse and surface a styled insufficient-credits error; do not fire. */
    INSUFFICIENT,
}

/**
 * The values a confirmation dialog renders for a tapped purchase (UC40 AC#1): the [item] name, its credit
 * [cost], and the [resultingBalance] the player is left with (`balance - cost`). A dumb value carrier —
 * the dialog is a pure renderer of it and holds no decision logic.
 */
data class ConfirmationDetails(
    val item: String,
    val cost: Long,
    val resultingBalance: Long,
)

/**
 * The pure confirm-gate (UC40). Decides [SpendDecision] for a tapped spend and builds the
 * [ConfirmationDetails] a confirmation dialog shows. No engine types; no resolver knowledge.
 */
object PurchaseGate {
    /**
     * The single source of truth for the spend that requires an explicit confirmation (UC40 AC#1). A spend
     * whose cost reaches this many credits prompts the player before committing; anything cheaper proceeds
     * silently. Authored as a deliberately round MVP default — high enough that routine trade/refuel taps
     * stay frictionless, low enough that a ship/hull/premium-upgrade purchase is gated. [TUNE]
     */
    const val CONFIRMATION_THRESHOLD_CREDITS: Long = 1_000L

    /**
     * Decide what should happen for a spend of [cost] credits against the current [balance] (UC40 AC#1/#3),
     * comparing the cost against [threshold] for the confirm boundary. Unaffordable wins first (a spend the
     * player can't cover is [SpendDecision.INSUFFICIENT] regardless of the threshold); an affordable spend is
     * [SpendDecision.CONFIRM] when it reaches the threshold and [SpendDecision.PROCEED] below it. Spending
     * the entire balance (`cost == balance`) is affordable.
     */
    fun evaluate(
        cost: Long,
        balance: Long,
        threshold: Long = CONFIRMATION_THRESHOLD_CREDITS,
    ): SpendDecision =
        when {
            cost > balance -> SpendDecision.INSUFFICIENT
            cost >= threshold -> SpendDecision.CONFIRM
            else -> SpendDecision.PROCEED
        }

    /** Build the [ConfirmationDetails] for a confirmation dialog: the post-spend balance is `balance - cost`. */
    fun details(
        item: String,
        cost: Long,
        balance: Long,
    ): ConfirmationDetails = ConfirmationDetails(item = item, cost = cost, resultingBalance = balance - cost)
}
