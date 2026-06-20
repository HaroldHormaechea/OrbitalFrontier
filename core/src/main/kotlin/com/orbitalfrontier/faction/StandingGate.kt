package com.orbitalfrontier.faction

/**
 * The pure **acquisition** standing gate (UC48 AC#1/#4) — decides whether a shop/shipyard item is
 * currently buyable given the player's standing with the **docked station's faction**, and carries the
 * "why locked" payload the screens render. The acquisition analogue of [ReputationGate] (which gates
 * *mission offers*); both are side-effect-free functions of their inputs with no engine types, so they
 * are fully JVM-testable (UC48 AC#5) and identical inputs always yield the same answer.
 *
 * **Identity when nothing is gated (the byte-identity anchor).** A `requiredStanding <= 0` item is
 * always available — the common case and every pre-UC48 part/hull (`unlockThreshold` defaults to 0) —
 * so this is the identity over an un-gated catalog and back-compatible by construction. A gated item is
 * available only when the player's standing with the station's [factionId] is **at or above** its
 * threshold (mirroring [ReputationGate]'s `>=`). The required faction is the **docked station's**
 * (mirroring [com.orbitalfrontier.economy.FactionPricing]); a positive threshold at a faction-less
 * (`null`) station reads as standing 0 and is therefore permanently locked — surfaced as an authoring
 * error via the lock reason, never special-cased.
 *
 * Purely derived from the already-persisted [Reputation] (no new persisted state, no RNG): gating
 * updates the instant standing changes (AC#3) and adds nothing to the save.
 */
object StandingGate {
    /**
     * The standing status of an item that needs [requiredStanding] with the docked station's
     * [factionId], given the player's [reputation]. `requiredStanding <= 0` ⇒ always available; else the
     * player's current standing with [factionId] (0 when null/unknown) must be `>= requiredStanding`.
     */
    fun status(
        requiredStanding: Int,
        factionId: FactionId?,
        reputation: Reputation,
    ): StandingStatus {
        if (requiredStanding <= 0) {
            return StandingStatus(
                available = true,
                requiredStanding = requiredStanding,
                currentStanding = factionId?.let { reputation.valueFor(it) } ?: 0,
                factionId = factionId,
            )
        }
        val current = factionId?.let { reputation.valueFor(it) } ?: 0
        return StandingStatus(
            available = current >= requiredStanding,
            requiredStanding = requiredStanding,
            currentStanding = current,
            factionId = factionId,
        )
    }
}

/**
 * The derived standing decision for one acquisition item (UC48 AC#4) — whether it is [available] to
 * buy plus the "why" payload a screen renders for a locked row ([requiredStanding] vs.
 * [currentStanding] with [factionId]). A small pure value (coding-guidelines § error-handling: explicit
 * result over a bare Boolean), produced by [StandingGate.status]; the screens turn [locked] +
 * [requiredStanding]/[currentStanding] into the "Requires <faction> standing N (you: M)" line.
 */
data class StandingStatus(
    val available: Boolean,
    val requiredStanding: Int,
    val currentStanding: Int,
    val factionId: FactionId?,
) {
    /** Convenience inverse of [available] — true when the item is gated out at the current standing. */
    val locked: Boolean get() = !available
}
