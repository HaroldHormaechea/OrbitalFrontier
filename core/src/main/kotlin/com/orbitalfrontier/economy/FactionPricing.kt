package com.orbitalfrontier.economy

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import kotlin.math.roundToLong

/**
 * The faction/reputation price seam (UC46 AC#2) — realizes the hook ADR 0007 / ADR 0013 reserved and
 * deferred (a `FactionPricing` type that never existed until now). A pure function that grades a
 * station's prices by the player's standing with its owning faction.
 *
 * **Exactly 1.0 at neutral (the byte-identity anchor, UC46 risk #1).** A null faction (an unaligned
 * station) or a neutral (0) standing returns a multiplier of *exactly* 1.0, so a fresh game — and
 * uc08's Titanium sell at the league-owned Alpha Station, where the player starts neutral — prices at
 * the authored base and stays byte-identical. Above neutral the player earns an allied discount (prices
 * trend below 1.0); below neutral a hostile markup (prices trend above 1.0). The multiplier is graded
 * linearly by `standing / factionStandingScale` and clamped into the shared `[minMul, maxMul]` band so
 * it composes with the supply/demand and drift terms in [MarketPricing].
 *
 * Pure, no engine types, so it is fully JVM-unit-testable ([FactionPricingTest]) and deterministic.
 */
object FactionPricing {
    /**
     * The price multiplier for a station owned by [factionId] given the player's [reputation]. Returns
     * **exactly 1.0** for a null faction or a neutral standing; otherwise `1 - factionInfluence *
     * (standing / factionStandingScale)`, clamped to `[minMul, maxMul]`.
     */
    fun adjust(
        factionId: FactionId?,
        reputation: Reputation,
        params: PricingParams,
    ): Double {
        if (factionId == null) return 1.0
        val standing = reputation.valueFor(factionId)
        if (standing == 0) return 1.0
        val raw = 1.0 - params.factionInfluence * (standing / params.factionStandingScale)
        return raw.coerceIn(params.minMul, params.maxMul)
    }

    /**
     * The faction-graded effective price of an item whose authored/catalog price is [basePrice], for a
     * station owned by [factionId] given the player's [reputation] (UC48 AC#2) — `round(basePrice ×
     * [adjust])`, clamped to `>= 1` so a discounted item is never free. The **single source of truth**
     * for an acquisition's effective price: the shop/shipyard screens display it and the pure resolvers
     * ([com.orbitalfrontier.outfit.Outfitting], [com.orbitalfrontier.ship.FleetResolver]) charge it, so
     * the displayed price and the deducted price are always identical (the display==charge invariant).
     *
     * **Exactly the base at neutral (byte-identity).** [adjust] returns exactly 1.0 for a null faction
     * or neutral standing, so `round(basePrice × 1.0) = basePrice` and a fresh-game purchase prices at
     * the authored base — no fixture regeneration. Above neutral the allied discount trends the price
     * below base; below neutral the hostile markup trends it above. All money math is [Long] so a large
     * price never overflows (matching [com.orbitalfrontier.outfit.Upgrade.price] /
     * [com.orbitalfrontier.ship.ShipType.price]).
     */
    fun adjustedPrice(
        basePrice: Long,
        factionId: FactionId?,
        reputation: Reputation,
        params: PricingParams,
    ): Long = (basePrice * adjust(factionId, reputation, params)).roundToLong().coerceAtLeast(1)
}
