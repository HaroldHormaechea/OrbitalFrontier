package com.orbitalfrontier.economy

import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.world.PoiId
import kotlin.math.roundToLong

/**
 * The pure dynamic-pricing engine (UC46 AC#1/#2/#4) — turns an authored [StationMarket] base into the
 * **effective** market for one tick by blending three bounded multipliers per offer:
 *
 *  1. **Supply/demand** (player-driven, always-on primary mechanism): `1 - elasticity * (pressure /
 *     pressureScale)` from the station's net signed [StationMarketState] pressure — identity at 0.
 *  2. **Drift** (smaller, seeded, kill-switchable): `1 + driftAmplitude * (u(key, epoch) - u(key, 0))`
 *     where `u` is a deterministic `[-1, 1]` value from [DeterministicRng] keyed on
 *     `"price:<stationId>:<resource>"` + the epoch (`tick / driftPeriodTicks`). **Exactly 1.0 at epoch
 *     0** (the anchor) because the term subtracts the epoch-0 value from itself.
 *  3. **Faction/reputation** ([FactionPricing.adjust]): identity at neutral standing.
 *
 * The three are multiplied, the **combined** multiplier is clamped to `[minMul, maxMul]` (the swing
 * caps that, with [StationMarketState.decayed] recovery, prevent a degenerate infinite-profit loop),
 * then each `Long` price is rounded and re-clamped to the [TradeOffer] invariant (`buyPrice > 0`,
 * `0 <= sellPrice <= buyPrice`).
 *
 * **Determinism (UC46 AC#4).** Drift is keyed purely on `(stationId, resource, epoch)` — there is **no
 * seed mix** (no production world-seed exists; mixing one would break device/replay parity), so the
 * supply/demand term is deterministic from the action log and the whole stepper replays bit-for-bit.
 *
 * **Byte-identity anchor (UC46 risk #1).** At pressure 0 AND tick 0 / epoch 0 AND neutral reputation
 * every multiplier is exactly 1.0, so `round(base * 1.0) == base` and the effective price equals the
 * authored base — uc08's tick-0 Titanium sell still yields 800 credits, every pre-UC46 fixture unchanged.
 *
 * Pure, no engine types, so it is fully JVM-unit-testable ([MarketPricingTest]).
 */
object MarketPricing {
    /**
     * The effective [StationMarket] for [stationId] this [tick]: each authored offer in [base] repriced
     * by the supply/demand ([state]), drift ([tick]) and faction ([factionId] + [reputation]) blend
     * under [params]. A resource not offered by [base] is simply not priced (the offer set never grows).
     */
    fun effectiveMarket(
        base: StationMarket,
        stationId: PoiId,
        state: StationMarketState,
        tick: Int,
        params: PricingParams,
        factionId: FactionId?,
        reputation: Reputation,
    ): StationMarket {
        if (base.offers.isEmpty()) return base
        val pressures = state.pressureFor(stationId)
        val factionMul = FactionPricing.adjust(factionId, reputation, params)
        val epoch = tick / params.driftPeriodTicks
        val repriced =
            base.offers.mapValues { (resource, offer) ->
                val pressure = pressures[resource] ?: 0
                val supplyDemandMul = 1.0 - params.elasticity * (pressure.toDouble() / params.pressureScale)
                val key = "price:${stationId.value}:${resource.name}"
                val driftMul = 1.0 + params.driftAmplitude * (seededUnit(key, epoch) - seededUnit(key, 0))
                val combined = (supplyDemandMul * driftMul * factionMul).coerceIn(params.minMul, params.maxMul)
                val buy = (offer.buyPrice * combined).roundToLong().coerceAtLeast(1)
                val sell = (offer.sellPrice * combined).roundToLong().coerceIn(0, buy)
                TradeOffer(buyPrice = buy, sellPrice = sell)
            }
        return StationMarket(repriced)
    }

    /**
     * A deterministic `[-1, 1)` value for [key] at drift [epoch], via the shared [DeterministicRng]
     * primitive (FNV-1a hash → one LCG step → top-24-bit float). At any epoch the value is fixed, so
     * `seededUnit(key, 0) - seededUnit(key, 0) == 0` exactly — the property that makes drift exactly 1.0
     * at epoch 0. String-keyed with no global seed, mirroring [com.orbitalfrontier.combat.CombatRng].
     */
    private fun seededUnit(
        key: String,
        epoch: Int,
    ): Double {
        val advanced = DeterministicRng.lcgAdvance(DeterministicRng.fnv1a("$key:$epoch"))
        return DeterministicRng.floatFromState(advanced).toDouble() * 2.0 - 1.0
    }
}
