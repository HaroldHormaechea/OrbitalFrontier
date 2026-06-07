package com.orbitalfrontier.platform

/**
 * Injected single-writer persistence executor (DIP — coding-guidelines § "Concurrency").
 *
 * Real SQLite writes must never run on the libGDX render thread (they would drop frames against the
 * 60 FPS budget). Every persistence write — autosave AND the settings/handedness write — is routed
 * through ONE instance of this port, which runs tasks **asynchronously, FIFO, on a single thread**.
 * Being the sole writer on a serial queue is what guarantees writes can't overlap or interleave
 * (single-writer rule), complementing the per-write SQLite transaction so a save is never corrupt.
 *
 * The `android` module backs this with a single-thread `ExecutorService`; JVM tests use a
 * synchronous (run-immediately) or capturing implementation. `core` never sees a platform type.
 */
interface SaveExecutor {
    /** Enqueue [task] to run asynchronously on the single writer thread; returns immediately. */
    fun execute(task: () -> Unit)

    /**
     * Block the calling thread until all previously-enqueued tasks have finished. Used on pause/exit
     * so the final autosave is durably written before the app is backgrounded or torn down.
     */
    fun flush()
}
