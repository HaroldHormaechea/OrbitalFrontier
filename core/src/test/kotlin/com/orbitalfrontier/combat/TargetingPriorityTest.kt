package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TargetingPriority] (UC13 AC#2/#7) — the auto-aim turret's **total order**: ascending
 * distance² from the firing point, then ascending [HostileId] as a deterministic tie-break.
 *
 * The tie-break is what keeps targeting byte-stable across a replay (never `hashCode`/identity/list
 * position), so the equidistant-hostiles case is the load-bearing assertion here.
 */
class TargetingPriorityTest {
    private fun hostile(
        id: Long,
        position: Vec2,
    ): Hostile = Hostile(id = HostileId(id), archetypeId = HostileArchetypes.RAIDER.id, kinematics = ShipKinematics(position = position))

    @Test
    fun `selectTarget picks the nearest hostile by distance squared`() {
        val from = Vec2(0f, 0f)
        val near = hostile(7, Vec2(100f, 0f))
        val far = hostile(2, Vec2(0f, 300f))

        // Order in the list is irrelevant — distance decides (the far one has the LOWER id, proving id is
        // only a tie-break, not the primary key).
        assertEquals(near.id, TargetingPriority.selectTarget(from, listOf(far, near))?.id)
        assertEquals(near.id, TargetingPriority.selectTarget(from, listOf(near, far))?.id)
    }

    @Test
    fun `an exact distance tie is broken by the lower HostileId`() {
        val from = Vec2(0f, 0f)
        // Two hostiles equidistant (both 200 from origin), presented high-id-first.
        val high = hostile(9, Vec2(200f, 0f))
        val low = hostile(3, Vec2(0f, 200f))

        assertEquals("the lower HostileId wins an equidistant tie", low.id, TargetingPriority.selectTarget(from, listOf(high, low))?.id)
        // List order must not change the answer (determinism).
        assertEquals("list order does not change the tie-break", low.id, TargetingPriority.selectTarget(from, listOf(low, high))?.id)
    }

    @Test
    fun `selectTarget returns null when there are no hostiles`() {
        assertNull(TargetingPriority.selectTarget(Vec2(0f, 0f), emptyList()))
    }

    @Test
    fun `the comparator implements the same total order`() {
        val from = Vec2(0f, 0f)
        val a = hostile(1, Vec2(300f, 0f))
        val b = hostile(2, Vec2(100f, 0f))
        val c = hostile(3, Vec2(100f, 0f)) // equidistant with b, higher id

        val sorted = listOf(a, c, b).sortedWith(TargetingPriority.comparator(from))
        // Nearest first; b before c on the equidistant tie (lower id), a last (farthest).
        assertEquals(listOf(b.id, c.id, a.id), sorted.map { it.id })
    }

    // --- UC45 AC#1 pitfall: a MULTI-hostile pack must still resolve to one stable total order (auto-aim) ---

    @Test
    fun `a multi-hostile pack resolves to a single deterministic total order`() {
        val from = Vec2(0f, 0f)
        // Five hostiles incl. an equidistant pair (200 from origin) presented in a jumbled order with ids
        // deliberately NOT matching distance order, so only the distance²-then-id total order yields a stable
        // result (never list position / hashCode / identity).
        val nearest = hostile(8, Vec2(50f, 0f)) // d² = 2500
        val tieHighId = hostile(9, Vec2(200f, 0f)) // d² = 40000
        val tieLowId = hostile(4, Vec2(0f, 200f)) // d² = 40000 (ties with tieHighId; lower id wins)
        val mid = hostile(1, Vec2(0f, 300f)) // d² = 90000
        val farthest = hostile(6, Vec2(500f, 0f)) // d² = 250000

        val jumbled = listOf(mid, farthest, tieHighId, nearest, tieLowId)

        // selectTarget picks the single nearest (the auto-aim turret's target).
        assertEquals("the nearest hostile is the auto-aim target", nearest.id, TargetingPriority.selectTarget(from, jumbled)?.id)

        // The full comparator order: nearest, then the equidistant pair (lower id first), then mid, then far.
        val order = jumbled.sortedWith(TargetingPriority.comparator(from)).map { it.id }
        assertEquals(listOf(nearest.id, tieLowId.id, tieHighId.id, mid.id, farthest.id), order)

        // Determinism: a different presentation order yields the SAME total order.
        val reshuffled = listOf(tieLowId, nearest, farthest, tieHighId, mid).sortedWith(TargetingPriority.comparator(from)).map { it.id }
        assertEquals("list order never changes the total order", order, reshuffled)
    }
}
