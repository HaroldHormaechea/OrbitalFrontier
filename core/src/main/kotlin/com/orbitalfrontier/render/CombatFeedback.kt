package com.orbitalfrontier.render

import com.orbitalfrontier.combat.CombatEvent
import kotlin.math.min

/**
 * The transient **hit-feedback intensities** that drive the combat HUD's flashes and screen-shake
 * (UC44 AC#3). A small, deterministic-stateful value holder fed once per render frame from the SAME
 * structured [CombatEvent]s the rest of the device layer already consumes (SFX, toasts) — damage
 * **dealt** (a player shot landing / a kill) brightens [dealtFlash]; damage **taken** (the player is hit
 * or destroyed) brightens [takenFlash] and kicks [shakeIntensity]. Each intensity decays linearly back
 * to 0, so when an encounter ends (no more events) the overlay fades out and never sticks.
 *
 * **Pure & deterministic** — engine-free (no libGDX types), **no RNG, no wall clock**: every change is a
 * function of the events and the frame `dt`. So it is JVM-unit-testable (ADR 0001) and never read into
 * the simulation (it is render-only; determinism/replay, ADR 0006).
 *
 * **Reduced motion is a parameter, never the global (UC39).** [update] and the read accessors take the
 * reduced-motion flag as an argument rather than reading [MotionPreference] directly: that keeps this
 * class free of any global state, so its unit tests need no `reset()` teardown. When reduced motion is
 * on, [shakeIntensity] and both flashes report 0 (a full stop, matching UC39's reduced-motion contract).
 */
class CombatFeedback {
    private var dealt: Float = 0f
    private var taken: Float = 0f
    private var shake: Float = 0f
    private var reducedMotion: Boolean = false

    /**
     * Advance the feedback by [dt] seconds against this frame's combat [events] and the current
     * [reducedMotion] preference. Decays every intensity first (so a quiet frame fades the overlay),
     * then bumps from the events: HostileHit / HostileDestroyed → dealt; PlayerHit / PlayerDestroyed →
     * taken + shake. The reduced-motion flag is stored so the read accessors can gate without touching
     * any global. Idempotent on an empty list except for the decay.
     */
    fun update(
        events: List<CombatEvent>,
        dt: Float,
        reducedMotion: Boolean,
    ) {
        this.reducedMotion = reducedMotion

        val decay = if (DECAY_SECONDS > 0f) dt / DECAY_SECONDS else 1f
        dealt = (dealt - decay).coerceAtLeast(0f)
        taken = (taken - decay).coerceAtLeast(0f)
        shake = (shake - decay).coerceAtLeast(0f)

        for (event in events) {
            when (event) {
                is CombatEvent.HostileHit, is CombatEvent.HostileDestroyed ->
                    dealt = min(1f, dealt + DEALT_BUMP)
                is CombatEvent.PlayerHit, CombatEvent.PlayerDestroyed -> {
                    taken = min(1f, taken + TAKEN_BUMP)
                    shake = min(1f, shake + SHAKE_BUMP)
                }
                else -> Unit // fire / break-off / cleared events carry no hit feedback
            }
        }
    }

    /** Screen-shake intensity (0..1) — 0 under reduced motion (a full stop, UC39). */
    val shakeIntensity: Float get() = if (reducedMotion) 0f else shake

    /** Damage-taken flash intensity (0..1) — the red full-screen flash; 0 under reduced motion (UC39). */
    val takenFlash: Float get() = if (reducedMotion) 0f else taken

    /** Damage-dealt flash intensity (0..1) — the reticle/hit pulse; 0 under reduced motion (UC39). */
    val dealtFlash: Float get() = if (reducedMotion) 0f else dealt

    private companion object {
        /** Seconds for a full-strength intensity to decay back to 0 (snappy but readable). */
        const val DECAY_SECONDS: Float = 0.35f

        /** Per-event bumps — a single hit need not max the effect; clustered hits ramp toward 1. */
        const val DEALT_BUMP: Float = 0.6f
        const val TAKEN_BUMP: Float = 0.8f
        const val SHAKE_BUMP: Float = 0.7f
    }
}
