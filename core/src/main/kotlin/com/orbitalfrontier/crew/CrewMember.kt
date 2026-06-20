package com.orbitalfrontier.crew

import com.orbitalfrontier.ship.ShipId

/**
 * Stable identity of one crew member within the save-wide [CrewRoster] (UC50 AC#1). A value class over
 * a [Long] so a member is keyed independently of its position in the (sorted) roster list and the
 * `crew_member.crew_id` column addresses it directly. Ids are per-save (the roster allocates max+1) and
 * never reused, giving the roster a stable total order.
 */
@JvmInline
value class CrewId(val value: Long)

/**
 * The broad shipboard job a [CrewMember] is signed on for (UC50 AC#1). A closed `enum`
 * (coding-guidelines § O) so a future role plugs in a new constant rather than editing a central `when`.
 *
 * **Inert metadata in the MVP.** A role is a label the fleet/crew screen shows and the player can change;
 * it affects **no** deterministic computation today — turret-operability and wages both key on the crew
 * **count** only (see [com.orbitalfrontier.crew.TurretOperability] / [Wages]). Crew skills that affect
 * systems are STRETCH and deferred (ADR 0038); do not wire role into any resolver here.
 */
enum class CrewRole {
    /** Flies the ship. */
    PILOT,

    /** Mans the guns. */
    GUNNER,

    /** Tends the reactor / engines. */
    ENGINEER,

    /** General hand — the default a synthesized or unknown-role member degrades to. */
    DECKHAND,

    ;

    companion object {
        /** The role a freshly-synthesized member (migration-load) or an unknown persisted slug degrades to. */
        val DEFAULT: CrewRole = DECKHAND
    }
}

/**
 * One identified crew member aboard one ship (UC50 AC#1) — a name, a [role], and the [assignedShipId]
 * of the [com.orbitalfrontier.ship.OwnedShip] they serve on. A pure, immutable value (no engine types)
 * so the roster model is fully JVM-testable.
 *
 * **Identity overlay, not a second source of truth.** The authoritative *count* of crew aboard a ship
 * stays [com.orbitalfrontier.ship.OwnedShip.crew] (the deterministic number turret-operability and wages
 * read); a [CrewMember] is the *who* layered over that count. The invariant the resolvers maintain is
 * `roster.forShip(s).size == s.crew` (UC50 — see [CrewRoster]); identities live ONLY on
 * [com.orbitalfrontier.world.WorldState.crewRoster], never on the ship / simulation snapshot, so they
 * add no bytes to the deterministic record/replay artifacts.
 */
data class CrewMember(
    val id: CrewId,
    val name: String,
    val role: CrewRole,
    val assignedShipId: ShipId,
)
