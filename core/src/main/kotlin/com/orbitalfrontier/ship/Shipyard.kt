package com.orbitalfrontier.ship

/**
 * A station's shipyard: the set of [ShipTypeId]s it **offers for purchase** (UC09 AC#5).
 *
 * The ship analogue of [com.orbitalfrontier.outfit.OutfitMarket] — pure, immutable authored data that
 * rides with the world ([com.orbitalfrontier.world.Station.shipyard]) rather than the save
 * (ADR 0008). It lists only **which** ship types a yard sells; the **price** of each lives on the
 * [ShipType] in the [ShipRoster] (so a retune is one place). [FleetResolver.resolve] gates a BuyShip
 * on membership here, so a yard only sells the hulls it advertises.
 *
 * A type **absent** from [offered] is not purchasable here ([offers] returns false). The default
 * [EMPTY] (sells nothing) is the right read for a station with no shipyard.
 */
data class Shipyard(
    val offered: Set<ShipTypeId>,
) {
    /** Whether this yard sells [id]. */
    fun offers(id: ShipTypeId): Boolean = id in offered

    companion object {
        /** A station with no shipyard (sells nothing); the default for an authored station. */
        val EMPTY: Shipyard = Shipyard(emptySet())

        /** Convenience: a shipyard offering [ids] (a value class can't be a vararg, so a list). */
        fun of(ids: List<ShipTypeId>): Shipyard = Shipyard(ids.toSet())
    }
}
