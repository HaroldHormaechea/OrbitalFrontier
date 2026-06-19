package com.orbitalfrontier.outfit

import com.orbitalfrontier.world.PoiId

/**
 * The mutable, save-wide junkyard **used-part depletion** (UC47 AC#3) — the persisted half of the
 * deterministic-baseline + persisted-depletion stock model (ADR 0035), and the used-part analogue of
 * [com.orbitalfrontier.economy.StationMarketState].
 *
 * A pure value over `Map<PoiId, Map<UpgradeId, Int>>` of the **count the player has already bought** per
 * (junkyard, part). The baseline available stock is a pure function of (junkyard, part) recomputed on
 * load ([UsedPartPricing.baselineStock]); this records only the depletion, so
 * `available = max(0, baseline − purchased)`. Persisting the *purchases* (not the remaining count) is
 * what kills the reload-restock exploit: the depletion is durable, so a player cannot reload to refill a
 * junkyard with cheap parts.
 *
 * **Byte-identity discipline (UC47 risk).** [EMPTY] is the default everywhere ([com.orbitalfrontier
 * .world.WorldState], [com.orbitalfrontier.sim.SimulationState], the snapshot DTO via
 * `@EncodeDefault(NEVER)`), and [withPurchase] is a **same-instance no-op** on a 0-unit purchase. No
 * existing fixture buys a used part, so a playthrough that never buys used serializes no depletion at
 * all and stays byte-identical to its pre-UC47 form, with zero fixture regeneration. The map is kept
 * **canonical** (a 0 count is never stored), mirroring [StationMarketState].
 *
 * Pure, immutable, no engine types — every mutation returns a new value — so it composes into the
 * immutable world snapshot the autosave thread reads and stays fully JVM-testable (UC47 AC#4).
 */
data class JunkyardStock(
    val purchasedByStation: Map<PoiId, Map<UpgradeId, Int>> = emptyMap(),
) {
    /** How many of [partId] the player has bought at [stationId] (0 when never bought here). */
    fun purchasedCount(
        stationId: PoiId,
        partId: UpgradeId,
    ): Int = purchasedByStation[stationId]?.get(partId) ?: 0

    /**
     * Record buying [units] of [partId] at [stationId] (UC47 AC#3). A no-op purchase ([units] <= 0)
     * returns **this same instance** so a non-buying tick is byte-identical. Otherwise the purchased
     * count grows by [units] (the count is monotonic — used parts are never un-bought — so it can never
     * reach 0 once positive, but the canonical drop-zero is kept for symmetry with [StationMarketState]).
     */
    fun withPurchase(
        stationId: PoiId,
        partId: UpgradeId,
        units: Int,
    ): JunkyardStock {
        if (units <= 0) return this
        val stationMap = purchasedByStation[stationId] ?: emptyMap()
        val next = (stationMap[partId] ?: 0) + units
        val newStationMap = LinkedHashMap(stationMap)
        if (next == 0) newStationMap.remove(partId) else newStationMap[partId] = next
        val newByStation = LinkedHashMap(purchasedByStation)
        if (newStationMap.isEmpty()) newByStation.remove(stationId) else newByStation[stationId] = newStationMap
        return if (newByStation.isEmpty()) EMPTY else JunkyardStock(newByStation)
    }

    companion object {
        /** No recorded purchases anywhere — every junkyard part at its full baseline. The default. */
        val EMPTY: JunkyardStock = JunkyardStock(emptyMap())
    }
}
