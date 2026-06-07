package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure gate-traversal resolver (UC03 AC#8; ADR 0004).
 *
 * Exercises the three behaviours that make jumps safe and deterministic:
 *  - the trigger circle is hit/missed by distance from the gate;
 *  - the arrival point is placed **outside** the destination gate's trigger circle; and
 *  - the tick immediately after a jump does **not** re-trigger a jump back (anti-bounce-back).
 *
 * Geometry is read from the production [MvpSectorMap] so the tests track the real map.
 */
class GateTraversalTest {
    private val world = MvpSectorMap.build()
    private val alpha = SectorId("alpha")
    private val beta = SectorId("beta")

    /** The gate the ship flies into, and the gate it should arrive at on the far side. */
    private val alphaToBeta = world.sector(alpha).gate(PoiId("alpha-to-beta"))!!
    private val betaToAlpha = world.sector(beta).gate(PoiId("beta-to-alpha"))!!

    @Test
    fun `a ship inside a gate trigger circle resolves a jump to the linked gate's sector`() {
        // Dead-centre on the gate is unambiguously inside the trigger circle.
        val traversal = GateTraversal.resolve(world, alpha, alphaToBeta.position)

        assertNotNull("expected a jump when on top of the gate", traversal)
        assertEquals(beta, traversal!!.destinationSector)
    }

    @Test
    fun `a ship just inside the trigger radius still jumps (boundary is inclusive)`() {
        // Exactly on the trigger ring: distance == triggerRadius, which resolve treats as a hit.
        val onTheRing = alphaToBeta.position + Vec2(alphaToBeta.triggerRadius, 0f)
        assertEquals(alphaToBeta.triggerRadius, (onTheRing - alphaToBeta.position).length, 1e-3f)

        assertNotNull("on-ring distance must count as inside the trigger", GateTraversal.resolve(world, alpha, onTheRing))
    }

    @Test
    fun `a ship outside every gate trigger circle resolves no jump`() {
        // The sector centre (origin) is far from any edge gate ⇒ no trigger.
        assertNull("centre of the sector must not trigger a jump", GateTraversal.resolve(world, alpha, Vec2.ZERO))

        // A point just beyond the trigger ring also misses.
        val justOutside = alphaToBeta.position + Vec2(alphaToBeta.triggerRadius + 1f, 0f)
        assertNull("just outside the trigger ring must not jump", GateTraversal.resolve(world, alpha, justOutside))
    }

    @Test
    fun `the arrival point is offset outside the destination gate's trigger circle`() {
        val traversal = GateTraversal.resolve(world, alpha, alphaToBeta.position)!!

        val distanceFromDestGate = (traversal.arrivalPosition - betaToAlpha.position).length
        assertTrue(
            "arrival ($distanceFromDestGate) must land outside the dest gate trigger (${betaToAlpha.triggerRadius})",
            distanceFromDestGate > betaToAlpha.triggerRadius,
        )
    }

    @Test
    fun `the tick after a jump does not re-trigger a jump back (anti-bounce-back)`() {
        val traversal = GateTraversal.resolve(world, alpha, alphaToBeta.position)!!

        // Standing at the arrival point in the destination sector, the next resolve must be null:
        // the ship spawned clear of the gate's trigger circle, so it cannot bounce straight back.
        val reTrigger = GateTraversal.resolve(world, traversal.destinationSector, traversal.arrivalPosition)
        assertNull("ship must arrive clear of the destination gate's trigger circle", reTrigger)
    }

    @Test
    fun `resolution is a pure function — identical inputs yield an identical traversal`() {
        val a = GateTraversal.resolve(world, alpha, alphaToBeta.position)
        val b = GateTraversal.resolve(world, alpha, alphaToBeta.position)
        assertEquals(a, b)
    }
}
