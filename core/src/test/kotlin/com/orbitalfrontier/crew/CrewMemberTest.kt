package com.orbitalfrontier.crew

import com.orbitalfrontier.ship.ShipId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit coverage for the crew-identity value types (UC50 AC#1) — [CrewId], [CrewRole] and [CrewMember].
 *
 * These are pure, immutable values (no engine types), so they are fully JVM-testable. The crew identity
 * is an overlay on the per-ship crew COUNT; a role is inert metadata in the MVP (it gates no resolver),
 * so these tests pin the shape and the defaults, not any system effect.
 */
class CrewMemberTest {
    @Test
    fun `CrewId is a value over its Long and compares by value`() {
        assertEquals(CrewId(7L), CrewId(7L))
        assertNotEquals(CrewId(7L), CrewId(8L))
        assertEquals(7L, CrewId(7L).value)
    }

    @Test
    fun `the default role a synthesized or unknown-slug member degrades to is DECKHAND`() {
        assertEquals(CrewRole.DECKHAND, CrewRole.DEFAULT)
    }

    @Test
    fun `every authored role is present in the enum`() {
        assertEquals(
            listOf(CrewRole.PILOT, CrewRole.GUNNER, CrewRole.ENGINEER, CrewRole.DECKHAND),
            CrewRole.entries.toList(),
        )
    }

    @Test
    fun `a CrewMember carries its id, name, role and assigned ship and compares by value`() {
        val member = CrewMember(CrewId(1L), "Ada", CrewRole.PILOT, ShipId(0L))
        assertEquals(CrewId(1L), member.id)
        assertEquals("Ada", member.name)
        assertEquals(CrewRole.PILOT, member.role)
        assertEquals(ShipId(0L), member.assignedShipId)
        assertEquals(member, CrewMember(CrewId(1L), "Ada", CrewRole.PILOT, ShipId(0L)))
    }

    @Test
    fun `copy changes one facet while keeping value equality semantics`() {
        val member = CrewMember(CrewId(1L), "Ada", CrewRole.PILOT, ShipId(0L))
        assertEquals(CrewRole.GUNNER, member.copy(role = CrewRole.GUNNER).role)
        assertEquals(ShipId(1L), member.copy(assignedShipId = ShipId(1L)).assignedShipId)
        // The id is the stable identity — copy with the same id keeps the members equal apart from the changed facet.
        assertNotEquals(member, member.copy(role = CrewRole.GUNNER))
    }
}
