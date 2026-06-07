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
import com.orbitalfrontier.screen.StationHubScreen
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.Station
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
 *
 * It also owns the **screen lifecycle for docking** (UC05): the play screen and (while docked) a
 * [StationHubScreen]. The play screen calls back on a dock; this class opens the hub for that
 * station, and the hub calls back on undock to return to flight. libGDX `setScreen` only `hide()`s
 * the previous screen, so this class **disposes both screens explicitly** to avoid leaking GL
 * resources. On load, if the save says the ship is docked it resolves the station and opens the hub;
 * a stale/unresolvable dock station degrades gracefully to flight with a WARN (UC05 risk).
 */
class OrbitalFrontierGame(
    private val logger: Logger,
    private val sqlDriverFactory: SqlDriverFactory,
    private val saveExecutor: SaveExecutor,
) : Game() {
    private var driver: SqlDriver? = null
    private var autosave: AutosaveController? = null
    private var playScreen: PlayScreen? = null
    private var stationHubScreen: StationHubScreen? = null

    // Fixed authored sector graph (ADR 0004), built once and shared with the play screen so dock-state
    // resolution agrees across the game and the screen.
    private val sectorWorld: SectorWorld = MvpSectorMap.build()

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

        // Resolve the initial dock state (UC05 AC#4). A saved dock station that no longer resolves to a
        // Station in the saved sector (e.g. a stale id after a map change) degrades gracefully to
        // flight with a WARN rather than crashing — "never stranded" (coding-guidelines § errors).
        val resumedStation = resolveDockedStation(worldState)
        val initialWorldState =
            if (worldState.dockedStation != null && resumedStation == null) {
                logger.warn(
                    TAG,
                    "Saved dock station '${worldState.dockedStation?.value}' not found in sector " +
                        "'${worldState.currentSector.value}'; resuming undocked",
                )
                worldState.copy(dockedStation = null)
            } else {
                worldState
            }

        // The controller snapshots the *live* screen state on the render thread; bind the supplier to
        // the screen built just below (assigned before any render/autosave trigger fires).
        val controller =
            AutosaveController(
                repository = gameStateRepository,
                saveExecutor = saveExecutor,
                logger = logger,
                snapshotSupplier = { playScreen?.currentWorldState() ?: initialWorldState },
            )
        autosave = controller

        val screen =
            PlayScreen(
                logger = logger,
                settingsRepository = settingsRepository,
                saveExecutor = saveExecutor,
                autosave = controller,
                sectorWorld = sectorWorld,
                initialHandedness = handedness,
                initialWorldState = initialWorldState,
                onDocked = { station -> openStationHub(station) },
            )
        playScreen = screen

        logger.info(TAG, "Game created; handedness=$handedness")

        // Resume on the hub if the load left the ship docked at a resolvable station; otherwise fly.
        if (resumedStation != null) {
            logger.info(TAG, "Resuming docked at station ${resumedStation.id.value}")
            openStationHub(resumedStation)
        } else {
            setScreen(screen)
        }
    }

    /** The [Station] the saved [WorldState] is docked at, or null if undocked or unresolvable. */
    private fun resolveDockedStation(worldState: WorldState): Station? =
        worldState.dockedStation?.let { id ->
            sectorWorld.sectorOrNull(worldState.currentSector)?.station(id)
        }

    /** Open the station hub for [station], owning it so it can be disposed (libGDX only hide()s). */
    private fun openStationHub(station: Station) {
        val hub =
            StationHubScreen(
                logger = logger,
                stationName = station.displayName,
                onUndock = { returnToFlight() },
            )
        stationHubScreen = hub
        setScreen(hub)
    }

    /** Undock and return to the play screen, then dispose the (now hidden) hub to free its GL. */
    private fun returnToFlight() {
        playScreen?.undock()
        playScreen?.let { setScreen(it) }
        // setScreen above already hid the hub; dispose it now that it is no longer the active screen.
        stationHubScreen?.dispose()
        stationHubScreen = null
    }

    override fun dispose() {
        // Final autosave + drain before teardown so the last frame of progress is durable (AC#2).
        try {
            autosave?.onPauseOrExit()
        } catch (e: Exception) {
            logger.error(TAG, "Failed final autosave on dispose; continuing teardown", e)
        }

        super.dispose() // libGDX Game.dispose() only hide()s the active screen — it does not dispose it.

        // Dispose BOTH owned screens explicitly so neither leaks GL resources (the inactive one was
        // never hidden/disposed by libGDX, and the active one is only hidden by super.dispose()).
        try {
            playScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose play screen on shutdown", e)
        }
        playScreen = null
        try {
            stationHubScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose station hub screen on shutdown", e)
        }
        stationHubScreen = null

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
