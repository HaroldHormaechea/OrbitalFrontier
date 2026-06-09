package com.orbitalfrontier.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the UC23 click-to-zoom map overlay's open/closed toggle
 * model, [MapOverlayState]. This is the deterministic half of AC#1 (tapping the map opens the overlay)
 * and AC#5 (the overlay can be dismissed, returning to gameplay) — the actual tap-routing/draw is
 * GL-bound and verified by a live emulator pass + the source-anchored [com.orbitalfrontier.screen.Uc23MapOverlayGuardTest].
 *
 * The state is what [com.orbitalfrontier.screen.PlayScreen] swaps on a minimap tap (`toggled()`) and
 * on any dismiss tap (`dismissed()`), then reads (`isOpen`) each frame to drive the overlay draw and
 * control visibility.
 */
class MapOverlayStateTest {
    @Test
    fun `the default state is closed`() {
        assertFalse("AC#1: a fresh overlay state starts closed", MapOverlayState().isOpen)
    }

    @Test
    fun `toggled opens from the default closed state`() {
        // AC#1: the first minimap tap opens the zoomed overlay.
        assertTrue("AC#1: toggling a closed overlay opens it", MapOverlayState().toggled().isOpen)
    }

    @Test
    fun `toggled closes again from the open state`() {
        // AC#5: tapping the minimap again (a toggle) dismisses the overlay back to gameplay.
        val open = MapOverlayState(isOpen = true)
        assertFalse("AC#5: toggling an open overlay closes it", open.toggled().isOpen)
    }

    @Test
    fun `toggled round-trips closed - open - closed`() {
        val closed = MapOverlayState()
        assertTrue(closed.toggled().isOpen)
        assertFalse(closed.toggled().toggled().isOpen)
    }

    @Test
    fun `dismissed closes an open overlay`() {
        // AC#5: an explicit dismiss gesture (tap outside) always returns to the closed state.
        val open = MapOverlayState(isOpen = true)
        assertFalse("AC#5: dismissing an open overlay closes it", open.dismissed().isOpen)
    }

    @Test
    fun `dismissed is idempotent from the closed state`() {
        // AC#5: dismissing while already closed is a harmless no-op (the player is never trapped, and a
        // stray dismiss can't accidentally re-open). Returns the same instance (no needless allocation).
        val closed = MapOverlayState()
        val result = closed.dismissed()
        assertFalse("AC#5: dismissing a closed overlay stays closed", result.isOpen)
        assertSame("dismissed() returns the same instance when already closed", closed, result)
    }
}
