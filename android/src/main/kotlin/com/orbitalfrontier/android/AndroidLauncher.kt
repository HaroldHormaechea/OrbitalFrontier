package com.orbitalfrontier.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.orbitalfrontier.BuildConfig
import com.orbitalfrontier.app.OrbitalFrontierGame

/**
 * Android entry point. Builds the platform implementations of the injected ports
 * ([AndroidLogger], [AndroidSqlDriverFactory], [AndroidSaveExecutor]) and hands them to the
 * platform-agnostic [OrbitalFrontierGame] (ADR 0001 / 0003). The `applicationContext` is used for
 * the SQLite driver so it is not tied to the Activity lifecycle.
 *
 * The single-writer [AndroidSaveExecutor] is owned here: it is disposed in [onDestroy] after
 * `super.onDestroy()` has driven libGDX's teardown (so the game's final, drained autosave has
 * already run) — only then is the writer thread shut down.
 */
class AndroidLauncher : AndroidApplication() {
    private lateinit var saveExecutor: AndroidSaveExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configuration =
            AndroidApplicationConfiguration().apply {
                useImmersiveMode = true
                useAccelerometer = false
                useCompass = false
            }

        val logger = AndroidLogger()
        val sqlDriverFactory = AndroidSqlDriverFactory(applicationContext)
        saveExecutor = AndroidSaveExecutor(logger)
        // UC38: the real wall clock, used by the save-slot repository to stamp each slot's last-saved time.
        val clock = AndroidClock()

        // UC25: BuildConfig.DEBUG is true only for the debug variant, so the debug-only point-and-go
        // navigation aid is armed-capable on debug builds and entirely inert on release.
        initialize(
            OrbitalFrontierGame(logger, sqlDriverFactory, saveExecutor, clock, debug = BuildConfig.DEBUG),
            configuration,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        saveExecutor.dispose()
    }
}
