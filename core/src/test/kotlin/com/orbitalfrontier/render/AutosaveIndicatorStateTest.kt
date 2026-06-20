package com.orbitalfrontier.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure render-only state-machine tests for [AutosaveIndicatorState] (UC52 AC#2): the subtle
 * "Saving" / "Saved" cue driven each frame from the cross-thread autosave signal. Like
 * [CombatHudStateTest], the derivation is JVM-pure (no engine types, off the deterministic
 * simulation), so the phase transitions, fade timer, and the derived `visible`/`alpha`/`label`
 * are fully unit-testable.
 */
class AutosaveIndicatorStateTest {
    @Test
    fun `it starts idle, invisible, and label-less`() {
        val state = AutosaveIndicatorState()
        assertEquals(AutosaveIndicatorState.Phase.IDLE, state.phase)
        assertFalse(state.visible)
        assertEquals(0f, state.alpha, 0f)
        assertEquals("", state.label)
    }

    @Test
    fun `onSaveStarted enters SAVING, fully opaque and visible`() {
        val state = AutosaveIndicatorState()
        state.onSaveStarted()

        assertEquals(AutosaveIndicatorState.Phase.SAVING, state.phase)
        assertTrue(state.visible)
        assertEquals("SAVING is held fully opaque until the write finishes", 1f, state.alpha, 0f)
        assertEquals(AutosaveIndicatorState.SAVING_LABEL, state.label)
    }

    @Test
    fun `SAVING is held opaque across frames until the save finishes`() {
        val state = AutosaveIndicatorState()
        state.onSaveStarted()

        // A long write spanning many frames must not fade the "Saving" cue.
        repeat(10) { state.update(1f) }

        assertEquals(AutosaveIndicatorState.Phase.SAVING, state.phase)
        assertEquals(1f, state.alpha, 0f)
    }

    @Test
    fun `onSaveFinished enters SAVED and shows the saved label`() {
        val state = AutosaveIndicatorState()
        state.onSaveStarted()
        state.onSaveFinished()

        assertEquals(AutosaveIndicatorState.Phase.SAVED, state.phase)
        assertTrue(state.visible)
        assertEquals(AutosaveIndicatorState.SAVED_LABEL, state.label)
        assertEquals("SAVED starts fully opaque before fading", 1f, state.alpha, 1e-4f)
    }

    @Test
    fun `the SAVED cue fades linearly and returns to IDLE once elapsed`() {
        val state = AutosaveIndicatorState()
        state.onSaveFinished()

        val half = AutosaveIndicatorState.SAVED_VISIBLE_SECONDS / 2f
        state.update(half)
        assertEquals(AutosaveIndicatorState.Phase.SAVED, state.phase)
        assertEquals("alpha fades linearly with the remaining hold", 0.5f, state.alpha, 1e-3f)

        state.update(half)
        assertEquals("the fade returns to IDLE once the hold elapses", AutosaveIndicatorState.Phase.IDLE, state.phase)
        assertFalse(state.visible)
        assertEquals(0f, state.alpha, 0f)
        assertEquals("", state.label)
    }

    @Test
    fun `over-shooting the fade clamps to idle with zero alpha`() {
        val state = AutosaveIndicatorState()
        state.onSaveFinished()

        state.update(AutosaveIndicatorState.SAVED_VISIBLE_SECONDS * 5f)

        assertEquals(AutosaveIndicatorState.Phase.IDLE, state.phase)
        assertEquals("alpha never goes negative", 0f, state.alpha, 0f)
    }

    @Test
    fun `update is a no-op while idle`() {
        val state = AutosaveIndicatorState()
        state.update(2f)
        assertEquals(AutosaveIndicatorState.Phase.IDLE, state.phase)
        assertFalse(state.visible)
    }

    @Test
    fun `a fresh save started during the SAVED fade re-holds the indicator opaque`() {
        val state = AutosaveIndicatorState()
        state.onSaveFinished()
        state.update(AutosaveIndicatorState.SAVED_VISIBLE_SECONDS / 2f) // mid-fade

        state.onSaveStarted() // a new autosave began

        assertEquals(AutosaveIndicatorState.Phase.SAVING, state.phase)
        assertEquals("a new save re-holds the cue fully opaque", 1f, state.alpha, 0f)
    }

    @Test
    fun `both labels are ASCII and within the bundled font glyph coverage`() {
        val required = GameFont.REQUIRED_GLYPHS.toSet()
        for (label in listOf(AutosaveIndicatorState.SAVING_LABEL, AutosaveIndicatorState.SAVED_LABEL)) {
            for (ch in label) {
                assertTrue("label '$label' must be ASCII (UC52: subtle indicator)", ch.code in 0x20..0x7E)
                assertTrue(
                    "glyph '$ch' in '$label' must be in GameFont.REQUIRED_GLYPHS so the bundled font renders it",
                    ch.code in required,
                )
            }
        }
    }
}
