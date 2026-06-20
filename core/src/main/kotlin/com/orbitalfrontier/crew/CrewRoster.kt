package com.orbitalfrontier.crew

import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.ShipId

/**
 * The save-wide roster of identified crew (UC50 AC#1) — the *who* overlaid on the per-ship crew **count**
 * ([com.orbitalfrontier.ship.OwnedShip.crew], which stays the authoritative deterministic number). Held
 * on [com.orbitalfrontier.world.WorldState.crewRoster] only — never on the ship or the simulation
 * snapshot — so identities add no bytes to the deterministic record/replay artifacts (the zero-regen
 * lever, ADR 0038).
 *
 * [members] is kept **sorted by [CrewId]** with unique ids, so the roster's data-class equality is stable
 * across a save/reload round-trip regardless of insertion order. All mutators are pure and return a new
 * [CrewRoster]. The framing invariant (maintained by the hire / [CrewAssignment] resolvers and reconciled
 * on load via [reconciledToCounts]) is `forShip(s).size == s.crew` for every owned ship.
 *
 * Pure, immutable value (no engine types) so the roster is fully JVM-testable.
 */
data class CrewRoster(
    val members: List<CrewMember> = emptyList(),
) {
    init {
        val ids = members.map { it.id.value }
        require(ids.toSet().size == ids.size) { "CrewRoster member ids must be unique: $ids" }
        require(ids == ids.sorted()) { "CrewRoster members must be sorted by id: $ids" }
    }

    /** The members serving on [shipId], in roster (id) order. Empty when none are assigned there. */
    fun forShip(shipId: ShipId): List<CrewMember> = members.filter { it.assignedShipId == shipId }

    /** The member with [id], or null if the roster does not contain it. */
    fun member(id: CrewId): CrewMember? = members.firstOrNull { it.id == id }

    /** The next free [CrewId] — one past the current maximum (or 0 for an empty roster). */
    fun nextCrewId(): CrewId = CrewId((members.maxOfOrNull { it.id.value } ?: -1L) + 1L)

    /**
     * Add [member] to the roster, keeping the list sorted by id. The hire path uses this to record a new
     * named identity for the crew member just hired onto a ship.
     *
     * @throws IllegalArgumentException if [member]'s id is already present (a programmer error — callers
     *   allocate a fresh id via [nextCrewId]).
     */
    fun withMember(member: CrewMember): CrewRoster {
        require(members.none { it.id == member.id }) { "withMember: ${member.id.value} is already in the roster" }
        return copy(members = (members + member).sortedBy { it.id.value })
    }

    /** Replace whatever member shares [updated]'s id with [updated] (id unchanged, so order is stable). */
    fun withUpdated(updated: CrewMember): CrewRoster {
        require(members.any { it.id == updated.id }) { "withUpdated: ${updated.id.value} is not in the roster" }
        return copy(members = members.map { if (it.id == updated.id) updated else it })
    }

    /**
     * Hire one new crew member named generically and assigned to [shipId] with [CrewRole.DEFAULT]
     * (UC50) — the roster side of a [com.orbitalfrontier.crew.Hiring] resolve. The id and name are
     * synthesized deterministically from the current roster so a hire is reproducible.
     */
    fun hiredOnto(shipId: ShipId): CrewRoster {
        val id = nextCrewId()
        return withMember(CrewMember(id, synthesizedName(id), CrewRole.DEFAULT, shipId))
    }

    /**
     * Reconcile the roster to a [fleet]'s per-ship crew **counts** (UC50) — the load-time / migration path
     * that makes `forShip(s).size == s.crew` hold for every owned ship. For each ship: keep its first
     * `crew` existing members (by id) and **synthesize** generic members up to the count when there are too
     * few; drop any surplus and any member assigned to a ship the fleet no longer owns. Synthesized members
     * get fresh ids (continuing past the max id seen) and [CrewRole.DEFAULT]. Deterministic: identical
     * `(roster, fleet)` always yields the identical reconciled roster, so a migrated save reads back stably.
     */
    fun reconciledToCounts(fleet: Fleet): CrewRoster {
        var nextId = (members.maxOfOrNull { it.id.value } ?: -1L) + 1L
        val reconciled = ArrayList<CrewMember>()
        for (ship in fleet.ships) {
            val existing = forShip(ship.id).sortedBy { it.id.value }
            val keep = ship.crew.coerceAtLeast(0)
            reconciled += existing.take(keep)
            repeat(keep - existing.size) {
                val id = CrewId(nextId++)
                reconciled += CrewMember(id, synthesizedName(id), CrewRole.DEFAULT, ship.id)
            }
        }
        return CrewRoster(reconciled.sortedBy { it.id.value })
    }

    companion object {
        /** The empty roster — a new game and every legacy / migrated pre-UC50 save begin here. */
        val EMPTY: CrewRoster = CrewRoster(emptyList())

        /** Deterministic generic name for a synthesized or freshly-hired member, derived from its [id]. */
        fun synthesizedName(id: CrewId): String = "Crewmember ${id.value}"
    }
}
