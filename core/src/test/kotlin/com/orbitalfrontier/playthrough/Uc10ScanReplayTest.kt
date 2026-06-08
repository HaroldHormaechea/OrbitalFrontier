package com.orbitalfrontier.playthrough

import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC10 scanning playthrough (AC#6), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc10-scan` artifact starts the ship in flight at the sector-centre
 * [com.orbitalfrontier.world.MvpSectorMap.SCAN_POINT] and runs a single active scan. This test
 * replays it headlessly and asserts the AC#6 contract:
 *  - the in-range hidden contact (`alpha-derelict`, d=300 < base range 500) **becomes known**;
 *  - a contact outside range (`alpha-ghost`, d=800) **stays hidden**;
 *  - the mid-range `alpha-smuggler` (d=600) also stays hidden — the base (un-upgraded) scan does not
 *    reach it, which is precisely the sensors-upgrade payoff UC10 AC#3 reserves for a SCANNER_I fit;
 *  - the revealed set persists across the trailing held ticks (monotonic — it does not re-hide, AC#4);
 *  - the replay is bit-for-bit deterministic across two runs.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc10Scan] and guarded by [PlaythroughFixtureTest].
 */
class Uc10ScanReplayTest {
    private val derelict = PoiId("alpha-derelict")
    private val smuggler = PoiId("alpha-smuggler")
    private val ghost = PoiId("alpha-ghost")

    private fun loadScan(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC10_SCAN)

    @Test
    fun `scanning near a hidden contact reveals it while an out-of-range contact stays hidden`() {
        val state = ReplayRunner().run(loadScan()).finalState

        // AC#6: the in-range derelict becomes known.
        assertTrue("the in-range derelict must become known after the scan", derelict in state.revealedContacts)

        // AC#6: the out-of-range ghost stays hidden.
        assertFalse("the out-of-range ghost must stay hidden", ghost in state.revealedContacts)

        // AC#3: the base (un-upgraded) scan does not reach the mid-range smuggler.
        assertFalse("the mid-range smuggler is beyond the base sensor range", smuggler in state.revealedContacts)

        // Exactly one contact was revealed by the base scan.
        assertEquals("only the derelict is revealed by the base scan", setOf(derelict), state.revealedContacts)
    }

    @Test
    fun `the revealed contact persists across the trailing held ticks`() {
        // The scan happens at tick 0; capturing per-tick snapshots shows the derelict revealed from the
        // step that ran the scan onward and never dropping (monotonic, AC#4).
        val perTick = ReplayRunner().run(loadScan(), capturePerTickStates = true).perTickStates

        assertFalse("nothing is revealed before any step runs", derelict in perTick.first().revealedContacts)
        // Index k is the state after k steps; after the tick-0 scan (index 1) the derelict is revealed
        // and stays revealed through the final snapshot.
        for (index in 1 until perTick.size) {
            assertTrue(
                "the derelict stays revealed at snapshot $index (monotonic)",
                derelict in perTick[index].revealedContacts,
            )
        }
    }

    @Test
    fun `replay through scanning is deterministic`() {
        val first = ReplayRunner().run(loadScan()).finalState
        val second = ReplayRunner().run(loadScan()).finalState

        // SimulationState data-class equality covers revealedContacts as well as kinematics.
        assertEquals(first, second)
    }
}
