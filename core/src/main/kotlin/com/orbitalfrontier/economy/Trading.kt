package com.orbitalfrontier.economy

/**
 * Which side of a trade was executed (UC08 AC#3) — the player **bought** resources from the station
 * or **sold** resources to it. A closed two-value set as an `enum` (coding-guidelines § O); reported
 * on a [TradeResult] so the caller can log/react to the direction without re-inspecting the order.
 */
enum class TradeKind { BUY, SELL }

/**
 * The player's trade intent for one trade action (UC08 AC#3) — the trading analogue of
 * [com.orbitalfrontier.world.MineAction] / [RefuelAction], modelled as a `sealed` hierarchy
 * (coding-guidelines § O) so adding a future order kind plugs in a new subtype rather than editing a
 * central `when`.
 *
 * [None] is the idle case (no trade requested). [Buy]/[Sell] carry the [resource] and the requested
 * [units]; [Trading.resolve] clamps the actual amount to what is affordable / fits / is held, so a
 * caller can safely request more than is possible and let the resolver settle the real quantity.
 */
sealed interface TradeOrder {
    /** No trade this action — [Trading.resolve] returns its inputs unchanged. */
    data object None : TradeOrder

    /** Buy up to [units] of [resource] from the station (clamped to credits + cargo space). */
    data class Buy(val resource: ResourceType, val units: Int) : TradeOrder

    /** Sell up to [units] of [resource] to the station (clamped to units held). */
    data class Sell(val resource: ResourceType, val units: Int) : TradeOrder
}

/**
 * The outcome of a single [Trading.resolve] call — the new credit balance and cargo, how many units
 * actually changed hands, and which side traded.
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes): "can't afford it / hold is full / nothing to sell" is a normal,
 * recoverable case reported via [tradedUnits] = 0 (with [credits]/[cargo] unchanged and [kind] null),
 * not an error. The caller (the play screen on device, tests on the JVM) folds the values back into
 * its state. [kind] is non-null only on a real trade, so a no-op is cheap to detect.
 */
data class TradeResult(
    /** Credit balance after the trade (unchanged on a no-op). */
    val credits: Long,
    /** Cargo after the trade (unchanged on a no-op). */
    val cargo: Cargo,
    /** Units actually bought or sold — 0 on a no-op. */
    val tradedUnits: Int,
    /** The side that traded, or null when nothing happened. */
    val kind: TradeKind?,
)

/**
 * Pure, deterministic inter-station trading (UC08 AC#3/#6) — the economy analogue of [Refueling] and
 * [com.orbitalfrontier.world.Mining]. A side-effect-free function of (credits, cargo, market, order):
 * identical inputs always yield an identical result, with no I/O and no engine types, so it slots
 * into the deterministic simulation/replay path and is fully JVM-unit-testable (UC08 AC#6). It does
 * **not** mutate anything — the caller applies the [TradeResult].
 *
 * Trading only resolves against a real [StationMarket]; the device path supplies the **docked**
 * station's market (UC08 AC#3), so trading is implicitly gated on being docked. A null market (not
 * docked, or a station with no trade desk) is a no-op.
 *
 * All money math is [Long] (credits and prices are Long) so a large balance can never overflow.
 */
object Trading {
    /**
     * Resolve a single trade [order] against the player's [credits], [cargo], and the docked station's
     * [market].
     *
     * Returns the inputs **unchanged** (`tradedUnits = 0`, `kind = null`) on any non-trade: the order
     * is [TradeOrder.None]; the market is null; the resource is not offered here; or the requested side
     * can't move any units (can't afford even one / hold full when buying; nothing held when selling;
     * a non-positive requested quantity).
     *
     * **Buy:** the affordable amount is `min(units, credits / buyPrice, cargo free space)` — bounded by
     * the budget, the wallet, and the hold all at once. That many units are added to the cargo and
     * `affordable * buyPrice` credits are deducted.
     *
     * **Sell:** the sellable amount is `min(units, units held)`. That many units are removed from the
     * cargo and `sellable * sellPrice` credits are added.
     */
    fun resolve(
        credits: Long,
        cargo: Cargo,
        market: StationMarket?,
        order: TradeOrder,
    ): TradeResult {
        val unchanged = TradeResult(credits, cargo, 0, null)
        if (market == null) return unchanged
        return when (order) {
            TradeOrder.None -> unchanged
            is TradeOrder.Buy -> resolveBuy(credits, cargo, market, order, unchanged)
            is TradeOrder.Sell -> resolveSell(credits, cargo, market, order, unchanged)
        }
    }

    private fun resolveBuy(
        credits: Long,
        cargo: Cargo,
        market: StationMarket,
        order: TradeOrder.Buy,
        unchanged: TradeResult,
    ): TradeResult {
        if (order.units <= 0) return unchanged
        val offer = market.offerFor(order.resource) ?: return unchanged
        val buyPrice = offer.buyPrice
        if (buyPrice <= 0) return unchanged // defensive: the TradeOffer invariant already guarantees > 0.

        // Bounded by the requested amount, the wallet (whole units we can pay for), and the hold.
        val affordable =
            minOf(
                order.units.toLong(),
                credits / buyPrice,
                cargo.remainingCapacity.toLong(),
            ).toInt()
        if (affordable <= 0) return unchanged

        val transfer = cargo.add(order.resource, affordable)
        val accepted = transfer.acceptedUnits
        if (accepted <= 0) return unchanged // hold full — no-op (defensive; remainingCapacity already bounds it).

        val newCredits = credits - accepted.toLong() * buyPrice
        return TradeResult(newCredits, transfer.cargo, accepted, TradeKind.BUY)
    }

    private fun resolveSell(
        credits: Long,
        cargo: Cargo,
        market: StationMarket,
        order: TradeOrder.Sell,
        unchanged: TradeResult,
    ): TradeResult {
        if (order.units <= 0) return unchanged
        val offer = market.offerFor(order.resource) ?: return unchanged

        val held = cargo.contents[order.resource] ?: 0
        val sellable = minOf(order.units, held)
        if (sellable <= 0) return unchanged // nothing to sell — no-op.

        val newCargo = cargo.remove(order.resource, sellable)
        val newCredits = credits + sellable.toLong() * offer.sellPrice
        return TradeResult(newCredits, newCargo, sellable, TradeKind.SELL)
    }
}
