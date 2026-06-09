package com.orbitalfrontier.app

import app.cash.sqldelight.db.SqlDriver
import com.badlogic.gdx.Game
import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.platform.SqlDriverFactory
import com.orbitalfrontier.save.AutosaveController
import com.orbitalfrontier.save.OrbitalFrontier
import com.orbitalfrontier.save.SqlDelightGameStateRepository
import com.orbitalfrontier.save.SqlDelightSettingsRepository
import com.orbitalfrontier.screen.HireScreen
import com.orbitalfrontier.screen.MissionBoardScreen
import com.orbitalfrontier.screen.OutfitScreen
import com.orbitalfrontier.screen.PlayScreen
import com.orbitalfrontier.screen.ShipyardScreen
import com.orbitalfrontier.screen.StationHubScreen
import com.orbitalfrontier.screen.StationWalkaroundScreen
import com.orbitalfrontier.screen.TradeScreen
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.station.StationBuildOrder
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.walkaround.StationInterior
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.Station
import com.orbitalfrontier.world.StationKind
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
    private var tradeScreen: TradeScreen? = null

    // UC19: the on-foot walk-around screen, kept alive across a shop visit so the avatar's position is
    // preserved when the trade desk closes and this screen is re-shown. Disposed only on re-board / dispose().
    private var stationWalkaroundScreen: StationWalkaroundScreen? = null
    private var outfitScreen: OutfitScreen? = null
    private var shipyardScreen: ShipyardScreen? = null
    private var hireScreen: HireScreen? = null
    private var missionBoardScreen: MissionBoardScreen? = null

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
                logger.info(TAG, "New Game: no save present; seeding defaults (credits=$STARTING_CREDITS)")
                // New game seeds a starting wallet (UC08) and the default single-starter-ship fleet
                // (UC09 — WorldState defaults to Fleet.starter()). A *migrated* save keeps its own
                // balance (the v5 -> v6 column backfills 0); only a brand-new game gets STARTING_CREDITS.
                WorldState(currentSector = MvpSectorMap.START_SECTOR, credits = STARTING_CREDITS)
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
                // TRADE opens the station trade desk for this station (UC08); the desk routes BUY/SELL
                // taps back to the play screen's pure Trading.resolve.
                onTrade = { openTradeDesk(station) },
                // OUTFIT / SHIPS open the outfitting desk and shipyard for this station (UC09); both route
                // taps back to the play screen's pure resolvers.
                onOutfit = { openOutfitDesk(station) },
                onShipyard = { openShipyard(station) },
                // CREW opens the crew-hire desk for this station (UC11); HIRE taps route back to the play
                // screen's pure Hiring.resolve.
                onCrew = { openHireDesk(station) },
                // MISSIONS opens the station mission board for this station (UC12); ACCEPT / TURN IN taps
                // route back to the play screen's pure Missions.resolve.
                onMissions = { openMissionBoard(station) },
                // "Refuel (H₂)" routes to the play screen's pure Refueling.resolve (UC07 AC#5) and
                // "Buy Fuel (credits)" to the pure StationRefuel.resolve (UC18); each returns a feedback
                // line the hub shows, and the hub re-reads the readout after the tap. All default to an
                // empty no-op if the play screen is gone.
                onRefuel = { playScreen?.refuel() ?: "" },
                onBuyFuel = { playScreen?.buyFuel() ?: "" },
                fuelStatus = { playScreen?.fuelStatusLine() ?: "" },
                // UC14: the station's owning faction (cosmetic), resolved from the authored catalog.
                factionName = station.factionId?.let { Factions.byId(it)?.displayName },
                // UC15: BUILD founds a personal station via the play screen's pure StationBuilder. Per
                // ADR 0014 there is no dedicated build screen yet, so the action fires a default
                // FoundStation order (the first catalogued module) directly; the full build/edit UI
                // (module choice, expansion) is deferred. The row only shows at a build-capable station.
                onBuild = { playScreen?.build(StationBuildOrder.FoundStation(StationModuleCatalog.MVP.all.first().id)) },
                buildsStations = station.buildsStations,
                // UC19: EXIT SHIP opens the on-foot walk-around for this station. Purely additive — the
                // hub and the docked WorldState are untouched; re-boarding re-shows this same hub (AC#1/#7).
                onDisembark = { openWalkaround(station) },
            )
        stationHubScreen = hub
        setScreen(hub)
    }

    /**
     * Open the on-foot walk-around for [station] (UC19), owning it so it can be disposed (libGDX only
     * hide()s the hub). The screen is intentionally decoupled from world/save state: it takes only a
     * transient [StationInterior] and two callbacks — RE-BOARD returns to the (untouched) hub and
     * INTERACT opens the existing shop. The walk-around instance is kept alive across a shop visit so
     * the avatar's position is preserved; it is disposed only on re-board and in [dispose].
     */
    private fun openWalkaround(station: Station) {
        val walkaround =
            StationWalkaroundScreen(
                logger = logger,
                interior = StationInterior.prototype(),
                onReboard = { returnToHubFromFoot() },
                onInteract = { openShopFromWalkaround(station) },
            )
        stationWalkaroundScreen = walkaround
        setScreen(walkaround)
    }

    /**
     * Re-board from the on-foot mode back to the station hub (UC19 AC#7): re-show the kept-alive hub
     * and dispose the now-hidden walk-around. The docked [WorldState] was never touched, so the normal
     * docked state is restored exactly.
     */
    private fun returnToHubFromFoot() {
        stationHubScreen?.let { setScreen(it) }
        stationWalkaroundScreen?.dispose()
        stationWalkaroundScreen = null
    }

    /**
     * Open the EXISTING station trade desk from on foot (UC19 AC#6): the same [TradeScreen] reached
     * from the hub menus — no new shop screen. BACK returns to the walk-around (not the hub), re-showing
     * the kept-alive avatar where it stood. The walk-around screen is left alive (only hidden) so its
     * avatar position survives the visit.
     */
    private fun openShopFromWalkaround(station: Station) {
        val desk =
            TradeScreen(
                logger = logger,
                stationName = station.displayName,
                market = station.market,
                creditsSupplier = { playScreen?.creditsBalance() ?: 0L },
                cargoSupplier = { playScreen?.cargoSnapshot() ?: Cargo.empty() },
                onTrade = { order: TradeOrder -> playScreen?.trade(order) },
                onBack = { returnToWalkaround() },
            )
        tradeScreen = desk
        setScreen(desk)
    }

    /**
     * Return from the on-foot shop visit to the walk-around (UC19): re-show the kept-alive walk-around
     * instance (avatar position preserved — never reconstructed) and dispose the now-hidden trade desk.
     */
    private fun returnToWalkaround() {
        stationWalkaroundScreen?.let { setScreen(it) }
        tradeScreen?.dispose()
        tradeScreen = null
    }

    /**
     * Open the outfitting desk for [station] (UC09 AC#2/#3/#4). INSTALL / REMOVE taps route to
     * [PlayScreen.outfit] (pure [com.orbitalfrontier.outfit.Outfitting]); the desk reads the live fleet
     * + credits back for its readouts. BACK returns to the hub.
     */
    private fun openOutfitDesk(station: Station) {
        val desk =
            OutfitScreen(
                logger = logger,
                stationName = station.displayName,
                outfitMarket = station.outfitMarket,
                isJunkyard = station.kind == StationKind.JUNKYARD,
                creditsSupplier = { playScreen?.creditsBalance() ?: 0L },
                fleetSupplier = { playScreen?.fleetSnapshot() ?: Fleet.starter() },
                onOutfit = { order -> playScreen?.outfit(order) },
                onBack = { returnToHub() },
            )
        outfitScreen = desk
        setScreen(desk)
    }

    /**
     * Open the shipyard / ship-switch screen for [station] (UC09 AC#5). BUY / SWITCH taps route to
     * [PlayScreen.fleetCommand] (pure [com.orbitalfrontier.ship.FleetResolver]); the screen reads the
     * live fleet + credits back for its readouts. BACK returns to the hub.
     */
    private fun openShipyard(station: Station) {
        val desk =
            ShipyardScreen(
                logger = logger,
                stationName = station.displayName,
                shipyard = station.shipyard,
                creditsSupplier = { playScreen?.creditsBalance() ?: 0L },
                fleetSupplier = { playScreen?.fleetSnapshot() ?: Fleet.starter() },
                onFleet = { order -> playScreen?.fleetCommand(order) },
                onBack = { returnToHub() },
            )
        shipyardScreen = desk
        setScreen(desk)
    }

    /**
     * Open the crew-hire desk for [station] (UC11 AC#2), owning it so it can be disposed (libGDX only
     * hide()s the hub). HIRE taps route to [PlayScreen.hire] (pure [com.orbitalfrontier.crew.Hiring]);
     * the desk reads the live credits + active-ship crew/capacity + turret operability back from the
     * play screen for its readouts. BACK returns to the hub.
     */
    private fun openHireDesk(station: Station) {
        val desk =
            HireScreen(
                logger = logger,
                stationName = station.displayName,
                creditsSupplier = { playScreen?.creditsBalance() ?: 0L },
                crewSupplier = { playScreen?.activeCrew() ?: 0 },
                crewCapacitySupplier = { playScreen?.activeCrewCapacity() ?: 0 },
                turretOperableSupplier = { playScreen?.turretsOperable() ?: false },
                onHire = { order: HireOrder -> playScreen?.hire(order) },
                onBack = { returnToHub() },
            )
        hireScreen = desk
        setScreen(desk)
    }

    /**
     * Open the station mission board for [station] (UC12 AC#2/#3), owning it so it can be disposed
     * (libGDX only hide()s the hub). ACCEPT / TURN IN taps route to [PlayScreen.applyMissionOrder] (pure
     * [com.orbitalfrontier.mission.Missions]); the board reads the live available offers, active
     * missions and credits back from the play screen for its rows. BACK returns to the hub.
     */
    private fun openMissionBoard(station: Station) {
        val board =
            MissionBoardScreen(
                logger = logger,
                stationName = station.displayName,
                availableSupplier = { playScreen?.stationMissionBoard() ?: emptyList() },
                activeSupplier = { playScreen?.activeMissions() ?: emptyList() },
                creditsSupplier = { playScreen?.creditsBalance() ?: 0L },
                onMissionOrder = { order: MissionOrder -> playScreen?.applyMissionOrder(order) },
                onBack = { returnToHub() },
                // UC14: reputation-gated (locked) offers + the current standing readout.
                lockedSupplier = { playScreen?.lockedStationOffers() ?: emptyList() },
                reputationSupplier = { playScreen?.reputationSnapshot() ?: Reputation.EMPTY },
            )
        missionBoardScreen = board
        setScreen(board)
    }

    /**
     * Open the trade desk for [station] (UC08), owning it so it can be disposed (libGDX only hide()s
     * the hub). BUY/SELL taps route to [PlayScreen.trade] (pure [com.orbitalfrontier.economy.Trading]);
     * the desk reads the live credits + cargo back from the play screen for its readouts. BACK returns
     * to the hub.
     */
    private fun openTradeDesk(station: Station) {
        val desk =
            TradeScreen(
                logger = logger,
                stationName = station.displayName,
                market = station.market,
                creditsSupplier = { playScreen?.creditsBalance() ?: 0L },
                cargoSupplier = { playScreen?.cargoSnapshot() ?: Cargo.empty() },
                onTrade = { order: TradeOrder -> playScreen?.trade(order) },
                onBack = { returnToHub() },
            )
        tradeScreen = desk
        setScreen(desk)
    }

    /** Undock and return to the play screen, then dispose the (now hidden) hub to free its GL. */
    private fun returnToFlight() {
        playScreen?.undock()
        playScreen?.let { setScreen(it) }
        // setScreen above already hid the hub; dispose it now that it is no longer the active screen.
        stationHubScreen?.dispose()
        stationHubScreen = null
    }

    /** Return from a sub-desk (trade / outfit / shipyard) to the station hub, disposing the hidden desk(s). */
    private fun returnToHub() {
        stationHubScreen?.let { setScreen(it) }
        // setScreen above already hid the active desk; dispose every sub-desk now that none is active.
        tradeScreen?.dispose()
        tradeScreen = null
        outfitScreen?.dispose()
        outfitScreen = null
        shipyardScreen?.dispose()
        shipyardScreen = null
        hireScreen?.dispose()
        hireScreen = null
        missionBoardScreen?.dispose()
        missionBoardScreen = null
    }

    override fun dispose() {
        // Final autosave + drain before teardown so the last frame of progress is durable (AC#2).
        try {
            autosave?.onPauseOrExit()
        } catch (e: Exception) {
            logger.error(TAG, "Failed final autosave on dispose; continuing teardown", e)
        }

        super.dispose() // libGDX Game.dispose() only hide()s the active screen — it does not dispose it.

        // Dispose ALL owned screens explicitly so none leaks GL resources (an inactive one was never
        // hidden/disposed by libGDX, and the active one is only hidden by super.dispose()).
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
            stationWalkaroundScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose station walk-around screen on shutdown", e)
        }
        stationWalkaroundScreen = null
        try {
            tradeScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose trade screen on shutdown", e)
        }
        tradeScreen = null
        try {
            outfitScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose outfit screen on shutdown", e)
        }
        outfitScreen = null
        try {
            shipyardScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose shipyard screen on shutdown", e)
        }
        shipyardScreen = null
        try {
            hireScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose hire screen on shutdown", e)
        }
        hireScreen = null
        try {
            missionBoardScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose mission board screen on shutdown", e)
        }
        missionBoardScreen = null

        try {
            driver?.close()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to close SQL driver on shutdown", e)
        }
        driver = null
    }

    private companion object {
        const val TAG = "App"

        /**
         * Credits a brand-new game starts with. The permanent default starting balance (UC17): 50k is
         * enough to exercise trading, outfitting, and refuelling without grinding first — not a debug/dev
         * toggle. Migrated saves keep their own balance (the v5 -> v6 column backfills 0), so this applies
         * only to a fresh game. [TUNE]
         */
        const val STARTING_CREDITS: Long = 50_000L
    }
}
