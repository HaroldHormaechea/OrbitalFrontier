package com.orbitalfrontier.render

import com.orbitalfrontier.combat.DestructionSummary

/**
 * Pure, libGDX-free gate for the **destruction / game-over screen** (UC33), modelled exactly like
 * [PauseState]: an immutable value the [com.orbitalfrontier.screen.PlayScreen] holds and reads once per
 * frame to decide whether the deterministic per-frame advance runs.
 *
 * Like the pause overlay it **freezes the simulation** while a destruction is pending — but it is a
 * distinct, narrower gate: it is raised *by the simulation itself* (a hull-destroying hit, not a player
 * tap), it carries the [DestructionSummary] the screen renders, and it clears only on a deliberate
 * CONTINUE. While [isPending] the screen skips `advanceSimulation` (nested under the pause gate so the
 * two compose), shows the modal destruction overlay on top, and hides the flight controls / pause
 * button. Keeping the gate a pure value (no Scene2D actor) keeps it JVM-unit-testable (ADR 0001),
 * independent of the GL-bound overlay it drives.
 */
data class DestructionState(val summary: DestructionSummary? = null) {
    /** Whether a destruction consequence is awaiting the player's acknowledgement (freezes the sim). */
    val isPending: Boolean get() = summary != null

    /** The state after a destruction: pending on [summary], awaiting a CONTINUE. */
    fun pending(summary: DestructionSummary): DestructionState = copy(summary = summary)

    /** The state after the player acknowledges CONTINUE: nothing pending (returns `this` when already clear). */
    fun cleared(): DestructionState = if (summary == null) this else copy(summary = null)
}
