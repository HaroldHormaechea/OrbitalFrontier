package com.orbitalfrontier.android

import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * On-device [SaveExecutor]: a single daemon thread that serializes every persistence write (ADR
 * 0003, coding-guidelines § "Concurrency"). One instance is shared by autosave and the settings
 * write, so SQLite I/O never runs on the render thread and writes can't interleave (single-writer).
 *
 * Lifecycle is owned by [AndroidLauncher], which [dispose]s it after libGDX has torn the game down
 * (the game's final autosave [flush] has already drained by then).
 */
class AndroidSaveExecutor(
    private val logger: Logger,
) : SaveExecutor, Disposable {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "save-writer").apply { isDaemon = true }
        }

    override fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                // Repository writes log their own failures and don't throw; this guards the writer
                // thread against any truly unexpected error so the queue keeps draining.
                logger.error(TAG, "Unexpected error running a save task", t)
            }
        }
    }

    override fun flush() {
        // FIFO on one thread: when this empty task finishes, all prior tasks have finished too.
        try {
            executor.submit { }.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(TAG, "Interrupted while flushing save executor", e)
        } catch (e: Exception) {
            logger.error(TAG, "Failed to flush save executor", e)
        }
    }

    override fun dispose() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn(TAG, "Save executor did not drain within ${SHUTDOWN_TIMEOUT_SECONDS}s; forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val TAG = "Save"
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
