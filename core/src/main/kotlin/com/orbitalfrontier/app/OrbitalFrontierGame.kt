package com.orbitalfrontier.app

import app.cash.sqldelight.db.SqlDriver
import com.badlogic.gdx.Game
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.platform.SqlDriverFactory
import com.orbitalfrontier.save.AutosaveController
import com.orbitalfrontier.save.OrbitalFrontier
import com.orbitalfrontier.save.SqlDelightGameStateRepository
import com.orbitalfrontier.save.SqlDelightSettingsRepository
import com.orbitalfrontier.screen.PlayScreen
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.WorldState

/**
 * libGDX application entry point (bootstrap / wiring — package `app`, per coding-guidelines).
 *
 * Platform dependencies are constructor-injected (DIP): the [Logger], the [SqlDriverFactory], and
 * the single-writer [SaveExecutor] are supplied by the `android` launcher on device (and by tests/
 * other backends elsewhere), so `core` stays free of Android types (ADR 0001). `create()` builds
 * the persistence stack, resolves **New Game vs. Continue** (UC04 AC#5) by reading the save once at
 * startup before the render loop, builds the [AutosaveController], and hands the initial
 * [WorldState] + repositories + controller + executor to [PlayScreen]. `dispose()` runs a final
 * autosave (drained) so progress is durable on exit (UC04 AC#2).
 */
class OrbitalFrontierGame(
    private val logger: Logger,
    private val sqlDriverFactory: SqlDriverFactory,
    private val saveExecutor: SaveExecutor,
) : Game() {
    private var driver: SqlDriver? = null
    private var autosave: AutosaveController? = null
    private var playScreen: PlayScreen? = null

    override fun create() {
        val sqlDriver = sqlDriverFactory.create()
        driver = sqlDriver

        val database = OrbitalFrontier(sqlDriver)

        val settingsRepository = SqlDelightSettingsRepository(database, logger)
        settingsRepository.ensureInitialized()
        val handedness = settingsRepository.loadHandedness()

        // Resolve New Game vs. Continue once, up front (all reads happen here, before the render loop).
        val gameStateRepository = SqlDelightGameStateRepository(database, logger)
        val loaded = gameStateRepository.loadGameState()
        val worldState =
            if (loaded != null) {
                logger.info(TAG, "Continue: restored save (sector=${loaded.currentSector.value})")
                loaded
            } else {
                logger.info(TAG, "New Game: no save present; seeding defaults")
                WorldState(MvpSectorMap.START_SECTOR, ShipKinematics())
            }

        // The controller snapshots the *live* screen state on the render thread; bind the supplier to
        // the screen built just below (assigned before any render/autosave trigger fires).
        val controller =
            AutosaveController(
                repository = gameStateRepository,
                saveExecutor = saveExecutor,
                logger = logger,
                snapshotSupplier = { playScreen?.currentWorldState() ?: worldState },
            )
        autosave = controller

        val screen =
            PlayScreen(
                logger = logger,
                settingsRepository = settingsRepository,
                saveExecutor = saveExecutor,
                autosave = controller,
                initialHandedness = handedness,
                initialWorldState = worldState,
            )
        playScreen = screen

        logger.info(TAG, "Game created; handedness=$handedness")
        setScreen(screen)
    }

    override fun dispose() {
        // Final autosave + drain before teardown so the last frame of progress is durable (AC#2).
        try {
            autosave?.onPauseOrExit()
        } catch (e: Exception) {
            logger.error(TAG, "Failed final autosave on dispose; continuing teardown", e)
        }

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
