package com.orbitalfrontier.crew

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [CrewAssignment] (UC50 AC#3) — the pure resolver that reassigns a crew member to
 * another owned ship (moving COUNT and identity together) or changes a member's role.
 *
 * The two-facets-in-sync property is the load-bearing one: a reassignment must keep
 * `roster.forShip(s).size == s.crew` for both ships. Turret-operability is asserted to still derive from
 * the resulting crew COUNT (AC#1 no-regression), since identities/roles gate no resolver in the MVP.
 */
class CrewAssignmentTest {
    private fun ship(
        id: Long,
        type: com.orbitalfrontier.ship.ShipType,
        crew: Int,
    ): OwnedShip = OwnedShip.fresh(ShipId(id), type, Vec2(0f, 0f)).withCrew(crew)

    /** Starter (cap 2) id 0 with [crew0], Swift (cap 2) id 1 with [crew1]; starter active. */
    private fun fleet(
        crew0: Int,
        crew1: Int,
    ): Fleet =
        Fleet(
            listOf(ship(0, ShipRoster.STARTER, crew0), ship(1, ShipRoster.SWIFT, crew1)),
            ShipId(0),
        )

    @Test
    fun `None is a no-op that returns its inputs unchanged`() {
        val f = fleet(1, 0)
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0))))
        val result = CrewAssignment.resolve(f, roster, CrewOrder.None)
        assertFalse(result.changed)
        assertSame(f, result.fleet)
        assertSame(roster, result.roster)
    }

    @Test
    fun `Reassign moves the count and the identity together`() {
        val f = fleet(1, 0)
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0))))
        val result = CrewAssignment.resolve(f, roster, CrewOrder.Reassign(CrewId(0), ShipId(1)))

        assertTrue(result.changed)
        assertEquals("source ship loses a crew", 0, result.fleet.ship(ShipId(0))!!.crew)
        assertEquals("target ship gains a crew", 1, result.fleet.ship(ShipId(1))!!.crew)
        assertEquals("the member's assigned ship moved", ShipId(1), result.roster.member(CrewId(0))!!.assignedShipId)
        assertEquals("the role is preserved across the move", CrewRole.PILOT, result.roster.member(CrewId(0))!!.role)
        // Invariant: forShip(s).size == s.crew on both ships after the move.
        assertEquals(0, result.roster.forShip(ShipId(0)).size)
        assertEquals(1, result.roster.forShip(ShipId(1)).size)
    }

    @Test
    fun `Reassign to a ship at full crew capacity is a no-op`() {
        val f = fleet(1, 2) // target Swift already at its capacity of 2
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0))))
        val result = CrewAssignment.resolve(f, roster, CrewOrder.Reassign(CrewId(0), ShipId(1)))
        assertFalse("a full target rejects the reassignment", result.changed)
        assertSame(f, result.fleet)
        assertSame(roster, result.roster)
    }

    @Test
    fun `Reassign of an unknown member, to an unowned ship, or to the same ship is a no-op`() {
        val f = fleet(1, 0)
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0))))

        assertFalse("unknown member", CrewAssignment.resolve(f, roster, CrewOrder.Reassign(CrewId(9), ShipId(1))).changed)
        assertFalse("unowned target", CrewAssignment.resolve(f, roster, CrewOrder.Reassign(CrewId(0), ShipId(9))).changed)
        assertFalse("same ship", CrewAssignment.resolve(f, roster, CrewOrder.Reassign(CrewId(0), ShipId(0))).changed)
    }

    @Test
    fun `ChangeRole updates only the role and only when it differs`() {
        val f = fleet(1, 0)
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0))))

        val changed = CrewAssignment.resolve(f, roster, CrewOrder.ChangeRole(CrewId(0), CrewRole.ENGINEER))
        assertTrue(changed.changed)
        assertEquals(CrewRole.ENGINEER, changed.roster.member(CrewId(0))!!.role)
        assertSame("a role change never touches the fleet", f, changed.fleet)

        val same = CrewAssignment.resolve(f, roster, CrewOrder.ChangeRole(CrewId(0), CrewRole.PILOT))
        assertFalse("setting the same role is a no-op", same.changed)
        assertSame(roster, same.roster)

        assertFalse("unknown member", CrewAssignment.resolve(f, roster, CrewOrder.ChangeRole(CrewId(9), CrewRole.GUNNER)).changed)
    }

    @Test
    fun `turret-operability still derives from the resulting crew count after a reassignment (AC#1 no-regression)`() {
        // Source starter has 1 crew → its turret is operable; moving that crew off drops it to 0 → inoperable.
        val f = fleet(1, 0)
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.GUNNER, ShipId(0))))
        assertTrue("before: 1 crew operates the turret", TurretOperability.turretsOperable(f.ship(ShipId(0))!!.crew))

        val result = CrewAssignment.resolve(f, roster, CrewOrder.Reassign(CrewId(0), ShipId(1)))
        assertFalse(
            "after: the source ship at 0 crew can no longer operate its turret",
            TurretOperability.turretsOperable(result.fleet.ship(ShipId(0))!!.crew),
        )
        assertTrue(
            "after: the target ship now at 1 crew can operate its turret",
            TurretOperability.turretsOperable(result.fleet.ship(ShipId(1))!!.crew),
        )
    }
}
