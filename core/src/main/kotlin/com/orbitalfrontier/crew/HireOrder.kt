package com.orbitalfrontier.crew

/**
 * The player's crew-hire intent for one hire action (UC11 AC#2) — the crew analogue of
 * [com.orbitalfrontier.economy.TradeOrder] / [com.orbitalfrontier.ship.FleetOrder], modelled as a
 * `sealed` hierarchy (coding-guidelines § O) so a future hire kind (e.g. fire/transfer crew) plugs
 * in a new subtype rather than editing a central `when`.
 *
 * [None] is the idle case (no hire requested). [Hire] carries the requested [units]; [Hiring.resolve]
 * clamps the actual amount to what fits under crew capacity and what the wallet can afford, so a
 * caller can safely request more than is possible and let the resolver settle the real quantity
 * (clamp-to-remaining, like [com.orbitalfrontier.economy.Trading]'s buy path).
 */
sealed interface HireOrder {
    /** No hire this action — [Hiring.resolve] returns its inputs unchanged. */
    data object None : HireOrder

    /** Hire up to [units] crew at the docked station (clamped to remaining capacity + credits). */
    data class Hire(val units: Int) : HireOrder
}
