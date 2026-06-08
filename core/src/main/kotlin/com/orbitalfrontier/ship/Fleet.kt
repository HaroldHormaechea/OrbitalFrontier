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

/**
 * The player's fleet intent for one action (UC09 AC#5) — the fleet analogue of
 * [com.orbitalfrontier.outfit.OutfitOrder]/[com.orbitalfrontier.economy.TradeOrder], a `sealed`
 * hierarchy (coding-guidelines § O).
 *
 * [None] is idle. [BuyShip] buys a hull from the docked station's shipyard and adds it to the fleet.
 * [SwitchActive] makes an owned ship the active one. [FleetResolver.resolve] gates every case.
 */
sealed interface FleetOrder {
    /** No fleet action — [FleetResolver.resolve] returns its inputs unchanged. */
    data object None : FleetOrder

    /** Buy a ship of [typeId] from the docked station's shipyard and add it to the fleet. */
    data class BuyShip(val typeId: ShipTypeId) : FleetOrder

    /** Switch the active ship to the owned ship [shipId]. */
    data class SwitchActive(val shipId: ShipId) : FleetOrder
}

/**
 * The outcome of a single [FleetResolver.resolve] call — the new fleet, the new credit balance, and
 * whether anything changed. A no-op (can't afford / not offered / already active / not owned) reports
 * [changed] = false with [fleet]/[credits] unchanged (coding-guidelines § error-handling).
 */
data class FleetResult(
    val fleet: Fleet,
    val credits: Long,
    val changed: Boolean,
)

/**
 * Pure, deterministic fleet operations (UC09 AC#5/#7) — buying a ship and switching the active ship,
 * both **docked-only** (the caller resolves this only while docked, mirroring [Outfitting]). A
 * side-effect-free function with no I/O and no engine types, so it slots into the deterministic
 * simulation/replay path and is fully JVM-unit-testable. It takes the decoupled [Shipyard] (not a
 * world `Station`) so the `ship` package stays world-agnostic.
 *
 * All money math is [Long] so a large balance can never overflow.
 */
object FleetResolver {
    /**
     * Resolve a single fleet [order] against the player's [fleet] and [credits], given the docked
     * station's [shipyard].
     *
     * - **BuyShip:** gated on the type being **offered** by [shipyard] (AC#5), known to [ShipRoster],
     *   and affordable (`credits >= type.price`). On success a fresh ship of that type — spawned at the
     *   active ship's (docked) position so switching to it does not teleport the player — is appended
     *   and its price deducted. The active ship is unchanged.
     * - **SwitchActive:** gated on owning the target ship (AC#5). A no-op when it is already active.
     */
    fun resolve(
        fleet: Fleet,
        credits: Long,
        shipyard: Shipyard,
        order: FleetOrder,
    ): FleetResult {
        val unchanged = FleetResult(fleet, credits, false)
        return when (order) {
            FleetOrder.None -> unchanged
            is FleetOrder.BuyShip -> resolveBuyShip(fleet, credits, shipyard, order, unchanged)
            is FleetOrder.SwitchActive -> resolveSwitchActive(fleet, credits, order, unchanged)
        }
    }

    private fun resolveBuyShip(
        fleet: Fleet,
        credits: Long,
        shipyard: Shipyard,
        order: FleetOrder.BuyShip,
        unchanged: FleetResult,
    ): FleetResult {
        if (!shipyard.offers(order.typeId)) return unchanged // not sold here
        val type = ShipRoster.byId(order.typeId) ?: return unchanged // unknown type
        if (credits < type.price) return unchanged // can't afford
        val newShip = OwnedShip.fresh(fleet.nextShipId(), type, fleet.active.kinematics.position)
        return FleetResult(fleet.addShip(newShip), credits - type.price, true)
    }

    private fun resolveSwitchActive(
        fleet: Fleet,
        credits: Long,
        order: FleetOrder.SwitchActive,
        unchanged: FleetResult,
    ): FleetResult {
        if (fleet.ship(order.shipId) == null) return unchanged // not owned
        if (order.shipId == fleet.activeShipId) return unchanged // already active — no-op
        return FleetResult(fleet.switchActive(order.shipId), credits, true)
    }
}
