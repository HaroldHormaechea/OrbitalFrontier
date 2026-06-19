package com.orbitalfrontier.tutorial

/**
 * Pure, libGDX-free progression state for the first-run tutorial (UC36 AC#1/#2) — **the non-trivial logic
 * AC#5 calls out for unit testing**.
 *
 * Modelled as an immutable value advancing through [TutorialStep.ORDER] by a single [stepIndex]; the
 * play screen holds one of these, feeds it [TutorialEvent]s recorded from the gameplay seams, and reads
 * [activeStep] each frame to drive the overlay + control highlight. Completion is simply "walked past the
 * last step" ([stepIndex] >= step count) — no separate flag — so a finished, skipped, or skip-all'd
 * tutorial all converge on the same [isComplete] terminal value.
 *
 * Every transition is pure and total (returns `this`, never an out-of-range state, when there is nothing
 * to do), so the gate logic is JVM-unit-testable independent of any Scene2D actor (ADR 0001), exactly
 * like [com.orbitalfrontier.render.PauseState]. The simulation is never consulted or mutated here — the
 * tutorial only ever *observes* events the deterministic sim already produced (AC#4).
 */
data class TutorialState(val stepIndex: Int = 0) {
    /** The step awaiting completion, or null once the tutorial is finished/skipped (terminal). */
    val activeStep: TutorialStep?
        get() = TutorialStep.ORDER.getOrNull(stepIndex)

    /** True once every step is done (or the player skipped all) — no active step remains. */
    val isComplete: Boolean
        get() = activeStep == null

    /**
     * Advance iff [event] completes the [activeStep]; otherwise return `this` unchanged. A non-matching
     * event (e.g. firing during the STEER step) is ignored, so out-of-order play never skips a step — the
     * player still sees each prompt in turn. Terminal when already complete.
     */
    fun advancedBy(event: TutorialEvent): TutorialState {
        val step = activeStep ?: return this
        return if (step.completingEvent == event) TutorialState(stepIndex + 1) else this
    }

    /** Skip just the current step, advancing to the next (AC#2). No-op when already complete. */
    fun skipped(): TutorialState = if (isComplete) this else TutorialState(stepIndex + 1)

    /** Skip the whole tutorial at once ("SKIP ALL"): jump straight to the terminal state. */
    fun dismissed(): TutorialState = if (isComplete) this else TutorialState(TutorialStep.ORDER.size)

    companion object {
        /** A brand-new tutorial positioned at the first step. */
        val NEW = TutorialState(stepIndex = 0)

        /** An already-finished tutorial (used when the persisted first-run flag is set). */
        val COMPLETED = TutorialState(stepIndex = TutorialStep.ORDER.size)
    }
}
