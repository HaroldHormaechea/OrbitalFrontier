package com.orbitalfrontier.crew

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [CrewRoster] (UC50 AC#1) — the save-wide *who* overlaid on the per-ship crew COUNT.
 *
 * Pins the sorted/unique-id invariant, the pure mutators, the deterministic name synthesizer, and — the
 * load-bearing one — [CrewRoster.reconciledToCounts], the migration/load path that makes
 * `forShip(s).size == s.crew` hold by synthesizing generic members up to each ship's count and dropping
 * surplus / orphaned rows.
 */
class CrewRosterTest {
    private fun crewedShip(
        id: Long,
        crew: Int,
    ): OwnedShip = OwnedShip.fresh(ShipId(id), ShipRoster.STARTER, Vec2(0f, 0f)).withCrew(crew)

    private fun fleetOf(vararg ships: OwnedShip): Fleet = Fleet(ships.sortedBy { it.id.value }, ships.first().id)

    @Test
    fun `EMPTY has no members`() {
        assertTrue(CrewRoster.EMPTY.members.isEmpty())
    }

    @Test
    fun `the roster rejects duplicate ids and unsorted members`() {
        assertThrows(IllegalArgumentException::class.java) {
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "A", CrewRole.DECKHAND, ShipId(0)),
                    CrewMember(CrewId(0), "B", CrewRole.DECKHAND, ShipId(0)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrewRoster(
                listOf(
                    CrewMember(CrewId(1), "A", CrewRole.DECKHAND, ShipId(0)),
                    CrewMember(CrewId(0), "B", CrewRole.DECKHAND, ShipId(0)),
                ),
            )
        }
    }

    @Test
    fun `forShip returns only the members on that ship, in id order`() {
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "A", CrewRole.DECKHAND, ShipId(0)),
                    CrewMember(CrewId(1), "B", CrewRole.DECKHAND, ShipId(1)),
                    CrewMember(CrewId(2), "C", CrewRole.DECKHAND, ShipId(0)),
                ),
            )
        assertEquals(listOf(CrewId(0), CrewId(2)), roster.forShip(ShipId(0)).map { it.id })
        assertEquals(listOf(CrewId(1)), roster.forShip(ShipId(1)).map { it.id })
        assertTrue(roster.forShip(ShipId(9)).isEmpty())
    }

    @Test
    fun `member looks up by id, nextCrewId is one past the max`() {
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "A", CrewRole.DECKHAND, ShipId(0)),
                    CrewMember(CrewId(3), "B", CrewRole.DECKHAND, ShipId(0)),
                ),
            )
        assertEquals("B", roster.member(CrewId(3))!!.name)
        assertNull(roster.member(CrewId(9)))
        assertEquals(CrewId(4), roster.nextCrewId())
        assertEquals("an empty roster allocates id 0 first", CrewId(0), CrewRoster.EMPTY.nextCrewId())
    }

    @Test
    fun `withMember keeps the list sorted and rejects a duplicate id`() {
        val roster = CrewRoster.EMPTY.withMember(CrewMember(CrewId(2), "B", CrewRole.DECKHAND, ShipId(0)))
        val grown = roster.withMember(CrewMember(CrewId(0), "A", CrewRole.DECKHAND, ShipId(0)))
        assertEquals(listOf(CrewId(0), CrewId(2)), grown.members.map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            grown.withMember(CrewMember(CrewId(0), "dup", CrewRole.DECKHAND, ShipId(0)))
        }
    }

    @Test
    fun `withUpdated replaces by id without reordering and rejects an absent id`() {
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "A", CrewRole.DECKHAND, ShipId(0)),
                    CrewMember(CrewId(1), "B", CrewRole.DECKHAND, ShipId(0)),
                ),
            )
        val updated = roster.withUpdated(CrewMember(CrewId(0), "A", CrewRole.PILOT, ShipId(0)))
        assertEquals(CrewRole.PILOT, updated.member(CrewId(0))!!.role)
        assertEquals("order is unchanged", listOf(CrewId(0), CrewId(1)), updated.members.map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            roster.withUpdated(CrewMember(CrewId(9), "X", CrewRole.DECKHAND, ShipId(0)))
        }
    }

    @Test
    fun `synthesizedName is deterministic from the id`() {
        assertEquals("Crewmember 0", CrewRoster.synthesizedName(CrewId(0)))
        assertEquals("Crewmember 5", CrewRoster.synthesizedName(CrewId(5)))
    }

    @Test
    fun `hiredOnto adds one default-role member named generically onto the ship`() {
        val roster = CrewRoster.EMPTY.hiredOnto(ShipId(1))
        assertEquals(1, roster.members.size)
        val hired = roster.members.single()
        assertEquals(CrewId(0), hired.id)
        assertEquals("Crewmember 0", hired.name)
        assertEquals(CrewRole.DEFAULT, hired.role)
        assertEquals(ShipId(1), hired.assignedShipId)
    }

    // --- reconciledToCounts: the load/migration path that enforces forShip(s).size == s.crew ---

    @Test
    fun `reconcile synthesizes generic members up to each ship's count when the roster is empty`() {
        val fleet = fleetOf(crewedShip(0, 2), crewedShip(1, 1))
        val reconciled = CrewRoster.EMPTY.reconciledToCounts(fleet)
        assertEquals("3 members synthesized in total", 3, reconciled.members.size)
        assertEquals(2, reconciled.forShip(ShipId(0)).size)
        assertEquals(1, reconciled.forShip(ShipId(1)).size)
        assertTrue("all synthesized members take the default role", reconciled.members.all { it.role == CrewRole.DECKHAND })
        assertEquals("forShip(s).size == s.crew holds for every ship", listOf(2, 1), fleet.ships.map { it.crew })
    }

    @Test
    fun `reconcile keeps existing members and only tops up the shortfall`() {
        val fleet = fleetOf(crewedShip(0, 2))
        val roster = CrewRoster(listOf(CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0))))
        val reconciled = roster.reconciledToCounts(fleet)
        assertEquals(2, reconciled.forShip(ShipId(0)).size)
        assertEquals("the named existing member is kept", "Ada", reconciled.member(CrewId(0))!!.name)
        assertEquals("Ada keeps her role", CrewRole.PILOT, reconciled.member(CrewId(0))!!.role)
    }

    @Test
    fun `reconcile drops surplus members above the ship's count`() {
        val fleet = fleetOf(crewedShip(0, 1))
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0)),
                    CrewMember(CrewId(1), "Ben", CrewRole.GUNNER, ShipId(0)),
                ),
            )
        val reconciled = roster.reconciledToCounts(fleet)
        assertEquals("only the first (lowest-id) member is kept", 1, reconciled.forShip(ShipId(0)).size)
        assertEquals("Ada", reconciled.member(CrewId(0))!!.name)
        assertNull("the surplus member is dropped", reconciled.member(CrewId(1)))
    }

    @Test
    fun `reconcile drops members assigned to a ship the fleet no longer owns`() {
        val fleet = fleetOf(crewedShip(0, 1))
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0)),
                    CrewMember(CrewId(1), "Orphan", CrewRole.GUNNER, ShipId(9)),
                ),
            )
        val reconciled = roster.reconciledToCounts(fleet)
        assertEquals(1, reconciled.members.size)
        assertNull("the orphaned member is dropped", reconciled.member(CrewId(1)))
    }

    @Test
    fun `reconcile is deterministic — identical inputs yield the identical roster`() {
        val fleet = fleetOf(crewedShip(0, 2), crewedShip(1, 2))
        assertEquals(CrewRoster.EMPTY.reconciledToCounts(fleet), CrewRoster.EMPTY.reconciledToCounts(fleet))
    }
}
