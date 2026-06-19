package com.orbitalfrontier.economy

import com.orbitalfrontier.world.PoiId
import kotlin.math.abs

/**
 * The mutable, save-wide per-station market pressure (UC46 AC#1/#3) — the dynamic state behind the
 * authored, immutable [StationMarket]. A pure value over `Map<PoiId, Map<ResourceType, Int>>` of **net
 * signed pressure** per (station, resource), mirroring the shape of
 * [com.orbitalfrontier.world.WorldState.fieldDepletion].
 *
 * **Sign convention.** A **positive** pressure means the player has net-**sold** that resource here →
 * the station is oversupplied → its price drifts **down** (the buyer's market). A **negative** pressure
 * means the player has net-**bought** it → scarcity → the price drifts **up**. [MarketPricing] turns
 * this signed pressure into a per-offer multiplier; only the pressure is persisted (drift + decay are
 * recomputed from the tick, per the ADR-0007 reconstructed-on-load philosophy, so a later re-tune
 * reaches old saves for free).
 *
 * **Byte-identity discipline (UC46 risk #1).** [EMPTY] is the default everywhere ([WorldState],
 * [com.orbitalfrontier.sim.SimulationState], the snapshot DTO via `@EncodeDefault(NEVER)`), and every
 * mutator is a **same-instance no-op** when nothing changes — [withTrade] on a clamped-fill of 0 units,
 * and [decayed] on an empty map or an off-period tick both return `this` unchanged. A fully-recovered
 * state collapses back to [EMPTY]. So a playthrough that never moves a price serializes no pressure at
 * all and stays byte-identical to its pre-UC46 form, with zero fixture regeneration.
 *
 * Pure, immutable, no engine types — every mutation returns a new value — so it composes into the
 * immutable world snapshot the autosave thread reads and stays fully JVM-testable (UC46 AC#4).
 */
data class StationMarketState(
    val pressureByStation: Map<PoiId, Map<ResourceType, Int>> = emptyMap(),
) {
    /** This station's per-resource net pressure, or an empty map when the player has not traded here. */
    fun pressureFor(stationId: PoiId): Map<ResourceType, Int> = pressureByStation[stationId] ?: emptyMap()

    /**
     * Fold one **clamped fill** into the pressure (UC46 AC#1). [units] is the number that actually
     * changed hands ([TradeResult.tradedUnits]) and [kind] which side traded ([TradeResult.kind]); a
     * no-op fill (0 units, or a null kind) returns **this same instance** so a non-trading tick is
     * byte-identical. A SELL adds `+units` (oversupply), a BUY adds `-units` (scarcity); a pressure that
     * lands back on 0 drops the entry (and an emptied station drops out), keeping the map canonical so a
     * sell-then-buy round-trip collapses back to the prior instance's value.
     */
    fun withTrade(
        stationId: PoiId,
        resource: ResourceType,
        kind: TradeKind?,
        units: Int,
    ): StationMarketState {
        if (units <= 0 || kind == null) return this
        val signed = if (kind == TradeKind.SELL) units else -units
        val stationMap = pressureByStation[stationId] ?: emptyMap()
        val next = (stationMap[resource] ?: 0) + signed
        val newStationMap = LinkedHashMap(stationMap)
        if (next == 0) newStationMap.remove(resource) else newStationMap[resource] = next
        val newByStation = LinkedHashMap(pressureByStation)
        if (newStationMap.isEmpty()) newByStation.remove(stationId) else newByStation[stationId] = newStationMap
        return if (newByStation.isEmpty()) EMPTY else StationMarketState(newByStation)
    }

    /**
     * Mean-reversion recovery (UC46 anti-degenerate-loop): step every non-zero pressure toward 0 (UC46
     * AC#1). A **strict no-op returning this same instance** when the map is empty OR this is not a decay
     * tick (`tick % decayPeriodTicks != 0`). Otherwise each entry's magnitude shrinks by
     * `step = max(1, abs(pressure) * decayNum / decayDen)` (integer, no floats), never overshooting 0
     * (`min(step, abs(pressure))`); entries reaching 0 are dropped, emptied stations are dropped, and a
     * fully-recovered state collapses to [EMPTY]. The `max(1, …)` floor guarantees finite-time recovery
     * back to the authored base price, so a price can never be pinned at a floor/ceiling forever.
     */
    fun decayed(
        tick: Int,
        params: PricingParams,
    ): StationMarketState {
        if (pressureByStation.isEmpty() || tick % params.decayPeriodTicks != 0) return this
        val newByStation = LinkedHashMap<PoiId, Map<ResourceType, Int>>()
        for ((station, pressures) in pressureByStation) {
            val newPressures = LinkedHashMap<ResourceType, Int>()
            for ((resource, pressure) in pressures) {
                if (pressure == 0) continue
                val magnitude = abs(pressure)
                val step = maxOf(1, magnitude * params.decayNum / params.decayDen)
                val delta = minOf(step, magnitude)
                val next = pressure - (if (pressure > 0) delta else -delta)
                if (next != 0) newPressures[resource] = next
            }
            if (newPressures.isNotEmpty()) newByStation[station] = newPressures
        }
        return if (newByStation.isEmpty()) EMPTY else StationMarketState(newByStation)
    }

    companion object {
        /** No recorded pressure anywhere — every station prices at its authored base. The default. */
        val EMPTY: StationMarketState = StationMarketState(emptyMap())
    }
}
