package com.orbitalfrontier.station

/**
 * The player-owned stations (UC15 AC#3) — the [com.orbitalfrontier.ship.Fleet] analogue for stations.
 *
 * [stations] is kept **sorted by [StationId]** with unique ids, so the whole snapshot's data-class
 * equality is stable across a record/replay round-trip (UC15 determinism) regardless of build order.
 * Unlike a [com.orbitalfrontier.ship.Fleet] (always ≥ 1 ship) a registry starts **empty** ([EMPTY]):
 * a new game owns no stations, and a pre-UC15 save migrates to an empty registry, so the default
 * keeps the world snapshot byte-identical. In the MVP a registry only ever **grows** — stations are
 * founded ([addStation]) and gain modules ([withStation]); they are never removed (AC#3), so there is
 * no remove path and no delete persistence query.
 *
 * Pure, immutable value (no engine types) so the station model is fully JVM-testable (UC15 AC#5).
 */
data class StationRegistry(
    val stations: List<OwnedStation> = emptyList(),
) {
    init {
        val ids = stations.map { it.id.value }
        require(ids.toSet().size == ids.size) { "StationRegistry station ids must be unique: $ids" }
        require(ids == ids.sorted()) { "StationRegistry stations must be sorted by id: $ids" }
    }

    /** True when the player owns no stations (the common, pre-UC15 case). */
    val isEmpty: Boolean get() = stations.isEmpty()

    /** How many stations the player owns. */
    val size: Int get() = stations.size

    /** The owned station with [id], or null if the player does not own it. */
    fun station(id: StationId): OwnedStation? = stations.firstOrNull { it.id == id }

    /**
     * Add [station] to the registry, keeping the list sorted by id (UC15 AC#1/#3).
     *
     * @throws IllegalArgumentException if [station]'s id is already owned (a programmer error — the
     *   builder allocates a fresh id via [nextStationId]).
     */
    fun addStation(station: OwnedStation): StationRegistry {
        require(stations.none { it.id == station.id }) { "addStation: ${station.id.value} is already owned" }
        return copy(stations = (stations + station).sortedBy { it.id.value })
    }

    /**
     * Replace whatever station shares [updated]'s id with [updated], preserving sort order (the id is
     * unchanged, so position is stable) — the [com.orbitalfrontier.ship.Fleet.withShip] analogue, used
     * when a module is built onto an existing station.
     *
     * @throws IllegalArgumentException if no owned station has [updated]'s id (a programmer error).
     */
    fun withStation(updated: OwnedStation): StationRegistry {
        require(stations.any { it.id == updated.id }) { "withStation: ${updated.id.value} is not owned" }
        return copy(stations = stations.map { if (it.id == updated.id) updated else it })
    }

    /**
     * The next free [StationId] — `max(id) + 1`, or 0 when empty (UC15). A **pure** function of the
     * current ids (the [com.orbitalfrontier.ship.Fleet.nextShipId] analogue): no global counter and no
     * time source, so id allocation is deterministic and replay-stable.
     */
    fun nextStationId(): StationId = StationId((stations.maxOfOrNull { it.id.value } ?: -1L) + 1L)

    companion object {
        /** The empty registry — a new game and every pre-UC15 / migrated save start here. */
        val EMPTY: StationRegistry = StationRegistry()
    }
}
