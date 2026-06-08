package com.orbitalfrontier.station

import com.orbitalfrontier.world.SectorId

/**
 * One station the player owns (UC15 AC#1/#2/#3) — its stable [id], the [sector] it is anchored in,
 * and the [modules] snapped onto it.
 *
 * [modules] is a **gap-tolerant** map `slotIndex → StationModuleId` — the
 * [com.orbitalfrontier.outfit.Loadout] analogue: a module is addressed by its slot position, and the
 * map stores only the module **id** per slot (the full [StationModule], its function and cost, is
 * authored data resolved through [StationModuleCatalog], never persisted). Storing ids keeps
 * persistence a plain `(station_id, slot_index)` row set and lets a cost/function retune live in one
 * place. Equality is value-based (Kotlin `Map` equality), so two stations with the same modules in
 * the same slots compare equal regardless of build order — keeping the whole-snapshot data-class
 * equality stable across a record/replay round-trip (UC15 determinism).
 *
 * **[availableFunctions] is derived, never stored** (AC#2): the set of [StationFunction]s the
 * station's installed modules expose, resolved through the catalog. A module id the catalog no longer
 * knows contributes nothing (it was already dropped on load).
 *
 * Pure, immutable value (no engine types) so the station model is fully JVM-testable (UC15 AC#5):
 * every change returns a new [OwnedStation].
 */
data class OwnedStation(
    val id: StationId,
    val sector: SectorId,
    val modules: Map<Int, StationModuleId> = emptyMap(),
) {
    /** How many modules are currently built on this station. */
    val moduleCount: Int get() = modules.size

    /** The module id in [slotIndex], or null if that slot is empty. */
    fun moduleAt(slotIndex: Int): StationModuleId? = modules[slotIndex]

    /**
     * The set of [StationFunction]s this station's modules expose (UC15 AC#2), resolved through
     * [catalog]. A module id the catalog no longer knows contributes nothing. Derived from the modules
     * on every call (cheap; the module count is small) — never stored.
     */
    fun availableFunctions(catalog: StationModuleCatalog = StationModuleCatalog.MVP): Set<StationFunction> =
        modules.values.mapNotNullTo(LinkedHashSet()) { catalog.module(it)?.function }

    /**
     * Snap [moduleId] onto the **lowest free slot index** (UC15) — the [Loadout.install] analogue,
     * except a station has no per-category slot cap, so the first index `>= 0` not already filled is
     * used (with `n` modules there is always a free index in `0..n`). Returns a new station.
     */
    fun addModule(moduleId: StationModuleId): OwnedStation {
        val freeIndex = (0..modules.size).first { it !in modules }
        return copy(modules = modules + (freeIndex to moduleId))
    }

    companion object {
        /**
         * Found a brand-new station with id [id], anchored in [sector], carrying [firstModule] in slot
         * 0 (UC15 AC#1) — what [StationBuilder] produces for a `FoundStation` order (the
         * [com.orbitalfrontier.ship.OwnedShip.fresh] analogue).
         */
        fun founded(
            id: StationId,
            sector: SectorId,
            firstModule: StationModuleId,
        ): OwnedStation = OwnedStation(id = id, sector = sector, modules = mapOf(0 to firstModule))
    }
}
