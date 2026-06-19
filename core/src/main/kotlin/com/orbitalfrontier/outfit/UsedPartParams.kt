package com.orbitalfrontier.outfit

/**
 * Authored balancing tunables for the junkyard **buy-used** system (UC47, ADR 0035) — the used-part
 * analogue of [com.orbitalfrontier.economy.PricingParams] / [com.orbitalfrontier.economy.FuelParams].
 *
 * Pure data injected into the (pure) used-part functions ([UsedPartPricing], [Outfitting.resolve]).
 * Injecting the params keeps used-part pricing + stock a pure function of their inputs — the same
 * params + the same junkyard/part + the same player purchases always yield the same price and the same
 * available count — so the whole system is deterministic and JVM-testable (UC47 AC#4), and a recorded
 * playthrough can pin the exact tuning it ran under ([com.orbitalfrontier.playthrough.Playthrough
 * .usedPartConfig]) so a later default retune can never silently invalidate an old replay.
 *
 * **MVP — purely cheaper, no condition/wear** (use-case decision). A used part costs
 * `round(newPrice × [discountFraction])` and is otherwise identical to the new part; there is no
 * durability/quality dimension. The baseline available stock per (junkyard, part) is a deterministic
 * draw in `[minStock, maxStock]` (see [UsedPartPricing.baselineStock]); only the player's purchased
 * depletion is persisted (the deterministic-baseline + persisted-depletion model, ADR 0035).
 *
 * Every `[TUNE]` constant is a balancing placeholder; none affects determinism, which is fixed by the
 * (pure) used-part algorithms.
 *
 * @property discountFraction the fraction of an upgrade's catalog (new) price a used copy costs — must
 *   be in the **open** interval `(0, 1)` so a used part is strictly cheaper than new yet never free.
 *   Authored at 0.6, deliberately **above** the [Outfitting.USED_PART_REFUND_FRACTION] of 0.5 so a
 *   buy-used-then-sell round-trip always loses money (no refund-arbitrage exploit). [TUNE]
 * @property minStock the smallest baseline count a junkyard can stock of any used part (inclusive). Must
 *   be >= 0. [TUNE]
 * @property maxStock the largest baseline count a junkyard can stock of any used part (inclusive). Must
 *   be >= [minStock]. [TUNE]
 */
data class UsedPartParams(
    val discountFraction: Double = DEFAULT_DISCOUNT_FRACTION,
    val minStock: Int = DEFAULT_MIN_STOCK,
    val maxStock: Int = DEFAULT_MAX_STOCK,
) {
    init {
        require(discountFraction > 0.0) { "discountFraction must be > 0: $discountFraction" }
        require(discountFraction < 1.0) { "discountFraction must be < 1 (used is cheaper than new): $discountFraction" }
        require(minStock >= 0) { "minStock must be >= 0: $minStock" }
        require(maxStock >= minStock) { "maxStock must be >= minStock ($minStock): $maxStock" }
    }

    companion object {
        /** Used parts cost 60% of the new (catalog) price by default — above the 0.5 sell refund. [TUNE] */
        const val DEFAULT_DISCOUNT_FRACTION: Double = 0.6

        /** A junkyard stocks at least this many of any offered used part. [TUNE] */
        const val DEFAULT_MIN_STOCK: Int = 1

        /** A junkyard stocks at most this many of any offered used part. [TUNE] */
        const val DEFAULT_MAX_STOCK: Int = 3
    }
}
