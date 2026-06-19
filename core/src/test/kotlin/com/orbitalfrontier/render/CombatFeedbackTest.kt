package com.orbitalfrontier.render

import com.orbitalfrontier.combat.CombatEvent
import com.orbitalfrontier.combat.HostileId
import com.orbitalfrontier.combat.ShipSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CombatFeedback] (UC44 AC#3) — the pure, deterministic hit-feedback intensities that
 * drive the HUD's flashes and screen-shake. Three contracts pinned here:
 *
 *  - **Right channel per event.** Damage *dealt* (HostileHit / HostileDestroyed) brightens only the
 *    dealt flash; damage *taken* (PlayerHit / PlayerDestroyed) brightens the taken flash AND kicks the
 *    shake. Neither bleeds into the other.
 *  - **No stuck overlay.** Every intensity decays back to 0 on quiet frames, so when an encounter ends
 *    (EncounterCleared → no more events) the flash/shake fade out and never stick.
 *  - **Reduced motion is a PARAMETER, not the global (UC39).** [CombatFeedback.update] takes the
 *    reduced-motion flag as an argument; under reduced motion the accessors report 0. Because nothing
 *    here reads or mutates the [MotionPreference] global, these tests need **no `reset()` teardown** —
 *    deliberately, this file does not import or touch that global at all (the proof of param-purity).
 */
class CombatFeedbackTest {
    // A small frame dt that barely decays (DECAY_SECONDS = 0.35s), so a single bump stays clearly > 0.
    private val tinyDt = 0.001f

    private fun hostileHit(): CombatEvent = CombatEvent.HostileHit(HostileId(1), ShipSection.HULL)

    private fun playerHit(): CombatEvent = CombatEvent.PlayerHit(ShipSection.HULL)

    // --- AC#3: dealt vs. taken land on the correct channels --------------------------------------------

    @Test
    fun `damage dealt brightens only the dealt flash`() {
        val feedback = CombatFeedback()

        feedback.update(listOf(hostileHit()), tinyDt, reducedMotion = false)

        assertTrue("a landed shot lights the dealt flash", feedback.dealtFlash > 0f)
        assertEquals("dealing damage must not flash the damage-taken cue", 0f, feedback.takenFlash, 0f)
        assertEquals("dealing damage must not shake the camera", 0f, feedback.shakeIntensity, 0f)
    }

    @Test
    fun `a hostile destroyed also counts as damage dealt`() {
        val feedback = CombatFeedback()

        feedback.update(listOf(CombatEvent.HostileDestroyed(HostileId(1))), tinyDt, reducedMotion = false)

        assertTrue(feedback.dealtFlash > 0f)
        assertEquals(0f, feedback.takenFlash, 0f)
    }

    @Test
    fun `damage taken brightens the taken flash and kicks the shake`() {
        val feedback = CombatFeedback()

        feedback.update(listOf(playerHit()), tinyDt, reducedMotion = false)

        assertTrue("being hit lights the damage-taken flash", feedback.takenFlash > 0f)
        assertTrue("being hit shakes the camera", feedback.shakeIntensity > 0f)
        assertEquals("taking damage must not light the dealt-hit cue", 0f, feedback.dealtFlash, 0f)
    }

    @Test
    fun `player destroyed also counts as damage taken`() {
        val feedback = CombatFeedback()

        feedback.update(listOf(CombatEvent.PlayerDestroyed), tinyDt, reducedMotion = false)

        assertTrue(feedback.takenFlash > 0f)
        assertTrue(feedback.shakeIntensity > 0f)
    }

    // --- AC#3: intensities decay to 0 — no overlay sticks after the fight (EncounterCleared) ------------

    @Test
    fun `intensities decay back to zero on quiet frames so nothing sticks`() {
        val feedback = CombatFeedback()
        // Land both a dealt and a taken hit, so all three intensities are live.
        feedback.update(listOf(hostileHit(), playerHit()), tinyDt, reducedMotion = false)
        assertTrue(feedback.dealtFlash > 0f)
        assertTrue(feedback.takenFlash > 0f)
        assertTrue(feedback.shakeIntensity > 0f)

        // A long-enough quiet frame (> DECAY_SECONDS) must drive every channel to a hard 0 (no residue).
        feedback.update(emptyList(), 0.4f, reducedMotion = false)

        assertEquals("dealt flash must fully decay (no stuck overlay)", 0f, feedback.dealtFlash, 0f)
        assertEquals("taken flash must fully decay", 0f, feedback.takenFlash, 0f)
        assertEquals("shake must fully decay (no stuck camera offset on EncounterCleared)", 0f, feedback.shakeIntensity, 0f)
    }

    // --- AC#3 / UC39: reduced motion is a parameter; it zeroes shake and attenuates the flashes ----------

    @Test
    fun `reduced motion zeroes the shake and both flashes`() {
        val feedback = CombatFeedback()

        feedback.update(listOf(hostileHit(), playerHit()), tinyDt, reducedMotion = true)

        assertEquals("reduced motion is a full stop on shake (UC39)", 0f, feedback.shakeIntensity, 0f)
        assertEquals("reduced motion attenuates the taken flash to 0", 0f, feedback.takenFlash, 0f)
        assertEquals("reduced motion attenuates the dealt flash to 0", 0f, feedback.dealtFlash, 0f)
    }

    @Test
    fun `gating is driven purely by the parameter with no global state`() {
        val feedback = CombatFeedback()

        // Same raw intensities throughout (dt = 0 → no decay); only the parameter flips the gate. This
        // proves the reduced-motion behaviour rides entirely on the argument, not on any MotionPreference
        // global — hence no reset() teardown is needed (and this file never imports that global).
        feedback.update(listOf(hostileHit(), playerHit()), tinyDt, reducedMotion = false)
        assertTrue(feedback.shakeIntensity > 0f)
        assertTrue(feedback.takenFlash > 0f)
        assertTrue(feedback.dealtFlash > 0f)

        feedback.update(emptyList(), 0f, reducedMotion = true)
        assertEquals(0f, feedback.shakeIntensity, 0f)
        assertEquals(0f, feedback.takenFlash, 0f)
        assertEquals(0f, feedback.dealtFlash, 0f)

        // Flipping the parameter back restores the (undecayed) intensities — no global was ever consulted.
        feedback.update(emptyList(), 0f, reducedMotion = false)
        assertTrue(feedback.shakeIntensity > 0f)
        assertTrue(feedback.takenFlash > 0f)
        assertTrue(feedback.dealtFlash > 0f)
    }
}
