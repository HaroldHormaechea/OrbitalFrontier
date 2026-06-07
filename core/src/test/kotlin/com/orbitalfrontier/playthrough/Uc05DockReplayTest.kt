package com.orbitalfrontier.playthrough

import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC05 docking playthrough (AC#6), following the record→persist→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc05-dock` artifact thrusts the ship north into Alpha Station's dock circle and
 * issues an explicit dock action. This test replays it headlessly and asserts:
 *  - the run ends **docked at the right station** (`alpha-station`) in the start sector (AC#6);
 *  - once docked, the ship is **frozen** — every post-dock snapshot holds the exact same position
 *    and stays docked (the dock state both engages and freezes movement, no bounce);
 *  - the replay is bit-for-bit deterministic across two runs.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc05Dock] and guarded by [PlaythroughFixtureTest].
 */
class Uc05DockReplayTest {
    private val alphaStation = PoiId("alpha-station")

    private fun loadDock(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC05_DOCK)

    @Test
    fun `flying into a station's dock range and docking ends docked at that station`() {
        val result = ReplayRunner().run(loadDock())

        // AC#6: the recorded run ends docked at the right station, in the start sector (never jumped).
        assertEquals(alphaStation, result.finalState.dockedStation)
        assertEquals(MvpSectorMap.START_SECTOR, result.finalState.currentSector)
    }

    @Test
    fun `once docked the ship is frozen — position is stable and it stays docked`() {
        val states = ReplayRunner().run(loadDock(), capturePerTickStates = true).perTickStates

        val dockIndex = states.indexOfFirst { it.dockedStation != null }
        assertTrue("expected the ship to dock during the replay", dockIndex > 0)

        // From the dock tick onward, position is bit-for-bit unchanged and the ship stays docked:
        // the frozen branch only advances the tick (no drift, no re-trigger / bounce).
        val dockedPosition = states[dockIndex].ship.position
        for (state in states.drop(dockIndex)) {
            assertEquals("docked ship must remain docked at the same station", alphaStation, state.dockedStation)
            assertEquals("docked ship position must be frozen", dockedPosition, state.ship.position)
        }
        // Sanity: there are genuinely several held ticks after docking (the freeze is exercised).
        assertTrue("expected held ticks after docking to prove the freeze", states.size - dockIndex >= 3)
    }

    @Test
    fun `replay through a dock is deterministic`() {
        val first = ReplayRunner().run(loadDock())
        val second = ReplayRunner().run(loadDock())

        SnapshotAssertions.assertStatesExactlyEqual(first.finalState, second.finalState)
        assertEquals(alphaStation, first.finalState.dockedStation)
    }
}
