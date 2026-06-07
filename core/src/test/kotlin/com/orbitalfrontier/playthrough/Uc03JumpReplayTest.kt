package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC03 jump-gate playthrough (AC#9/#10), following the record→persist→replay→
 * assert pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc03-jump` artifact drives the ship east through Alpha's `alpha-to-beta` gate. This
 * test replays it headlessly and asserts the resulting current-sector and arrival position (AC#9),
 * that momentum carries through the gate, and that the replay is bit-for-bit deterministic across two
 * runs (AC#10). The artifact itself is reproduced from [PlaythroughFixtures.uc03Jump] and guarded by
 * [PlaythroughFixtureTest].
 */
class Uc03JumpReplayTest {
    private val world = MvpSectorMap.build()
    private val beta = SectorId("beta")

    private fun loadJump(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC03_JUMP)

    /** The arrival point GateTraversal places the ship at on the far side, derived from the map. */
    private fun expectedBetaArrival(): Vec2 {
        val betaToAlpha = world.sector(beta).gate(PoiId("beta-to-alpha"))!!
        val toCenter = (Vec2.ZERO - betaToAlpha.position).normalizedOrZero()
        return betaToAlpha.position + toCenter * (betaToAlpha.triggerRadius + GateTraversal.DEFAULT_ARRIVAL_MARGIN)
    }

    @Test
    fun `flying through the alpha gate jumps the ship to beta at the arrival point`() {
        val result = ReplayRunner().run(loadJump(), capturePerTickStates = true)

        // AC#9: the recorded run ends in the destination sector (Beta).
        assertEquals(beta, result.finalState.currentSector)

        // The first tick that reports Beta is the jump tick; at that tick the ship is placed exactly
        // at the computed offset arrival point (AC#9 arrival position).
        val jumpIndex = result.perTickStates.indexOfFirst { it.currentSector == beta }
        assertTrue("expected a jump into beta during the replay", jumpIndex > 0)

        SnapshotAssertions.assertVec2Within(
            "ship should arrive at the beta gate's offset arrival point",
            expectedBetaArrival(),
            result.perTickStates[jumpIndex].ship.position,
        )

        // Anti-bounce-back at replay scope: every post-jump tick stays in Beta (no immediate jump back).
        val staysInBeta = result.perTickStates.drop(jumpIndex).all { it.currentSector == beta }
        assertTrue("ship must not bounce back out of beta after the jump", staysInBeta)
    }

    @Test
    fun `momentum carries through the jump — velocity and heading are preserved`() {
        val states = ReplayRunner().run(loadJump(), capturePerTickStates = true).perTickStates
        val jumpIndex = states.indexOfFirst { it.currentSector == beta }
        assertTrue("expected a jump into beta during the replay", jumpIndex > 0)

        val before = states[jumpIndex - 1].ship
        val after = states[jumpIndex].ship

        SnapshotAssertions.assertVec2Within("velocity preserved through the gate", before.velocity, after.velocity)
        SnapshotAssertions.assertWithin("heading preserved through the gate", before.headingRadians, after.headingRadians)
    }

    @Test
    fun `replay through a jump is deterministic`() {
        // AC#10: identical inputs ⇒ identical sector-traversal outcome, bit-for-bit, across runs.
        val playthrough = loadJump()

        val first = ReplayRunner().run(playthrough)
        val second = ReplayRunner().run(playthrough)

        SnapshotAssertions.assertStatesExactlyEqual(first.finalState, second.finalState)
        assertEquals(beta, first.finalState.currentSector)
    }
}
