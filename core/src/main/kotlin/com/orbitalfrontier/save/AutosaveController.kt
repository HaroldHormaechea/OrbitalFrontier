package com.orbitalfrontier.save

import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.world.WorldState

/**
 * Decides *when* to autosave and drives the single-writer [SaveExecutor] to do it (UC04 AC#2).
 *
 * Triggers (per UC04): on key events ([onEvent] — e.g. a jump), periodically during flight
 * ([update] accumulates frame time and enqueues every [intervalSeconds]), and on app pause/exit
 * ([onPauseOrExit], which also blocks until the write is durable). SRP: this owns autosave timing
 * only; the actual write/serialization lives in [GameStateRepository].
 *
 * Threading (coding-guidelines § "Concurrency"): [onEvent]/[update]/[onPauseOrExit] are all called
 * on the render thread, so the [snapshotSupplier] runs there and captures an **immutable**
 * [WorldState] on the render thread; the DB write then happens on the executor thread. No game
 * state or GL is touched off-thread. There is **no per-frame INFO logging** — [update] runs every
 * frame but only logs (once) when it actually enqueues a save, protecting the 60 FPS budget.
 *
 * The periodic interval is data-driven (UC52 AC#1): it defaults to [AutosaveParams.periodicIntervalSeconds]
 * (20s) — within the ~15–30s range the use case calls for, frequent enough to bound lost progress without
 * churning the DB during flight.
 *
 * UC52 AC#2: an [AutosaveActivitySignal] is pulsed on each enqueue (markSaving, render thread) and after
 * each write (markSaved, executor thread) so the play screen can show a subtle "Saving"/"Saved" indicator.
 */
class AutosaveController(
    private val repository: GameStateRepository,
    private val saveExecutor: SaveExecutor,
    private val logger: Logger,
    private val snapshotSupplier: () -> WorldState,
    // UC38: which save slot the autosave writes to. A supplier (not a fixed value) so the autosave always
    // follows the live active slot — load-slot / save-into-slot update the app's active-slot field, and the
    // very next autosave targets the new slot. Defaults to the legacy slot so existing call sites / tests
    // that predate save slots keep writing the single autosave (slot 0) unchanged.
    private val slotSupplier: () -> SlotId = { SlotId.LEGACY },
    // UC52: cross-thread bridge to the on-screen autosave indicator (AC#2). markSaving() fires on this
    // (render) thread at enqueue; markSaved() fires on the executor thread after the write. Defaults to a
    // private, un-polled instance so call sites / tests that don't show the indicator are unaffected.
    private val activitySignal: AutosaveActivitySignal = AutosaveActivitySignal(),
    // UC52: data-driven autosave tuning (AC#1). The periodic interval is sourced from here rather than a
    // hard-coded constant; [intervalSeconds] defaults to it but stays overridable for tests.
    private val params: AutosaveParams = AutosaveParams(),
    private val intervalSeconds: Float = params.periodicIntervalSeconds,
) {
    /** Seconds of flight accumulated since the last periodic save; render-thread only. */
    private var secondsSincePeriodicSave = 0f

    /** Autosave triggered by a discrete world event (e.g. a sector jump). */
    fun onEvent(reason: String) {
        enqueueSave(reason)
    }

    /**
     * Autosave on a **critical** world event and **block until it is durably written** (UC33 AC#4) — the
     * event-driven analogue of [onPauseOrExit]. Used for a transition a crash must never duplicate or
     * skip: a destruction respawn. The respawn is committed and durably persisted *before* the player is
     * handed the consequence screen, so closing the app on that screen reloads the post-respawn state and
     * the cargo-loss penalty is applied exactly once. Unlike the fire-and-forget [onEvent], this flushes
     * the single-writer executor (the same blocking guarantee as the pause/exit path).
     */
    fun onCriticalEvent(reason: String) {
        enqueueSave(reason)
        saveExecutor.flush()
    }

    /**
     * Accumulate frame time; enqueue a periodic save once [intervalSeconds] of flight has elapsed,
     * then reset the accumulator. Called every frame — must stay allocation-free and log-free on the
     * common (no-save) path.
     */
    fun update(dt: Float) {
        secondsSincePeriodicSave += dt
        if (secondsSincePeriodicSave >= intervalSeconds) {
            secondsSincePeriodicSave = 0f
            enqueueSave("periodic")
        }
    }

    /**
     * Enqueue a final save and **block until it is written** (UC04 AC#2). Resets the periodic
     * accumulator so a subsequent resume starts a fresh interval rather than saving immediately.
     */
    fun onPauseOrExit() {
        enqueueSave("pause/exit")
        saveExecutor.flush()
        secondsSincePeriodicSave = 0f
    }

    private fun enqueueSave(reason: String) {
        // Snapshot on the render thread (immutable WorldState); the write runs on the executor.
        val snapshot = snapshotSupplier()
        // Snapshot the target slot on the render thread too (UC38), so the executor thread writes to the
        // slot that was active at enqueue time even if the active slot changes before the write runs.
        val slot = slotSupplier()
        logger.info(TAG, "Autosave enqueued (reason=$reason): slot=${slot.value}, sector=${snapshot.currentSector.value}")
        // UC52 AC#2: signal "saving" now (render thread) and "saved" after the write (executor thread), so
        // the render-thread indicator can show progress without touching world state across threads.
        activitySignal.markSaving()
        saveExecutor.execute {
            repository.saveGameState(slot, snapshot)
            activitySignal.markSaved()
        }
    }

    private companion object {
        const val TAG = "Save"
    }
}
