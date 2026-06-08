package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.UpgradeCatalog

/**
 * Stable identity of an owned ship within the player's [Fleet] (UC09 AC#5). A value class over a
 * [Long] so a ship is keyed independently of its position in the (sorted) fleet list and the
 * `game_state.active_ship_id` / `ship.id` columns address it directly. The starter ship is always
 * [STARTER_SHIP_ID].
 */
@JvmInline
value class ShipId(val value: Long)

/**
 * One ship the player owns (UC09 AC#5) — its [type], spatial [kinematics], cargo [Cargo], fuel
 * [Fuel], and installed [Loadout]. Each owned ship keeps **its own** cargo/fuel/loadout, so switching
 * the active ship swaps the whole fit-out, not just the hull (AC#5).
 *
 * Pure, immutable value (no engine types) so the fleet model is fully JVM-testable (UC09 AC#7).
 *
 * **Capacity is a derived stat, re-derived in exactly one place.** [withLoadout] is the single point
 * that rebuilds [cargo] and [fuel] capacities from `type + loadout` via [ShipStats] after a fit
 * change — so an install/remove can never leave a stale capacity, and contents/level are preserved
 * (clamped down only if a capacity shrank). Other mutations (`copy(kinematics = …)`, fuel burn, cargo
 * load) keep the same loadout and so the same capacity, and intentionally bypass re-derivation.
 */
data class OwnedShip(
    val id: ShipId,
    val type: ShipType,
    val kinematics: ShipKinematics,
    val cargo: Cargo,
    val fuel: Fuel,
    val loadout: Loadout = Loadout.EMPTY,
) {
    /**
     * Return this ship fitted with [newLoadout], re-deriving [cargo] and [fuel] **capacities** from
     * `type + newLoadout` via [ShipStats] (the single re-derivation point, UC09 AC#2). Cargo contents
     * and fuel level are preserved; if a capacity shrank below what is currently held/filled, contents
     * are clamped to fit (deterministically, in [ResourceType] order) and the fuel level is coerced
     * down to the new capacity — so the [Cargo]/[Fuel] invariants always hold.
     */
    fun withLoadout(
        newLoadout: Loadout,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): OwnedShip {
        val newCargoCapacity = ShipStats.cargoCapacity(type, newLoadout, catalog)
        val newFuelCapacity = ShipStats.fuelCapacity(type, newLoadout, catalog)
        return copy(
            loadout = newLoadout,
            cargo = Cargo(clampContents(cargo.contents, newCargoCapacity), newCargoCapacity),
            fuel = Fuel(level = fuel.level.coerceAtMost(newFuelCapacity), capacity = newFuelCapacity),
        )
    }

    companion object {
        /** The id of the starter ship — the one a new game and every migrated save begin with. */
        val STARTER_SHIP_ID: ShipId = ShipId(0L)

        /**
         * Build the starter [OwnedShip]: the [ShipRoster.STARTER] type, an empty [Loadout], at rest,
         * with an empty hold and a full tank — all capacities derived from the type so they equal
         * today's constants (UC09 byte-identical contract). The default ship for a new game and the
         * single ship a migrated/legacy save reconstructs.
         */
        fun starter(): OwnedShip = fresh(STARTER_SHIP_ID, ShipRoster.STARTER)

        /**
         * Build a brand-new [OwnedShip] of [type] with id [id]: an empty [Loadout], an empty hold and a
         * full tank (capacities derived from [type] via [ShipStats]), at rest at [spawnPosition]. The
         * ship a freshly-bought hull (UC09 AC#5) or the starter begins as. [spawnPosition] lets a bought
         * ship appear where the player is docked, so switching to it does not teleport them away.
         */
        fun fresh(
            id: ShipId,
            type: ShipType,
            spawnPosition: Vec2 = Vec2.ZERO,
        ): OwnedShip {
            val loadout = Loadout.EMPTY
            val cargoCapacity = ShipStats.cargoCapacity(type, loadout)
            val fuelCapacity = ShipStats.fuelCapacity(type, loadout)
            return OwnedShip(
                id = id,
                type = type,
                kinematics = ShipKinematics(position = spawnPosition),
                cargo = Cargo.empty(cargoCapacity),
                fuel = Fuel(level = fuelCapacity, capacity = fuelCapacity),
                loadout = loadout,
            )
        }

        /**
         * Drop [contents] down to [capacity] total units when it currently exceeds it, keeping units in
         * [ResourceType] declaration order (deterministic) until the cap is reached. A no-op when
         * everything already fits (the common case — capacity only ever grows in the MVP).
         */
        private fun clampContents(
            contents: Map<ResourceType, Int>,
            capacity: Int,
        ): Map<ResourceType, Int> {
            if (contents.values.sum() <= capacity) return contents
            val clamped = LinkedHashMap<ResourceType, Int>()
            var remaining = capacity
            for (resource in ResourceType.entries) {
                if (remaining <= 0) break
                val held = contents[resource] ?: 0
                if (held <= 0) continue
                val keep = held.coerceAtMost(remaining)
                clamped[resource] = keep
                remaining -= keep
            }
            return clamped
        }
    }
}
