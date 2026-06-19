package com.orbitalfrontier.economy

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation

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
}
