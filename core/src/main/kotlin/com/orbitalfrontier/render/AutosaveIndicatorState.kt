package com.orbitalfrontier.render

/**
 * The render-only state machine behind the subtle autosave indicator (UC52 AC#2): a tiny "Saving" /
 * "Saved" cue shown while a save is in progress and briefly after it completes.
 *
 * Driven each frame from [com.orbitalfrontier.save.AutosaveActivitySignal] polled on the render thread:
 * [onSaveStarted] enters [Phase.SAVING] (held, fully opaque, until the write finishes), [onSaveFinished]
 * enters [Phase.SAVED] (shown then fading out over [SAVED_VISIBLE_SECONDS]), and [update] ages the fade
 * back to [Phase.IDLE]. The derived [visible] / [alpha] / [label] are what the renderer draws.
 *
 * **Pure & render-only** — no engine types and, crucially, **not part of the deterministic simulation**:
 * it lives in the PlayScreen frame loop off `SimulationState`, so replay fixtures stay byte-identical
 * (ADR 0006/0012). Its [label] is ASCII-only ("Saving"/"Saved"), already within
 * [GameFont.REQUIRED_GLYPHS], so the bundled font renders it with no new glyph (UC28).
 *
 * JVM-unit-testable like [CombatHudState] / [com.orbitalfrontier.render.CombatFeedback].
 */
class AutosaveIndicatorState {
    enum class Phase { IDLE, SAVING, SAVED }

    var phase: Phase = Phase.IDLE
        private set

    /** Seconds remaining in the [Phase.SAVED] fade-out; 0 outside it. */
    private var savedRemaining = 0f

    /** A save was enqueued: show "Saving" (held opaque until [onSaveFinished]). */
    fun onSaveStarted() {
        phase = Phase.SAVING
        savedRemaining = 0f
    }

    /** A save finished: show "Saved", then fade out over [SAVED_VISIBLE_SECONDS]. */
    fun onSaveFinished() {
        phase = Phase.SAVED
        savedRemaining = SAVED_VISIBLE_SECONDS
    }

    /** Age the fade. A no-op outside [Phase.SAVED]; returns to [Phase.IDLE] once the fade elapses. */
    fun update(dt: Float) {
        if (phase != Phase.SAVED) return
        savedRemaining -= dt
        if (savedRemaining <= 0f) {
            savedRemaining = 0f
            phase = Phase.IDLE
        }
    }

    /** Whether the indicator draws this frame. */
    val visible: Boolean
        get() = phase != Phase.IDLE

    /** The cue text (empty while idle). ASCII-only — see [GameFont.REQUIRED_GLYPHS]. */
    val label: String
        get() =
            when (phase) {
                Phase.SAVING -> SAVING_LABEL
                Phase.SAVED -> SAVED_LABEL
                Phase.IDLE -> ""
            }

    /**
     * Draw opacity 0..1: fully opaque while SAVING, linearly fading from 1 to 0 across the SAVED hold,
     * 0 while idle. The renderer multiplies this into the (already subtle) base indicator alpha.
     */
    val alpha: Float
        get() =
            when (phase) {
                Phase.SAVING -> 1f
                Phase.SAVED -> (savedRemaining / SAVED_VISIBLE_SECONDS).coerceIn(0f, 1f)
                Phase.IDLE -> 0f
            }

    companion object {
        /** ASCII labels (within [GameFont.REQUIRED_GLYPHS]). */
        const val SAVING_LABEL = "Saving"
        const val SAVED_LABEL = "Saved"

        /** How long the "Saved" cue lingers (and fades) after a save completes (seconds). [TUNE] */
        const val SAVED_VISIBLE_SECONDS = 1.5f
    }
}
