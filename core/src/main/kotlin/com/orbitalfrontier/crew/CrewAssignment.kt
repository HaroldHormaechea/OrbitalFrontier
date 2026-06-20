package com.orbitalfrontier.crew

import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.ShipId

/**
 * The player's crew-management intent for one action (UC50 AC#3) — the crew analogue of
 * [com.orbitalfrontier.crew.HireOrder] / [com.orbitalfrontier.ship.FleetOrder], a `sealed` hierarchy
 * (coding-guidelines § O) so a future action (e.g. dismiss crew) plugs in a new subtype rather than
 * editing a central `when`. Switching the **active ship** is NOT here — that reuses the existing
 * [com.orbitalfrontier.ship.FleetOrder.SwitchActive] / [com.orbitalfrontier.ship.FleetResolver] path
 * (the fleet/crew screen simply surfaces it), so there is no duplicated switch logic.
 */
sealed interface CrewOrder {
    /** No crew action — [CrewAssignment.resolve] returns its inputs unchanged. */
    data object None : CrewOrder

    /** Move the crew member [crewId] to the owned ship [toShipId] (clamped to its crew capacity). */
    data class Reassign(val crewId: CrewId, val toShipId: ShipId) : CrewOrder

    /** Change the crew member [crewId]'s [role] (inert metadata in the MVP — no system effect). */
    data class ChangeRole(val crewId: CrewId, val role: CrewRole) : CrewOrder
}

/**
 * The outcome of a single [CrewAssignment.resolve] — the (possibly) new [fleet] and [roster], and
 * whether anything changed. A no-op (idle / unknown member / not owned / target full / already there)
 * reports [changed] = false with [fleet]/[roster] unchanged (coding-guidelines § error-handling: an
 * expected "can't do that" is a normal result, not an exception).
 */
data class CrewAssignmentResult(
    val fleet: Fleet,
    val roster: CrewRoster,
    val changed: Boolean,
)

/**
 * Pure, deterministic crew assignment (UC50 AC#3) — reassign a crew member to another owned ship, or
 * change their role. A side-effect-free function of `(fleet, roster, order)` with no I/O and no engine
 * types, so it is fully JVM-unit-testable and the screen layer just folds the [CrewAssignmentResult]
 * back into world state.
 *
 * **Two facets kept in sync.** A reassignment moves both the crew **count** (decrement the source ship's
 * [com.orbitalfrontier.ship.OwnedShip.crew], increment the target's, via the single
 * [com.orbitalfrontier.ship.OwnedShip.withCrew] clamp point) **and** the roster identity (the member's
 * [CrewMember.assignedShipId]) together, so the `forShip(s).size == s.crew` invariant always holds. A
 * role change touches only the roster (no count moves). The active-ship switch is intentionally NOT here
 * — it reuses [com.orbitalfrontier.ship.FleetResolver].
 */
object CrewAssignment {
    /**
     * Resolve a single crew [order] against the player's [fleet] and [roster].
     *
     * - **Reassign:** gated on the member existing, the target being a *different* owned ship, and the
     *   target having a free berth (`target.crew < ShipStats.crewCapacity(target)`). On success one crew
     *   count moves source → target and the member's [CrewMember.assignedShipId] is updated. A full
     *   target, an unknown member, an unowned target, or a same-ship request is a no-op.
     * - **ChangeRole:** gated on the member existing and the role actually differing. A no-op otherwise.
     */
    fun resolve(
        fleet: Fleet,
        roster: CrewRoster,
        order: CrewOrder,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): CrewAssignmentResult {
        val unchanged = CrewAssignmentResult(fleet, roster, false)
        return when (order) {
            CrewOrder.None -> unchanged
            is CrewOrder.ChangeRole -> {
                val member = roster.member(order.crewId) ?: return unchanged
                if (member.role == order.role) return unchanged
                CrewAssignmentResult(fleet, roster.withUpdated(member.copy(role = order.role)), true)
            }
            is CrewOrder.Reassign -> resolveReassign(fleet, roster, order, catalog, unchanged)
        }
    }

    private fun resolveReassign(
        fleet: Fleet,
        roster: CrewRoster,
        order: CrewOrder.Reassign,
        catalog: UpgradeCatalog,
        unchanged: CrewAssignmentResult,
    ): CrewAssignmentResult {
        val member = roster.member(order.crewId) ?: return unchanged
        val source = member.assignedShipId
        val target = order.toShipId
        if (source == target) return unchanged // already there — no-op
        val targetShip = fleet.ship(target) ?: return unchanged // not an owned ship
        val sourceShip = fleet.ship(source) ?: return unchanged // member's ship not owned (shouldn't happen)
        val targetCapacity = ShipStats.crewCapacity(targetShip.type, targetShip.loadout, catalog)
        if (targetShip.crew >= targetCapacity) return unchanged // target has no free berth

        val newFleet =
            fleet
                .withShip(sourceShip.withCrew(sourceShip.crew - 1, catalog))
                .withShip(targetShip.withCrew(targetShip.crew + 1, catalog))
        val newRoster = roster.withUpdated(member.copy(assignedShipId = target))
        return CrewAssignmentResult(newFleet, newRoster, true)
    }
}
