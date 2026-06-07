package com.orbitalfrontier.playthrough

import com.orbitalfrontier.ship.ShipMovementParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * JVM unit tests for [ReplayRunner] replaying the committed `uc01-thrust-north` artifact headlessly
 * (UC02 AC#5/#6/#7).
 *
 * The scenario: thrust the stick "north" (+y) for 60 ticks from rest at the origin, then coast for
 * 30 ticks. The ship rotates from its initial east-facing heading toward north and accelerates,
 * then drifts. Assertions are qualitative-but-physical and use tolerance ([SnapshotAssertions]),
 * because float math is deterministic yet not exact — exact reproducibility is covered separately by
 * [DeterminismGuardTest].
 */
class ReplayRunnerTest {
    private val params = ShipMovementParams()

    private fun loadUc01(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC01_THRUST_NORTH)

    @Test
    fun `replay steps the full tick span and ends on the final tick`() {
        val playthrough = loadUc01()

        val result = ReplayRunner().run(playthrough)

        assertEquals(playthrough.tickCount, result.finalState.tick)
    }

    @Test
    fun `per-tick capture yields one snapshot per tick plus the initial state`() {
        val playthrough = loadUc01()

        val result = ReplayRunner().run(playthrough, capturePerTickStates = true)

        // Index 0 is the initial state, index k is the state after k steps ⇒ size tickCount + 1.
        assertEquals(playthrough.tickCount + 1, result.perTickStates.size)
        assertEquals(0, result.perTickStates.first().tick)
        assertEquals(playthrough.tickCount, result.perTickStates.last().tick)
        assertEquals(result.finalState, result.perTickStates.last())
    }

    @Test
    fun `default run does not capture per-tick states`() {
        val result = ReplayRunner().run(loadUc01())

        assertTrue(result.perTickStates.isEmpty())
    }

    @Test
    fun `thrusting north turns the ship to face north and moves it north`() {
        val result = ReplayRunner().run(loadUc01())
        val ship = result.finalState.ship

        // Hull rotated from east (0) toward the north stick target (+y ⇒ +PI/2).
        SnapshotAssertions.assertWithin("heading should settle facing north", (PI / 2).toFloat(), ship.headingRadians, 0.05f)
        // Net travel and motion are predominantly north (+y dominates +x).
        assertTrue("ship should have moved north (y>0): $ship", ship.position.y > 0f)
        assertTrue("velocity should point north (vy>0): $ship", ship.velocity.y > 0f)
        assertTrue("northward motion should dominate: $ship", ship.velocity.y > abs(ship.velocity.x))
        assertTrue("northward displacement should dominate: $ship", ship.position.y > abs(ship.position.x))
    }

    @Test
    fun `final speed respects the top-speed cap`() {
        val ship = ReplayRunner().run(loadUc01()).finalState.ship

        // AC#6: total speed never exceeds maxSpeed (within float tolerance).
        assertTrue("speed ${ship.speed} exceeded cap ${params.maxSpeed}", ship.speed <= params.maxSpeed + 1e-3f)
    }

    @Test
    fun `coasting after release decays the ship speed (drift)`() {
        val states = ReplayRunner().run(loadUc01(), capturePerTickStates = true).perTickStates

        // Thrust covers ticks 0..59 ⇒ index 60 is the end-of-thrust state; index 90 is after the
        // 30 trailing no-input ticks. Drift decay must have reduced the speed.
        val endOfThrust = states[60].ship.speed
        val afterDrift = states[90].ship.speed
        assertTrue("expected drift to reduce speed: thrust=$endOfThrust drift=$afterDrift", afterDrift < endOfThrust)
    }
}
