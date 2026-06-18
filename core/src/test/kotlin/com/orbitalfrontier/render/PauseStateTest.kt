package com.orbitalfrontier.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the UC32 in-flight pause overlay's paused/running toggle
 * model, [PauseState]. This is the deterministic half of AC#1 (a pause control opens the overlay) and
 * AC#4 (resuming continues exactly where the player left off) — the actual tap-routing/draw and the
 * sim-freeze wiring are GL-bound and verified by the source-anchored
 * [com.orbitalfrontier.screen.Uc32PauseOverlayGuardTest] plus a live emulator pass.
 *
 * The deliberate inverse of [MapOverlayStateTest]: where the click-to-zoom map overlay is LIVE, this
 * state is what [com.orbitalfrontier.screen.PlayScreen] flips on the HUD pause button / Android BACK
 * key (`paused()`/`resumed()`/`toggled()`) and reads (`isPaused`) once per frame to gate the entire
 * per-frame state-advance (AC#2/#5).
 */
class PauseStateTest {
    @Test
    fun `the default state is running`() {
        assertFalse("AC#1: a fresh pause state starts running (not paused)", PauseState().isPaused)
    }

    @Test
    fun `paused freezes from the default running state`() {
        // AC#1/#2: opening the overlay pauses the sim.
        assertTrue("AC#1: pausing a running game freezes it", PauseState().paused().isPaused)
    }

    @Test
    fun `resumed runs again from the paused state`() {
        // AC#4: tapping Resume / BACK unfreezes the sim so play continues where it left off.
        val paused = PauseState(isPaused = true)
        assertFalse("AC#4: resuming a paused game runs it", paused.resumed().isPaused)
    }

    @Test
    fun `toggled flips running to paused and back`() {
        val running = PauseState()
        assertTrue("AC#1: toggling a running game pauses it", running.toggled().isPaused)
        assertFalse("AC#4: toggling twice round-trips to running", running.toggled().toggled().isPaused)
    }

    @Test
    fun `paused is idempotent from the paused state`() {
        // No needless allocation + no double-pause: pausing an already-paused state returns the same
        // instance (mirrors the openPause() no-op guard on the screen).
        val paused = PauseState(isPaused = true)
        val result = paused.paused()
        assertTrue("AC#2: pausing an already-paused game stays paused", result.isPaused)
        assertSame("paused() returns the same instance when already paused", paused, result)
    }

    @Test
    fun `resumed is idempotent from the running state`() {
        // A stray resume while already running is a harmless no-op that allocates nothing.
        val running = PauseState()
        val result = running.resumed()
        assertFalse("AC#4: resuming an already-running game stays running", result.isPaused)
        assertSame("resumed() returns the same instance when already running", running, result)
    }

    @Test
    fun `round-trips running - paused - running`() {
        val running = PauseState()
        assertTrue(running.paused().isPaused)
        assertFalse(running.paused().resumed().isPaused)
    }
}
