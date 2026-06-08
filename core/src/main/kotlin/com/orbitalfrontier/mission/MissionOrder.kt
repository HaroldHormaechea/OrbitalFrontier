package com.orbitalfrontier.mission

/**
 * The player's mission intent for one resolve action (UC12 AC#3) — the mission analogue of
 * [com.orbitalfrontier.economy.TradeOrder] / [com.orbitalfrontier.crew.HireOrder], modelled as a
 * `sealed` hierarchy (coding-guidelines § O) so a future order kind (e.g. abandon) plugs in a new
 * subtype rather than editing a central `when`.
 *
 * [None] is the idle case (no order this action). [Accept] takes on a currently-surfaced offer (a
 * board offer while docked, a radio offer while in flight). [TurnIn] completes an active mission at
 * the appropriate station. [Missions.resolve] no-ops any order it cannot satisfy (offer gone,
 * quota not held, wrong station, not picked up), so a caller can issue an order optimistically and
 * let the resolver settle whether it applies.
 *
 * Note there is **no pick-up order**: a courier parcel is picked up **automatically** on docking at
 * its pickup station (handled inside [Missions.resolve]); pickup needs no explicit player action.
 */
sealed interface MissionOrder {
    /** No mission order this action — [Missions.resolve] returns its inputs unchanged. */
    data object None : MissionOrder

    /** Accept the surfaced offer with [id] (moves it from available → ACTIVE in the log). */
    data class Accept(val id: MissionId) : MissionOrder

    /** Turn in the ACTIVE mission with [id] (completes it + grants the reward when conditions are met). */
    data class TurnIn(val id: MissionId) : MissionOrder
}
