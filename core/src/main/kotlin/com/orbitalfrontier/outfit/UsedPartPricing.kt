package com.orbitalfrontier.outfit

import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.world.PoiId
import kotlin.math.roundToLong

/**
 * The pure used-part pricing + stock engine (UC47 AC#1/#3/#4) — the used-part analogue of
 * [com.orbitalfrontier.economy.MarketPricing]. Two side-effect-free, deterministic functions:
 *
 *  1. **[usedPrice]** — the data-driven discount curve (AC#1/#4): `round(newPrice ×
 *     discountFraction)`, clamped to `>= 1` so a used part is never free. The discount lives entirely
 *     in [UsedPartParams.discountFraction] (no magic per-call number), so it applies consistently
 *     across every junkyard.
 *  2. **[baselineStock]** — the deterministic baseline available count per (junkyard, part) (AC#3): a
 *     `[minStock, maxStock]` draw from the shared [DeterministicRng], keyed purely on the stable slugs
 *     `"usedstock:<junkyardId>:<partId>"`. No world seed, no wall clock, no `Math.random` — so the
 *     baseline is reproducible on any JVM and replay-safe. **Time-based restock is deliberately not
 *     modelled** (the use-case open question — deferred per ADR 0035): the key carries no epoch, so the
 *     baseline is fixed for the life of the save and only player purchases (the persisted depletion in
 *     [JunkyardStock]) reduce what is `available`.
 *
 * Pure, no engine types, so it is fully JVM-unit-testable (UC47 AC#4).
 */
object UsedPartPricing {
    /**
     * The used (discounted) price of a part whose new/catalog price is [newPrice], under [params]:
     * `round(newPrice × discountFraction)` clamped to `>= 1` (a used part always costs at least 1
     * credit). All money math is [Long] so a large price never overflows.
     */
    fun usedPrice(
        newPrice: Long,
        params: UsedPartParams,
    ): Long = (newPrice * params.discountFraction).roundToLong().coerceAtLeast(1)

    /**
     * The deterministic **baseline** available count of part [partId] at junkyard [junkyardId] under
     * [params] — a uniform draw in `[minStock, maxStock]` (inclusive) from the shared [DeterministicRng]
     * keyed on the stable slugs only. The same (junkyard, part) always yields the same baseline; the
     * **available** count is this baseline minus the player's persisted purchases (see
     * [JunkyardStock.purchasedCount]), so depletion persists across save/reload while the baseline is
     * recomputed (not stored) on load — the model documented in ADR 0035.
     */
    fun baselineStock(
        junkyardId: PoiId,
        partId: UpgradeId,
        params: UsedPartParams,
    ): Int {
        val span = params.maxStock - params.minStock + 1
        val advanced = DeterministicRng.lcgAdvance(DeterministicRng.fnv1a("usedstock:${junkyardId.value}:${partId.value}"))
        return DeterministicRng.boundedInt(advanced, span) + params.minStock
    }
}
