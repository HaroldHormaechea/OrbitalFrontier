package com.orbitalfrontier.save

import java.util.concurrent.atomic.AtomicLong

/**
 * A thread-safe one-way bridge from the off-thread save writer to the render thread, so the UI can show
 * an autosave indicator (UC52 AC#2) without ever touching game state across threads.
 *
 * The autosave write runs on the single-writer [com.orbitalfrontier.platform.SaveExecutor] thread, while
 * the indicator is drawn on the libGDX render thread. This carries only two monotonic counters
 * ([AtomicLong]): [markSaving] (render thread, when a save is enqueued) bumps one, [markSaved] (executor
 * thread, after the write completes) bumps the other. The render thread calls [poll] once per frame and
 * learns whether a save **started** and/or **finished** since the previous poll — never reading or
 * mutating any world state, honouring the concurrency rule (coding-guidelines § "Concurrency").
 *
 * Pure JVM (`java.util.concurrent`), engine-free, so the start/finish bookkeeping is unit-testable
 * without real threads (call [markSaving]/[markSaved]/[poll] directly).
 */
class AutosaveActivitySignal {
    /** Bumped on the render thread each time a save is enqueued. */
    private val enqueued = AtomicLong(0)

    /** Bumped on the executor thread each time a save finishes writing. */
    private val completed = AtomicLong(0)

    // The last counts observed by poll(). poll() is only ever called on the render thread, so these need
    // no synchronization of their own — the AtomicLongs handle the cross-thread reads.
    private var seenEnqueued = 0L
    private var seenCompleted = 0L

    /** Render thread: record that an autosave was just enqueued. */
    fun markSaving() {
        enqueued.incrementAndGet()
    }

    /** Executor thread: record that an autosave just finished writing. */
    fun markSaved() {
        completed.incrementAndGet()
    }

    /**
     * Render thread: consume activity since the previous poll. [AutosaveActivity.started] is true if at
     * least one save was enqueued since last poll, [AutosaveActivity.finished] if at least one completed.
     * Both can be true in one frame when a save starts and finishes within the same frame (e.g. a fast or
     * synchronous write).
     */
    fun poll(): AutosaveActivity {
        val e = enqueued.get()
        val c = completed.get()
        val activity = AutosaveActivity(started = e > seenEnqueued, finished = c > seenCompleted)
        seenEnqueued = e
        seenCompleted = c
        return activity
    }
}

/** What [AutosaveActivitySignal.poll] observed since the previous frame. */
data class AutosaveActivity(
    val started: Boolean,
    val finished: Boolean,
) {
    companion object {
        /** No activity this frame. */
        val NONE = AutosaveActivity(started = false, finished = false)
    }
}
