package com.orbitalfrontier.platform

/**
 * Injected logging port (DIP — coding-guidelines "Logging conventions").
 *
 * `core` logs through this interface only; the `android` module backs it with
 * `Gdx.app.log` / Android `Log`, and tests use a no-op or capturing implementation. Keeps
 * `core` free of Android/libGDX platform types (ADR 0001).
 *
 * Levels follow the guidelines: ERROR (always, with the throwable), WARN (handled-but-
 * unexpected), INFO (significant lifecycle events), DEBUG (verbose, disabled in release).
 * The first argument is a per-system tag (e.g. "Save", "Movement", "Settings").
 */
interface Logger {
    fun debug(
        tag: String,
        message: String,
    )

    fun info(
        tag: String,
        message: String,
    )

    fun warn(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}

/**
 * Logger that discards everything. Safe default and convenient for headless contexts; tests
 * may use their own capturing logger instead.
 */
object NoOpLogger : Logger {
    override fun debug(
        tag: String,
        message: String,
    ) = Unit

    override fun info(
        tag: String,
        message: String,
    ) = Unit

    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) = Unit
}
