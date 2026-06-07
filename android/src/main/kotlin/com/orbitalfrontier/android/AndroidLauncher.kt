package com.orbitalfrontier.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.orbitalfrontier.app.OrbitalFrontierGame

/**
 * Android entry point. Builds the platform implementations of the injected ports
 * ([AndroidLogger], [AndroidSqlDriverFactory]) and hands them to the platform-agnostic
 * [OrbitalFrontierGame] (ADR 0001 / 0003). The `applicationContext` is used for the SQLite
 * driver so it is not tied to the Activity lifecycle.
 */
class AndroidLauncher : AndroidApplication() {
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

        initialize(OrbitalFrontierGame(logger, sqlDriverFactory), configuration)
    }
}
