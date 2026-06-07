package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.sim.SimulationState
import org.junit.Assert.assertEquals

/**
 * Tolerance-aware snapshot assertion helpers for replay tests (UC02 AC#6).
 *
 * The pure model is deterministic but float math is not *exact* across algebraically-equal
 * expressions, so feature replays assert resulting state **within a tolerance** rather than on an
 * exact literal. This object is the one place those helpers live, so every later UC's replay test
 * asserts the same way (see docs/PLAYTESTING.md). For the determinism guard — where two replays of
 * the *same* playthrough must be bit-identical — use [assertStatesExactlyEqual], which compares with
 * data-class equality and no tolerance.
 */
object SnapshotAssertions {
    /** Default absolute tolerance for kinematic comparisons (world-units / rad). */
    const val DEFAULT_TOLERANCE: Float = 1e-3f

    /** Assert [actual] equals [expected] within [tolerance], with a descriptive [message]. */
    fun assertWithin(
        message: String,
        expected: Float,
        actual: Float,
        tolerance: Float = DEFAULT_TOLERANCE,
    ) {
        assertEquals(message, expected.toDouble(), actual.toDouble(), tolerance.toDouble())
    }

    /** Assert two [Vec2]s are component-wise equal within [tolerance]. */
    fun assertVec2Within(
        message: String,
        expected: Vec2,
        actual: Vec2,
        tolerance: Float = DEFAULT_TOLERANCE,
    ) {
        assertWithin("$message (x)", expected.x, actual.x, tolerance)
        assertWithin("$message (y)", expected.y, actual.y, tolerance)
    }

    /**
     * Assert two [SimulationState]s are equal: the [SimulationState.tick] exactly, and every ship
     * kinematic field within [tolerance]. Use this when asserting an expected replay end-state.
     */
    fun assertStateWithin(
        expected: SimulationState,
        actual: SimulationState,
        tolerance: Float = DEFAULT_TOLERANCE,
    ) {
        assertEquals("tick", expected.tick.toLong(), actual.tick.toLong())
        assertVec2Within("position", expected.ship.position, actual.ship.position, tolerance)
        assertVec2Within("velocity", expected.ship.velocity, actual.ship.velocity, tolerance)
        assertWithin("headingRadians", expected.ship.headingRadians, actual.ship.headingRadians, tolerance)
        assertWithin("angularVelocity", expected.ship.angularVelocity, actual.ship.angularVelocity, tolerance)
    }

    /**
     * Assert two snapshots are **exactly** equal (no tolerance) — the determinism contract
     * (UC02 AC#1/#11): identical seed + inputs + dt must reproduce the same state bit-for-bit.
     */
    fun assertStatesExactlyEqual(
        expected: SimulationState,
        actual: SimulationState,
    ) {
        assertEquals(expected, actual)
    }
}
