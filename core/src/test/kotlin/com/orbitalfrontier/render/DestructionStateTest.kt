package com.orbitalfrontier.render

import com.orbitalfrontier.combat.DestructionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the destruction / game-over gate [DestructionState] (UC33),
 * modelled exactly like [PauseStateTest] for [PauseState]. This is the deterministic half of the gate
 * that [com.orbitalfrontier.screen.PlayScreen] reads once per frame to decide whether the per-frame
 * advance runs — the GL-bound wiring (the nested sim gate, the modal overlay, hidden controls) is pinned
 * by the source-anchored [com.orbitalfrontier.screen.Uc33DestructionScreenGuardTest] and a live pass.
 *
 * Unlike the pause gate this one carries the [DestructionSummary] the overlay renders, so the
 * transitions assert both the pending flag AND the carried summary.
 */
class DestructionStateTest {
    private val summary = DestructionSummary(cargoUnitsLost = 3, creditPenalty = 0L, respawnLocationName = "Alpha Station")

    @Test
    fun `the default state is not pending and carries no summary`() {
        val state = DestructionState()
        assertFalse("a fresh destruction gate is not pending (sim runs)", state.isPending)
        assertNull("a fresh gate carries no consequence summary", state.summary)
    }

    @Test
    fun `pending raises the gate and carries the summary`() {
        val state = DestructionState().pending(summary)
        assertTrue("AC#1: a pending destruction freezes the sim", state.isPending)
        assertSame("AC#2: the gate carries the consequence summary the overlay renders", summary, state.summary)
    }

    @Test
    fun `cleared lowers the gate from a pending state`() {
        val pending = DestructionState().pending(summary)
        val cleared = pending.cleared()
        assertFalse("AC#3: acknowledging CONTINUE clears the gate (sim resumes)", cleared.isPending)
        assertNull("the cleared gate drops the summary", cleared.summary)
    }

    @Test
    fun `cleared is idempotent from an already-clear state`() {
        // No needless allocation: clearing a gate that carries nothing returns the same instance
        // (mirrors the continueAfterDestruction no-op guard on the screen).
        val clear = DestructionState()
        val result = clear.cleared()
        assertFalse("a clear gate stays clear", result.isPending)
        assertSame("cleared() returns the same instance when already clear", clear, result)
    }

    @Test
    fun `round-trips clear - pending - clear`() {
        val clear = DestructionState()
        assertTrue(clear.pending(summary).isPending)
        assertFalse(clear.pending(summary).cleared().isPending)
    }

    @Test
    fun `equality is by carried summary`() {
        assertEquals(
            "two gates pending on an equal summary are equal",
            DestructionState().pending(summary),
            DestructionState().pending(summary.copy()),
        )
    }
}
