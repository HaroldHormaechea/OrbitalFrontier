package com.orbitalfrontier.station

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.world.SectorId

/**
 * The player's station-building intent for one action (UC15 AC#1) — a `sealed` hierarchy
 * (coding-guidelines § O), the station analogue of [com.orbitalfrontier.ship.FleetOrder] /
 * [com.orbitalfrontier.outfit.OutfitOrder] so a future order kind plugs in a new subtype rather than
 * editing a central `when`.
 *
 * [None] is idle. [FoundStation] founds a brand-new station carrying one starting module (the
 * `FleetResolver.BuyShip` analogue). [BuildModule] snaps another module onto an already-owned station
 * (the `Outfitting.BuyInstall` analogue). [StationBuilder.resolve] gates every case.
 */
sealed interface StationBuildOrder {
    /** No station action — [StationBuilder.resolve] returns its inputs unchanged. */
    data object None : StationBuildOrder

    /** Found a new station whose first module is [moduleType], anchored at the docked station's sector. */
    data class FoundStation(val moduleType: StationModuleId) : StationBuildOrder

    /** Build [moduleType] onto the already-owned station [stationId] (lowest free slot). */
    data class BuildModule(
        val stationId: StationId,
        val moduleType: StationModuleId,
    ) : StationBuildOrder
}

/**
 * The outcome of a single [StationBuilder.resolve] call — the new [registry], the new [credits]
 * balance, the new [cargo] (resources spent are drawn from the active ship's hold), and whether
 * anything [changed].
 *
 * A no-op (idle / not build-capable / unknown module / unaffordable / not owned) reports
 * [changed] = false with [registry]/[credits]/[cargo] **unchanged** (coding-guidelines §
 * error-handling: prefer explicit returns over exceptions for expected outcomes).
 */
data class StationBuildResult(
    val registry: StationRegistry,
    val credits: Long,
    val cargo: Cargo,
    val changed: Boolean,
)

/**
 * Pure, deterministic station building (UC15 AC#1/#5) — the station analogue of
 * [com.orbitalfrontier.ship.FleetResolver] / [com.orbitalfrontier.outfit.Outfitting]. A
 * side-effect-free function of (registry, credits, cargo, build-capability, sector, order): identical
 * inputs always yield an identical result, with no I/O and no engine types, so it slots into the
 * deterministic simulation/replay path and is fully JVM-unit-testable (UC15 AC#5). It does **not**
 * mutate anything — the caller applies the [StationBuildResult].
 *
 * Both build actions are **docked-only and gated on the docked station being build-capable**
 * ([buildsStations]) — the station-building service is offered only at a build-capable station, the
 * way outfitting is offered only where there is an outfit market. Affordability is **atomic**: credits
 * and *every* resource line are checked up front and deducted all-or-nothing (a shortfall in any line
 * is a no-op — no partial deduction). All money math is [Long] so a large balance never overflows.
 */
object StationBuilder {
    /**
     * Resolve a single station-build [order] against the player's [registry], [credits] and active
     * ship's [cargo], given whether the docked station is build-capable ([buildsStations]) and the
     * [sector] a newly-founded station is anchored in (the docked station's sector).
     *
     * Returns the inputs **unchanged** (`changed = false`) on any no-op (see [StationBuildResult]).
     *
     * - **FoundStation:** gated on [buildsStations] (AC#1), the module being catalogued, and the cost
     *   being affordable (atomic). On success a fresh station — id allocated by
     *   [StationRegistry.nextStationId], anchored in [sector], carrying the module in slot 0 — is added
     *   and its cost deducted.
     * - **BuildModule:** gated on [buildsStations], owning the target station, the module being
     *   catalogued, and affordability (atomic). On success the module is snapped onto the station's
     *   lowest free slot and its cost deducted.
     */
    fun resolve(
        registry: StationRegistry,
        credits: Long,
        cargo: Cargo,
        buildsStations: Boolean,
        sector: SectorId,
        order: StationBuildOrder,
        catalog: StationModuleCatalog = StationModuleCatalog.MVP,
    ): StationBuildResult {
        val unchanged = StationBuildResult(registry, credits, cargo, false)
        return when (order) {
            StationBuildOrder.None -> unchanged
            is StationBuildOrder.FoundStation -> resolveFound(registry, credits, cargo, buildsStations, sector, order, catalog, unchanged)
            is StationBuildOrder.BuildModule -> resolveBuild(registry, credits, cargo, buildsStations, order, catalog, unchanged)
        }
    }

    private fun resolveFound(
        registry: StationRegistry,
        credits: Long,
        cargo: Cargo,
        buildsStations: Boolean,
        sector: SectorId,
        order: StationBuildOrder.FoundStation,
        catalog: StationModuleCatalog,
        unchanged: StationBuildResult,
    ): StationBuildResult {
        if (!buildsStations) return unchanged // station can't build here (AC#1)
        val module = catalog.module(order.moduleType) ?: return unchanged // unknown module
        val paid = deduct(credits, cargo, module.cost) ?: return unchanged // can't afford (atomic)
        val station = OwnedStation.founded(registry.nextStationId(), sector, module.id)
        return StationBuildResult(registry.addStation(station), paid.first, paid.second, true)
    }

    private fun resolveBuild(
        registry: StationRegistry,
        credits: Long,
        cargo: Cargo,
        buildsStations: Boolean,
        order: StationBuildOrder.BuildModule,
        catalog: StationModuleCatalog,
        unchanged: StationBuildResult,
    ): StationBuildResult {
        if (!buildsStations) return unchanged // station can't build here
        val existing = registry.station(order.stationId) ?: return unchanged // not owned
        val module = catalog.module(order.moduleType) ?: return unchanged // unknown module
        val paid = deduct(credits, cargo, module.cost) ?: return unchanged // can't afford (atomic)
        return StationBuildResult(registry.withStation(existing.addModule(module.id)), paid.first, paid.second, true)
    }

    /**
     * Atomically pay [cost] out of [credits] + [cargo] (challenger note: all-or-nothing). Returns the
     * new (credits, cargo) when *both* the credit price and *every* resource line are covered, or null
     * when any single line falls short — so the caller leaves the wallet and hold untouched on a
     * shortfall (no partial deduction). Resources are drawn from the active ship's hold via
     * [Cargo.remove], which drops a resource key at 0 (the hold never carries zero-count entries).
     */
    private fun deduct(
        credits: Long,
        cargo: Cargo,
        cost: StationBuildCost,
    ): Pair<Long, Cargo>? {
        if (credits < cost.credits) return null
        for ((resource, units) in cost.resources) {
            if ((cargo.contents[resource] ?: 0) < units) return null
        }
        var newCargo = cargo
        for ((resource, units) in cost.resources) {
            newCargo = newCargo.remove(resource, units)
        }
        return (credits - cost.credits) to newCargo
    }
}
