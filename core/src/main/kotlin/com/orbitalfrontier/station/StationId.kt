package com.orbitalfrontier.station

/**
 * Stable identity of a player-owned [OwnedStation] within the [StationRegistry] (UC15 AC#1/#3).
 *
 * A value class over a [Long] so a station is keyed independently of its position in the (sorted)
 * registry list and the `owned_station.id` / `station_module.station_id` columns address it directly.
 * Its namespace is **separate** from [com.orbitalfrontier.ship.ShipId] and
 * [com.orbitalfrontier.world.PoiId]: an owned station is a distinct kind of player entity (it is not
 * a ship, nor an authored world POI), so the three id spaces never share a value or collide.
 *
 * Ids are allocated by [StationRegistry.nextStationId] (`max(id) + 1`, deterministic), starting at 0
 * for the first founded station.
 */
@JvmInline
value class StationId(val value: Long)
