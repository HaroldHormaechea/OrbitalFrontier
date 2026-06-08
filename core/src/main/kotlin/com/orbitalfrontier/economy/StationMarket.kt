package com.orbitalfrontier.economy

/**
 * A single station's fixed buy/sell prices for one [ResourceType] (UC08 AC#2/#4).
 *
 * [buyPrice] is what the player pays (credits per unit) to **buy** that resource **from** the
 * station; [sellPrice] is what the player receives (credits per unit) to **sell** it **to** the
 * station. Prices are MVP-fixed and data-driven — authored per station (see
 * [com.orbitalfrontier.world.MvpSectorMap]) rather than simulated; dynamic pricing is deferred
 * (UC14, see docs/design/economy-and-resources.md).
 *
 * **Invariant: `buyPrice > 0` and `0 <= sellPrice <= buyPrice`.** A positive buy price is what makes
 * the affordability division ([Trading.resolve]) safe (never divide by zero); `sellPrice <= buyPrice`
 * forbids a single station that pays more to buy back than it charges to sell — that would be a
 * money-printing loop. Cross-station arbitrage (buy low here, sell high there) is the intended income
 * path and is unaffected (a *different* station may pay a higher sell price than this one's buy
 * price). Prices are [Long] so a large balance never overflows (coding-guidelines § error-handling:
 * fail fast on an authoring/invariant violation).
 */
data class TradeOffer(
    val buyPrice: Long,
    val sellPrice: Long,
) {
    init {
        require(buyPrice > 0) { "TradeOffer buyPrice must be positive: $buyPrice" }
        require(sellPrice in 0..buyPrice) {
            "TradeOffer sellPrice must be in 0..buyPrice ($buyPrice): $sellPrice"
        }
    }
}

/**
 * A docked station's trade desk: an immutable map of [ResourceType] → [TradeOffer] (UC08 AC#2).
 *
 * Pure, immutable value with no engine types, so trading stays fully JVM-testable (UC08 AC#6) and
 * composes into the authored [com.orbitalfrontier.world.Station] data. A resource **absent** from
 * [offers] is simply not traded at this station — [offerFor] returns null and [Trading.resolve]
 * no-ops for it (the "not-offered" case), so a station only buys/sells the goods it lists.
 *
 * **Prices are authored map data, not persisted rows.** The MVP markets are reconstructed from
 * [com.orbitalfrontier.world.MvpSectorMap] on load (they ride with the injected world), so the save
 * never pins a stale price table — see ADR 0007. When dynamic pricing arrives, per-station price
 * state will move into the save behind this same type without touching consumers.
 */
data class StationMarket(
    val offers: Map<ResourceType, TradeOffer>,
) {
    /** The offer for [resource] at this station, or null if the station does not trade it. */
    fun offerFor(resource: ResourceType): TradeOffer? = offers[resource]

    companion object {
        /** A station with no trade desk (trades nothing); the default for an authored [Station]. */
        val EMPTY: StationMarket = StationMarket(emptyMap())
    }
}
