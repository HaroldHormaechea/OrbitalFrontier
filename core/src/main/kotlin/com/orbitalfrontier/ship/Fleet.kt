package com.orbitalfrontier.ship

/**
 * The ships the player owns and which one is active (UC09 AC#5).
 *
 * [ships] is kept **sorted by [ShipId]** with unique ids, and [activeShipId] is stored separately
 * (not as a list position) so switching the active ship never reorders the list — keeping the whole
 * snapshot's data-class equality stable across a record/replay round-trip (UC09 determinism). The
 * active ship is resolved by id ([active]); all mutators are pure and return a new [Fleet].
 *
 * In the MVP a fleet only ever **grows** ([addShip]) — ships are never removed or traded in (that and
 * idle-ship storage are out of scope). A new game and every migrated/legacy save start as a
 * single-ship fleet ([starter]); buying ships ([com.orbitalfrontier.ship.FleetResolver]) appends.
 *
 * Pure, immutable value (no engine types) so the fleet model is fully JVM-testable (UC09 AC#7).
 */
data class Fleet(
    val ships: List<OwnedShip>,
    val activeShipId: ShipId,
) {
    init {
        require(ships.isNotEmpty()) { "Fleet must contain at least one ship" }
        val ids = ships.map { it.id.value }
        require(ids.toSet().size == ids.size) { "Fleet ship ids must be unique: $ids" }
        require(ids == ids.sorted()) { "Fleet ships must be sorted by id: $ids" }
        require(ships.any { it.id == activeShipId }) {
            "Fleet activeShipId ${activeShipId.value} is not an owned ship: $ids"
        }
    }

    /** The currently active ship (the one the player flies / outfits). */
    val active: OwnedShip get() = ships.first { it.id == activeShipId }

    /** True when the player owns more than one ship (so a switch is meaningful). */
    val hasMultipleShips: Boolean get() = ships.size > 1

    /** The owned ship with [id], or null if the player does not own it. */
    fun ship(id: ShipId): OwnedShip? = ships.firstOrNull { it.id == id }

    /**
     * Replace whatever ship shares [updated]'s id with [updated], preserving sort order (the id is
     * unchanged, so position is stable). The common per-tick path: fold the active ship's new
     * kinematics/​cargo/​fuel/​loadout back into the fleet.
     *
     * @throws IllegalArgumentException if no owned ship has [updated]'s id (a programmer error).
     */
    fun withShip(updated: OwnedShip): Fleet {
        require(ships.any { it.id == updated.id }) { "withShip: ${updated.id.value} is not owned" }
        return copy(ships = ships.map { if (it.id == updated.id) updated else it })
    }

    /** [withShip] applied to the **active** ship — replace the active ship with [updated] (same id). */
    fun withActive(updated: OwnedShip): Fleet {
        require(updated.id == activeShipId) {
            "withActive: ${updated.id.value} is not the active ship (${activeShipId.value})"
        }
        return withShip(updated)
    }

    /**
     * Switch the active ship to [id] (UC09 AC#5). Returns a fleet with [activeShipId] = [id] and the
     * **same** ship list (no reorder). A no-op (returns `this`) when [id] is already active.
     *
     * @throws IllegalArgumentException if [id] is not an owned ship (a programmer error — the resolver
     *   gates this on ownership before calling).
     */
    fun switchActive(id: ShipId): Fleet {
        require(ships.any { it.id == id }) { "switchActive: ${id.value} is not owned" }
        if (id == activeShipId) return this
        return copy(activeShipId = id)
    }

    /**
     * Add [ship] to the fleet (UC09 AC#5), keeping the list sorted by id; the active ship is unchanged.
     *
     * @throws IllegalArgumentException if [ship]'s id is already owned (a programmer error — the
     *   resolver allocates a fresh id via [nextShipId]).
     */
    fun addShip(ship: OwnedShip): Fleet {
        require(ships.none { it.id == ship.id }) { "addShip: ${ship.id.value} is already owned" }
        return copy(ships = (ships + ship).sortedBy { it.id.value })
    }

    /** The next free [ShipId] (one past the current maximum) — used when buying a ship. */
    fun nextShipId(): ShipId = ShipId((ships.maxOf { it.id.value }) + 1L)

    companion object {
        /** The starting fleet: a single starter ship, active. A new game and every legacy save begin here. */
        fun starter(): Fleet {
            val ship = OwnedShip.starter()
            return Fleet(listOf(ship), ship.id)
        }
    }
}
