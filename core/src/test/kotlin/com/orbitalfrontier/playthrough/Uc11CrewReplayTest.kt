package com.orbitalfrontier.playthrough

import com.orbitalfrontier.crew.Hiring
import com.orbitalfrontier.crew.TurretOperability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC11 crew playthrough (AC#6), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc11-crew` artifact starts the ship **docked at Alpha Station** (the one crew-hiring
 * station) with a single starter ship (crew=0, base crew capacity 2) and a known wallet, then hires
 * one crew at tick 0. This test replays it headlessly and asserts the AC#6 contract:
 *  - the crew count rises **0 → 1** (a crew is actually hired);
 *  - the **turret-operability flag flips** from inoperable (crew 0) to operable (crew 1) at the MVP
 *    turret crew requirement of 1 — the load-bearing AC#6 assertion;
 *  - the hire's cost is deducted from the wallet exactly once;
 *  - the crew count persists across the trailing held ticks (the docked freeze covers crew);
 *  - the replay is bit-for-bit deterministic across two runs.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc11Crew] and guarded by [PlaythroughFixtureTest].
 */
class Uc11CrewReplayTest {
    private fun loadCrew(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC11_CREW)

    /** Turret operability derived from a snapshot's active-ship crew at the MVP requirement (UC11 AC#3). */
    private fun turretsOperable(state: com.orbitalfrontier.sim.SimulationState): Boolean =
        TurretOperability.turretsOperable(state.fleet.active.crew)

    @Test
    fun `hiring crew raises the count and flips turret operability to operable`() {
        val perTick = ReplayRunner().run(loadCrew(), capturePerTickStates = true).perTickStates

        // Before any step runs: the docked starter ship is uncrewed and its turret is inoperable.
        val initial = perTick.first()
        assertEquals("precondition: the ship starts uncrewed", 0, initial.fleet.active.crew)
        assertFalse("precondition: an uncrewed ship's turret is inoperable", turretsOperable(initial))

        // After the tick-0 hire (snapshot index 1): crew is 1 and the turret is now operable (AC#6).
        val afterHire = perTick[1]
        assertEquals("the hire raises the crew count to 1", 1, afterHire.fleet.active.crew)
        assertTrue("the first hire flips the turret to operable (AC#6)", turretsOperable(afterHire))
    }

    @Test
    fun `the hire deducts exactly one crew's cost from the wallet`() {
        val finalState = ReplayRunner().run(loadCrew()).finalState

        assertEquals("crew ends at 1", 1, finalState.fleet.active.crew)
        assertEquals(
            "the wallet drops by exactly one hire's cost",
            PlaythroughFixtures.UC11_STARTING_CREDITS - Hiring.HIRE_COST_PER_CREW,
            finalState.credits,
        )
    }

    @Test
    fun `the hired crew persists across the trailing held ticks`() {
        val perTick = ReplayRunner().run(loadCrew(), capturePerTickStates = true).perTickStates

        // From the hire (index 1) onward the crew count stays at 1 — docked + no hire ⇒ it stays put.
        for (index in 1 until perTick.size) {
            assertEquals("crew stays hired at snapshot $index", 1, perTick[index].fleet.active.crew)
            assertTrue("the turret stays operable at snapshot $index", turretsOperable(perTick[index]))
        }
    }

    @Test
    fun `replay through hiring is deterministic`() {
        val first = ReplayRunner().run(loadCrew()).finalState
        val second = ReplayRunner().run(loadCrew()).finalState

        // SimulationState data-class equality covers the fleet (and thus the active ship's crew) + credits.
        assertEquals(first, second)
    }
}
