package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.faction.Reputation

/**
 * The outcome of a single [Missions.resolve] or [Missions.advance] call — the updated mission log,
 * the player's credits, cargo and per-faction [reputation] after the action, and whether anything
 * event-worthy changed.
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes). "Offer already gone / quota not held / wrong station / nothing
 * expired" is a normal, recoverable no-op reported via [changed] = false with the **same** [log],
 * [credits], [cargo] and [reputation] instances returned unchanged — so the caller (the play screen on
 * device, the simulation on the JVM) can cheaply detect "nothing happened" with a reference check and
 * skip the autosave. The caller folds the values back into its state.
 *
 * **UC14 — [reputation].** A turn-in that completes a faction mission, or a courier expiry, returns an
 * updated [reputation] (the faction's standing moved by [ReputationParams.missionCompleteDelta] /
 * [ReputationParams.courierFailDelta], clamped). Every other outcome — including a faction-less
 * mission's turn-in — returns the **input** [reputation] instance unchanged, so the reputation field
 * never spuriously perturbs a pre-UC14 replay (the same-instance, byte-identical invariant).
 *
 * **[changed] semantics differ slightly between the two callers:**
 *  - For [Missions.resolve] it means "the order applied" (an accept/turn-in took effect).
 *  - For [Missions.advance] it means "a courier just **expired** this tick" (a terminal transition
 *    worth an event autosave). A plain per-tick timer decrement still updates [log] but reports
 *    [changed] = false, so the caller always folds [log] (to advance the timer) yet only autosaves on
 *    an actual failure — keeping per-tick I/O off the 60 FPS budget.
 */
data class MissionResult(
    val log: MissionLog,
    val credits: Long,
    val cargo: Cargo,
    val reputation: Reputation,
    val changed: Boolean,
)
