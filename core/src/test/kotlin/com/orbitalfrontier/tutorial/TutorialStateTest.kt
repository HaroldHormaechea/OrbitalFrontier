package com.orbitalfrontier.tutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure first-run-tutorial progression machine ([TutorialState] over
 * [TutorialStep]/[TutorialEvent]) — the non-trivial logic UC36 AC#5 calls out for testing.
 *
 * This is the JVM-only heart of the feature (engine-free, ADR 0001): the ordered step list represents the
 * whole core loop (AC#1), each step advances only on its matching completing event and can be skipped
 * (AC#2), and a finished / per-step-skipped / skip-all'd tutorial all converge on the same terminal
 * "complete" value (which the screen turns into the persisted first-run flag, AC#3). Nothing here touches
 * the simulation — the state only ever *observes* events the deterministic sim already produced (AC#4),
 * so this whole suite runs with no GL/engine backend.
 */
class TutorialStateTest {
    // --- AC#1: the ordered steps represent the whole core loop, in order ------------------------------

    @Test
    fun `the tutorial covers the whole core loop in the steer-dock-mission-gather-refuel-fire order`() {
        assertEquals(
            "the ordered steps must walk the core loop exactly once, in order (AC#1)",
            listOf(
                TutorialStep.STEER,
                TutorialStep.DOCK,
                TutorialStep.ACCEPT_MISSION,
                TutorialStep.GATHER,
                TutorialStep.REFUEL,
                TutorialStep.FIRE,
            ),
            TutorialStep.ORDER,
        )
    }

    @Test
    fun `ORDER is exactly the declared enum entries, so a new step is covered automatically`() {
        assertEquals(TutorialStep.entries.toList(), TutorialStep.ORDER)
        assertEquals("every loop beat is a distinct step", TutorialStep.ORDER.size, TutorialStep.ORDER.toSet().size)
    }

    @Test
    fun `every step names a control to highlight and a distinct completing event`() {
        for (step in TutorialStep.ORDER) {
            // AC#2: each step points the player at a specific control.
            assertTrue("step $step must name a highlight control", step.highlight in TutorialHighlight.entries)
            // AC#1: each beat is gated by an actual completing event.
            assertTrue("step $step must name a completing event", step.completingEvent in TutorialEvent.entries)
        }
        // Each gameplay completing event drives exactly one step (no two steps wait on the same event),
        // so the ordered walk never double-advances on a single recorded event.
        val events = TutorialStep.ORDER.map { it.completingEvent }
        assertEquals("each step waits on a distinct completing event", events.size, events.toSet().size)
    }

    @Test
    fun `the ASCII copy stays inside the bundled-font glyph set (UC28)`() {
        // The UC28 game font ships ASCII plus the two extra glyphs ° and →; onboarding copy must stay
        // within that set or it renders as tofu on device. The current steps use plain ASCII only.
        val allowedExtra = setOf('°', '→')
        for (step in TutorialStep.ORDER) {
            for (ch in step.copy) {
                assertTrue(
                    "step $step copy char '$ch' (U+${ch.code.toString(16)}) must be ASCII or one of $allowedExtra",
                    ch.code in 0x20..0x7E || ch in allowedExtra,
                )
            }
            assertTrue("step $step copy must not be blank", step.copy.isNotBlank())
        }
    }

    // --- Initial / terminal anchors -------------------------------------------------------------------

    @Test
    fun `a new tutorial starts at the first step and is not complete`() {
        val state = TutorialState.NEW
        assertEquals(0, state.stepIndex)
        assertEquals(TutorialStep.STEER, state.activeStep)
        assertFalse(state.isComplete)
    }

    @Test
    fun `the default-constructed state equals NEW`() {
        assertEquals(TutorialState.NEW, TutorialState())
    }

    @Test
    fun `a COMPLETED tutorial is terminal with no active step`() {
        val state = TutorialState.COMPLETED
        assertTrue(state.isComplete)
        assertNull(state.activeStep)
        assertEquals(TutorialStep.ORDER.size, state.stepIndex)
    }

    @Test
    fun `activeStep tracks the step index across the whole walk`() {
        TutorialStep.ORDER.forEachIndexed { index, step ->
            val state = TutorialState(stepIndex = index)
            assertEquals("index $index addresses $step", step, state.activeStep)
            assertFalse("index $index is mid-tutorial, not complete", state.isComplete)
        }
        // One past the last step is the terminal/complete position.
        assertNull(TutorialState(stepIndex = TutorialStep.ORDER.size).activeStep)
        assertTrue(TutorialState(stepIndex = TutorialStep.ORDER.size).isComplete)
    }

    // --- AC#1/#2: advancedBy only advances on the matching event --------------------------------------

    @Test
    fun `advancedBy advances exactly one step on the active step's completing event`() {
        val start = TutorialState.NEW // STEER
        val next = start.advancedBy(TutorialStep.STEER.completingEvent)
        assertEquals("a matching event advances by one step", TutorialStep.DOCK, next.activeStep)
        assertEquals(1, next.stepIndex)
    }

    @Test
    fun `advancedBy ignores a non-matching event so out-of-order play never skips a step`() {
        val start = TutorialState.NEW // STEER, completed by STEERED
        // Fire every event that is NOT the active step's completing event: the state must be unchanged.
        for (event in TutorialEvent.entries) {
            if (event == TutorialStep.STEER.completingEvent) continue
            val result = start.advancedBy(event)
            assertSame("event $event must not advance the STEER step", start, result)
            assertEquals(TutorialStep.STEER, result.activeStep)
        }
    }

    @Test
    fun `feeding each step's completing event in order walks the whole tutorial to completion`() {
        var state = TutorialState.NEW
        for (step in TutorialStep.ORDER) {
            assertEquals("waiting on $step", step, state.activeStep)
            // An unrelated event in the middle of a step is a no-op (proves per-step gating end to end).
            val wrong = TutorialEvent.entries.first { it != step.completingEvent }
            assertSame("a wrong event mid-step is ignored", state, state.advancedBy(wrong))
            state = state.advancedBy(step.completingEvent)
        }
        assertTrue("the tutorial is complete after every step's event", state.isComplete)
        assertNull(state.activeStep)
        assertEquals("the walked index lands one past the last step", TutorialStep.ORDER.size, state.stepIndex)
    }

    @Test
    fun `advancedBy on a complete state is a terminal no-op`() {
        val done = TutorialState.COMPLETED
        for (event in TutorialEvent.entries) {
            assertSame("a complete tutorial ignores $event", done, done.advancedBy(event))
        }
    }

    // --- AC#2: per-step skip + skip-all ----------------------------------------------------------------

    @Test
    fun `skipped advances past the current step without needing its event`() {
        var state = TutorialState.NEW
        TutorialStep.ORDER.forEachIndexed { index, _ ->
            val nextIndex = index + 1
            state = state.skipped()
            assertEquals("skip moves to the next index", nextIndex, state.stepIndex)
        }
        assertTrue("skipping every step in turn completes the tutorial", state.isComplete)
    }

    @Test
    fun `skipped on a complete state is a no-op`() {
        assertSame(TutorialState.COMPLETED, TutorialState.COMPLETED.skipped())
    }

    @Test
    fun `dismissed jumps straight to the terminal complete state`() {
        // Skip-all from any position lands on the same terminal value.
        for (index in 0..TutorialStep.ORDER.size) {
            val dismissed = TutorialState(stepIndex = index).dismissed()
            assertTrue("skip-all from index $index is complete", dismissed.isComplete)
            assertNull(dismissed.activeStep)
            assertEquals("skip-all converges on the COMPLETED terminal", TutorialState.COMPLETED, dismissed)
        }
    }

    @Test
    fun `dismissed on a complete state is a no-op`() {
        assertSame(TutorialState.COMPLETED, TutorialState.COMPLETED.dismissed())
    }

    @Test
    fun `a fully-walked, a fully-skipped and a dismissed tutorial all converge on the same complete value`() {
        // Walked to the end via events.
        var walked = TutorialState.NEW
        TutorialStep.ORDER.forEach { walked = walked.advancedBy(it.completingEvent) }
        // Skipped one step at a time to the end.
        var skipped = TutorialState.NEW
        repeat(TutorialStep.ORDER.size) { skipped = skipped.skipped() }
        // Dismissed in one shot.
        val dismissed = TutorialState.NEW.dismissed()

        assertEquals(TutorialState.COMPLETED, walked)
        assertEquals(TutorialState.COMPLETED, skipped)
        assertEquals(TutorialState.COMPLETED, dismissed)
        assertTrue(walked.isComplete && skipped.isComplete && dismissed.isComplete)
    }
}
