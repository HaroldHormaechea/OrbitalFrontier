package com.orbitalfrontier.app

import app.cash.sqldelight.db.SqlDriver
import com.badlogic.gdx.Game
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SqlDriverFactory
import com.orbitalfrontier.save.OrbitalFrontier
import com.orbitalfrontier.save.SqlDelightSettingsRepository
import com.orbitalfrontier.screen.PlayScreen

/**
 * libGDX application entry point (bootstrap / wiring — package `app`, per coding-guidelines).
 *
 * Platform dependencies are constructor-injected (DIP): the [Logger] and the [SqlDriverFactory]
 * are supplied by the `android` launcher on device (and by tests/other backends elsewhere), so
 * `core` stays free of Android types (ADR 0001). `create()` builds the persistence stack, loads
 * the saved handedness, and hands off to [PlayScreen].
 */
class OrbitalFrontierGame(
    private val logger: Logger,
    private val sqlDriverFactory: SqlDriverFactory,
) : Game() {
    private var driver: SqlDriver? = null

    override fun create() {
        val sqlDriver = sqlDriverFactory.create()
        driver = sqlDriver

        val database = OrbitalFrontier(sqlDriver)
        val settingsRepository = SqlDelightSettingsRepository(database, logger)
        settingsRepository.ensureInitialized()
        val handedness = settingsRepository.loadHandedness()

        logger.info(TAG, "Game created; loaded handedness=$handedness")
        setScreen(PlayScreen(logger, settingsRepository, handedness))
    }

    override fun dispose() {
        super.dispose() // disposes the active screen
        try {
            driver?.close()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to close SQL driver on shutdown", e)
        }
        driver = null
    }

    private companion object {
        const val TAG = "App"
    }
}
