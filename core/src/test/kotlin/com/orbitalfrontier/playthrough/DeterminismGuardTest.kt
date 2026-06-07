package com.orbitalfrontier.playthrough

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The determinism guard (UC02 AC#1/#11): replaying the *same* playthrough twice must produce
 * **bit-identical** snapshots — not merely within tolerance.
 *
 * This is the contract every later UC relies on: identical seed + identical input script + identical
 * fixed `dt` ⇒ identical end state, every run, on any conformant JVM. Compared with data-class
 * equality (exact), unlike the tolerance-based assertions in [ReplayRunnerTest].
 */
class DeterminismGuardTest {
    private fun loadUc01(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC01_THRUST_NORTH)

    @Test
    fun `two replays of the same playthrough yield identical final states`() {
        val playthrough = loadUc01()

        val first = ReplayRunner().run(playthrough)
        val second = ReplayRunner().run(playthrough)

        SnapshotAssertions.assertStatesExactlyEqual(first.finalState, second.finalState)
    }

    @Test
    fun `two replays yield identical per-tick snapshot sequences`() {
        val playthrough = loadUc01()

        val first = ReplayRunner().run(playthrough, capturePerTickStates = true)
        val second = ReplayRunner().run(playthrough, capturePerTickStates = true)

        // Whole-sequence equality: every intermediate tick matches exactly, not just the end state.
        assertEquals(first.perTickStates, second.perTickStates)
    }

    @Test
    fun `a fresh runner instance reproduces the same result`() {
        val playthrough = loadUc01()

        // Determinism must not depend on reusing a runner: separate instances, identical output.
        val a = ReplayRunner().run(playthrough)
        val b = ReplayRunner().run(playthrough)

        SnapshotAssertions.assertStatesExactlyEqual(a.finalState, b.finalState)
    }
}
