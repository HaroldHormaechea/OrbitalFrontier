package com.orbitalfrontier.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AutosaveActivitySignal] (UC52 AC#2): the thread-safe one-way bridge from the
 * off-thread save writer to the render thread. The render thread learns, once per frame via [poll],
 * whether a save **started** and/or **finished** since the previous poll — without ever reading or
 * mutating world state. Exercised by calling markSaving / markSaved / poll directly (no real threads).
 */
class AutosaveActivitySignalTest {
    @Test
    fun `a fresh signal reports no activity`() {
        val signal = AutosaveActivitySignal()
        assertEquals(AutosaveActivity.NONE, signal.poll())
    }

    @Test
    fun `markSaving is observed as started on the next poll`() {
        val signal = AutosaveActivitySignal()
        signal.markSaving()

        val activity = signal.poll()
        assertTrue(activity.started)
        assertFalse(activity.finished)
    }

    @Test
    fun `markSaved is observed as finished on the next poll`() {
        val signal = AutosaveActivitySignal()
        signal.markSaved()

        val activity = signal.poll()
        assertFalse(activity.started)
        assertTrue(activity.finished)
    }

    @Test
    fun `a start and finish within one frame are both observed`() {
        val signal = AutosaveActivitySignal()
        signal.markSaving()
        signal.markSaved()

        val activity = signal.poll()
        assertTrue("a fast save started this frame", activity.started)
        assertTrue("and finished this frame", activity.finished)
    }

    @Test
    fun `activity is consumed - a second poll with no new pulses reports NONE`() {
        val signal = AutosaveActivitySignal()
        signal.markSaving()
        signal.markSaved()
        signal.poll()

        assertEquals("poll consumes the observed activity", AutosaveActivity.NONE, signal.poll())
    }

    @Test
    fun `each new save across frames is observed once`() {
        val signal = AutosaveActivitySignal()

        signal.markSaving()
        assertTrue(signal.poll().started)
        assertFalse("the same start is not re-reported", signal.poll().started)

        signal.markSaving()
        assertTrue("a fresh save is observed in its frame", signal.poll().started)
    }
}
