package com.orbitalfrontier.economy

/**
 * Authored balancing tunables for the dynamic-pricing system (UC46, ADR 0034) — the pricing analogue
 * of [com.orbitalfrontier.faction.ReputationParams] / [MiningParams] / [FuelParams].
 *
 * Pure data injected into the (pure) pricing functions ([MarketPricing], [StationMarketState.decayed],
 * [FactionPricing]). Injecting the params keeps pricing a pure function of its inputs — the same params
 * + the same player actions + the same tick always yield the same prices — so the whole system is
 * deterministic and JVM-testable, and a recorded playthrough can pin the exact tuning it ran under
 * (`Playthrough.pricingConfig`) so a later default retune can never silently invalidate an old replay.
 *
 * **Identity at the anchor (the byte-identity contract, UC46 risk #1).** Every default is chosen so
 * that at **pressure 0, tick 0 / epoch 0, and neutral reputation** every multiplier is *exactly* 1.0,
 * so the effective price equals the authored base and the pre-UC46 fixtures (notably uc08's tick-0
 * Titanium sell → 800 credits) stay byte-identical with zero regeneration. [minMul] <= 1.0 <= [maxMul]
 * keeps that identity representable after the combined-multiplier clamp.
 *
 * Every `[TUNE]` constant is a balancing placeholder; none affects determinism, which is fixed by the
 * (pure) pricing algorithms.
 *
 * @property elasticity how strongly net player-driven pressure moves a price (per unit of
 *   `pressure / pressureScale`). 0 disables supply/demand entirely. [TUNE]
 * @property pressureScale the pressure magnitude that `elasticity` is measured against; larger ⇒
 *   gentler per-unit moves. Must be > 0. [TUNE]
 * @property minMul lower clamp on the COMBINED price multiplier — the floor that caps a price crash and
 *   (with [decayed] recovery) blocks a degenerate infinite-profit loop. Must be > 0 and <= 1.0. [TUNE]
 * @property maxMul upper clamp on the COMBINED price multiplier — the ceiling that caps a price spike.
 *   Must be >= 1.0. [TUNE]
 * @property driftAmplitude amplitude of the smaller, seeded market-drift term; **0 fully disables
 *   drift** (then pricing is purely player-driven supply/demand + faction). [TUNE]
 * @property driftPeriodTicks how many ticks one drift epoch lasts (`epoch = tick / driftPeriodTicks`),
 *   so drift steps in discrete, seed-reproducible plateaus. Must be > 0. [TUNE]
 * @property decayNum / @property decayDen the per-period mean-reversion fraction
 *   (`step = max(1, abs(pressure) * decayNum / decayDen)`), so accumulated pressure recovers toward 0
 *   over time. `decayDen` must be > 0 and `decayNum` in `0..decayDen`. [TUNE]
 * @property decayPeriodTicks how often (in ticks) decay is applied (`tick % decayPeriodTicks == 0`).
 *   Must be > 0. [TUNE]
 */
data class PricingParams(
    val elasticity: Double = DEFAULT_ELASTICITY,
    val pressureScale: Double = DEFAULT_PRESSURE_SCALE,
    val minMul: Double = DEFAULT_MIN_MUL,
    val maxMul: Double = DEFAULT_MAX_MUL,
    val driftAmplitude: Double = DEFAULT_DRIFT_AMPLITUDE,
    val driftPeriodTicks: Int = DEFAULT_DRIFT_PERIOD_TICKS,
    val decayNum: Int = DEFAULT_DECAY_NUM,
    val decayDen: Int = DEFAULT_DECAY_DEN,
    val decayPeriodTicks: Int = DEFAULT_DECAY_PERIOD_TICKS,
    // UC46: the faction/reputation tunables (consumed by FactionPricing). Identity at neutral standing.
    val factionInfluence: Double = DEFAULT_FACTION_INFLUENCE,
    val factionStandingScale: Double = DEFAULT_FACTION_STANDING_SCALE,
) {
    init {
        require(elasticity >= 0.0) { "elasticity must be >= 0: $elasticity" }
        require(pressureScale > 0.0) { "pressureScale must be > 0: $pressureScale" }
        require(minMul > 0.0) { "minMul must be > 0: $minMul" }
        require(minMul <= 1.0) { "minMul must be <= 1.0 (so identity is representable): $minMul" }
        require(maxMul >= 1.0) { "maxMul must be >= 1.0 (so identity is representable): $maxMul" }
        require(driftAmplitude >= 0.0) { "driftAmplitude must be >= 0: $driftAmplitude" }
        require(driftPeriodTicks > 0) { "driftPeriodTicks must be > 0: $driftPeriodTicks" }
        require(decayDen > 0) { "decayDen must be > 0: $decayDen" }
        require(decayNum in 0..decayDen) { "decayNum must be in 0..decayDen ($decayDen): $decayNum" }
        require(decayPeriodTicks > 0) { "decayPeriodTicks must be > 0: $decayPeriodTicks" }
        require(factionInfluence >= 0.0) { "factionInfluence must be >= 0: $factionInfluence" }
        require(factionStandingScale > 0.0) { "factionStandingScale must be > 0: $factionStandingScale" }
    }

    companion object {
        const val DEFAULT_ELASTICITY: Double = 0.02
        const val DEFAULT_PRESSURE_SCALE: Double = 1.0
        const val DEFAULT_MIN_MUL: Double = 0.5
        const val DEFAULT_MAX_MUL: Double = 1.5
        const val DEFAULT_DRIFT_AMPLITUDE: Double = 0.05
        const val DEFAULT_DRIFT_PERIOD_TICKS: Int = 128
        const val DEFAULT_DECAY_NUM: Int = 1
        const val DEFAULT_DECAY_DEN: Int = 10
        const val DEFAULT_DECAY_PERIOD_TICKS: Int = 64
        const val DEFAULT_FACTION_INFLUENCE: Double = 0.1
        const val DEFAULT_FACTION_STANDING_SCALE: Double = 100.0
    }
}
