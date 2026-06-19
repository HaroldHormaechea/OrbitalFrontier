package com.orbitalfrontier.app

import app.cash.sqldelight.db.SqlDriver
import com.badlogic.gdx.Game
import com.orbitalfrontier.audio.MusicTrack
import com.orbitalfrontier.audio.Sfx
import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.menu.SaveSlotModel
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.Clock
import com.orbitalfrontier.platform.FixedClock
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.NoOpAudioService
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.platform.SqlDriverFactory
import com.orbitalfrontier.render.GameAssets
import com.orbitalfrontier.render.LibGdxAudioService
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.save.AutosaveController
import com.orbitalfrontier.save.GameStateRepository
import com.orbitalfrontier.save.OrbitalFrontier
import com.orbitalfrontier.save.SaveSlotRepository
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.save.SlotId
import com.orbitalfrontier.save.SqlDelightGameStateRepository
import com.orbitalfrontier.save.SqlDelightSettingsRepository
import com.orbitalfrontier.screen.HireScreen
import com.orbitalfrontier.screen.MainMenuScreen
import com.orbitalfrontier.screen.MissionBoardScreen
import com.orbitalfrontier.screen.OutfitScreen
import com.orbitalfrontier.screen.PlayScreen
import com.orbitalfrontier.screen.SaveSlotScreen
import com.orbitalfrontier.screen.SettingsScreen
import com.orbitalfrontier.screen.ShipyardScreen
import com.orbitalfrontier.screen.StationHubScreen
import com.orbitalfrontier.screen.StationWalkaroundScreen
import com.orbitalfrontier.screen.TradeScreen
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning
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
 * the persistence stack, reads the save once up front, and shows the **main menu** ([MainMenuScreen],
 * UC21) before any gameplay: Start begins a new game (double-confirming a wipe when a save exists) and
 * Continue resumes the save. Picking either runs [enterGame], which resolves the initial dock state
 * (UC05), builds the [AutosaveController], and hands the [WorldState] + repositories + controller +
 * executor to [PlayScreen]. `dispose()` runs a final autosave (drained) so progress is durable on exit
 * (UC04 AC#2). The "New Game vs. Continue" choice (UC04 AC#5) is now an explicit player decision at the
 * menu rather than an automatic load-or-seed at startup.
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
    // UC38: wall-clock port used by the save-slot repository to stamp each slot's last-saved time. The
    // Android launcher passes a real AndroidClock; defaults to FixedClock so non-Android backends / tests
    // (and the time-free pure simulation) need not wire it (ADR 0006 — the clock lives at the save boundary).
    private val clock: Clock = FixedClock,
    // UC25: debug-build flag (the launcher passes BuildConfig.DEBUG). Forwarded to PlayScreen, which
    // wires the debug-only point-and-go navigation aid only when true. Defaults false so non-Android
    // backends / tests stay release-safe.
    private val debug: Boolean = false,
) : Game() {
    private var driver: SqlDriver? = null
    private var autosave: AutosaveController? = null

    // UC27: the single shared design-system art atlas (AC#1). Loaded once on the GL thread in create()
    // and disposed exactly once in dispose(); every screen/renderer/skin gets a BORROWED reference.
    private var gameAssets: GameAssets? = null

    // UC31: the single audio service. Defaults to the no-op (headless/test-safe, and the value before the
    // GL-thread libGDX service is built in create()); replaced once with the real LibGdxAudioService and
    // disposed exactly once in dispose() (single owner, single dispose — like gameAssets).
    private var audio: AudioService = NoOpAudioService

    private var mainMenuScreen: MainMenuScreen? = null

    // UC37: the standalone main-menu settings screen, built on demand from the menu's SETTINGS button and
    // owned here so it can be disposed (libGDX only hide()s the previous screen).
    private var settingsScreen: SettingsScreen? = null
    private var playScreen: PlayScreen? = null
    private var stationHubScreen: StationHubScreen? = null
    private var tradeScreen: TradeScreen? = null

    // UC21: persistence + settings captured at create() so the menu's Start/Continue callbacks can run
    // enterGame() later (the menu defers New-Game-vs-Continue from startup to a player choice). Set once
    // in create(), before the menu can fire a callback.
    private lateinit var gameStateRepository: GameStateRepository

    // UC38: slot-management capability (list / rename / delete / active-slot pointer). Backed by the same
    // SqlDelightGameStateRepository instance as [gameStateRepository] (it realises both interfaces).
    private lateinit var saveSlotRepository: SaveSlotRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var handedness: Handedness

    // UC38: the slot the current session targets — the autosave writes here and Continue resumes it. Read
    // from meta.active_slot_id in create(); updated (and persisted via setActiveSlot) on new-game-into /
    // load / save-into a slot. The AutosaveController reads it through a supplier so autosave always follows.
    private var activeSlot: SlotId = SlotId.LEGACY

    // UC38: the save/load slot screen, built on demand (LOAD from the main menu, SAVE from the pause
    // overlay) and owned here so it can be disposed (libGDX only hide()s the previous screen).
    private var saveSlotScreen: SaveSlotScreen? = null

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
        // UC27: load the shared art atlas once, here on the GL thread (a live GL context exists by
        // create()). Borrowed by every screen/renderer/skin; disposed once in dispose() (AC#1).
        gameAssets = GameAssets.load()

        val sqlDriver = sqlDriverFactory.create()
        driver = sqlDriver

        val database = OrbitalFrontier(sqlDriver)

        val settings = SqlDelightSettingsRepository(database, logger)
        settings.ensureInitialized()
        settingsRepository = settings
        handedness = settings.loadHandedness()

        // UC37: restore the persisted UI scale into the global knob BEFORE any screen builds, so the very
        // first screen (the main menu) lays out at the player's chosen scale. Every screen's ScreenViewport
        // reads UiScale.factor at construction (ADR 0015 / ADR 0025).
        UiScale.set(settings.loadUiScale())

        // UC31: build the real audio service on the GL/audio thread (alive by create()) and apply the
        // persisted preferences before any cue/music plays, so audio honours the saved mute + volumes
        // immediately (AC#3). The field stays NoOpAudioService until this point, so an early failure
        // leaves a safe no-op rather than a half-built service.
        val audioService = LibGdxAudioService.load(logger)
        val audioSettings = settings.loadAudioSettings()
        audioService.setMasterMuted(audioSettings.masterMuted)
        audioService.setSfxVolume(audioSettings.sfxVolume)
        audioService.setMusicVolume(audioSettings.musicVolume)
        audio = audioService

        // UC31: wire the shared UI-tap cue so every menu/hub/desk screen's buttons click audibly (AC#1).
        // The hook is read at tap time, so screens built later still pick it up; reset in dispose().
        OrbitalUiSkin.uiTapSound = { audio.play(Sfx.UI_TAP) }

        // Read the save once, up front, to decide whether Continue is available (UC21 AC#4). The actual
        // New-Game-vs-Continue decision is now the player's at the menu, not an automatic load-or-seed:
        // a usable save (non-null) enables Continue and makes Start double-confirm before wiping; a
        // null one (no save, or a corrupt save the repository degraded to null) disables Continue and
        // lets Start begin immediately.
        val repository = SqlDelightGameStateRepository(database, logger, clock)
        gameStateRepository = repository
        saveSlotRepository = repository
        // UC38: resume the slot the player last played / saved into (meta.active_slot_id; legacy slot 0 on a
        // fresh or migrated DB — the legacy single autosave appears as slot 0, AC#3).
        activeSlot = repository.activeSlot()
        val loaded = repository.loadGameState(activeSlot)

        // Show the main menu first, on every launch (UC21 AC#1/#5). Start / Continue route into the
        // game via enterGame(); only then is the play screen / hub built.
        val menu = buildMainMenu(loaded)
        mainMenuScreen = menu
        setScreen(menu)

        logger.info(TAG, "Game created; handedness=$handedness; menu shown")
    }

    /**
     * Build the main menu over the given [loaded] save snapshot (UC21; reused by UC32's quit-to-main-menu).
     * Continue is enabled iff a usable save exists; Start wipes any save and seeds a fresh game. The two
     * callbacks defer the New-Game-vs-Continue decision to the player and route into [enterGame].
     */
    private fun buildMainMenu(loaded: WorldState?): MainMenuScreen =
        MainMenuScreen(
            logger = logger,
            continueEnabled = loaded != null,
            // Continue: resume the active slot's save (AC#2; UC38 — the slot is meta.active_slot_id). loaded
            // is non-null here (Continue is only enabled when it is), so the !! is safe.
            onContinue = {
                logger.info(TAG, "Continue: restored slot ${activeSlot.value} (sector=${loaded!!.currentSector.value})")
                enterGame(loaded, activeSlot)
            },
            // Start: begin a brand-new game in the ACTIVE slot (AC#3; UC38 — the slot Continue would resume).
            // clearSave(slot) is UNCONDITIONAL — a no-op on an empty slot and a full wipe on a usable OR
            // corrupt one; it is decoupled from the warnings (the menu model gates those on whether a save
            // exists). Safe at menu time: there is no AutosaveController yet (the play screen, hence
            // autosaving, is built only in enterGame() below), so there is no concurrent writer to race.
            onStartNewGame = {
                logger.info(TAG, "New Game: wiping slot ${activeSlot.value}; seeding defaults (credits=$STARTING_CREDITS)")
                newGameIntoSlot(activeSlot)
            },
            // UC37 AC#4: SETTINGS opens the standalone settings screen over the menu.
            onOpenSettings = { openSettings() },
            // UC38: LOAD GAME opens the save-slot screen in LOAD mode (resume / new-game-into / delete a slot).
            onLoadGame = { openSaveSlots(SaveSlotModel.Mode.LOAD, returnToMenuOnBack = true) },
        )

    /**
     * Begin a brand-new game in [slot] (UC38): make it the active slot, wipe any existing save there, and
     * enter gameplay with the freshly-seeded [WorldState] (a starting wallet — UC08 — and the default
     * single-starter-ship fleet — UC09). Used by the menu's Start and by "new game into an empty slot" on
     * the load screen (AC#2). The slot's player-facing name is set lazily on the first save (its default).
     */
    private fun newGameIntoSlot(slot: SlotId) {
        activeSlot = slot
        saveSlotRepository.setActiveSlot(slot)
        gameStateRepository.clearSave(slot)
        enterGame(WorldState(currentSector = MvpSectorMap.START_SECTOR, credits = STARTING_CREDITS), slot)
    }

    /**
     * Open the save/load slot screen (UC38 AC#1/#2), owning it so it can be disposed (libGDX only hide()s
     * the previous screen). In LOAD [mode] (from the main menu) tapping a slot resumes it, an empty slot
     * starts a new game, and DELETE removes a slot; in SAVE [mode] (from the pause overlay) tapping a slot
     * persists the live game into it (with an overwrite warning for an occupied slot). BACK returns to the
     * main menu when [returnToMenuOnBack], else to the (paused) play screen.
     */
    private fun openSaveSlots(
        mode: SaveSlotModel.Mode,
        returnToMenuOnBack: Boolean,
    ) {
        val screen =
            SaveSlotScreen(
                logger = logger,
                mode = mode,
                slotsSupplier = { saveSlotRepository.listSlots() },
                onLoad = { slot -> loadSlot(slot) },
                onDelete = { slot -> saveSlotRepository.deleteSlot(slot) },
                onSave = { slot -> saveIntoSlot(slot) },
                onNewGameInto = { slot -> newGameIntoSlot(slot) },
                onBack = { if (returnToMenuOnBack) returnToMenuFromSaveSlots() else returnToPlayFromSaveSlots() },
            )
        saveSlotScreen = screen
        setScreen(screen)
    }

    /** Resume the chosen [slot] (UC38 AC#2): make it active and enter gameplay with its loaded snapshot. */
    private fun loadSlot(slot: SlotId) {
        val loaded = gameStateRepository.loadGameState(slot)
        if (loaded == null) {
            logger.warn(TAG, "Load slot ${slot.value} found no save; ignoring")
            return
        }
        activeSlot = slot
        saveSlotRepository.setActiveSlot(slot)
        disposeSaveSlots()
        logger.info(TAG, "Loaded slot ${slot.value} (sector=${loaded.currentSector.value})")
        enterGame(loaded, slot)
    }

    /**
     * Manual save of the live game into [slot] (UC38 AC#2), reached from the pause overlay (SAVE mode). The
     * game stays paused. Re-points the autosave at [slot] (save-as) and flushes a durable write through the
     * single-writer executor, then returns to the (paused) play screen. The snapshot is taken on the render
     * thread; the write runs on the executor (mirrors the autosave path).
     */
    private fun saveIntoSlot(slot: SlotId) {
        val screen = playScreen
        if (screen == null) {
            logger.warn(TAG, "Save into slot ${slot.value} with no play screen; ignoring")
            return
        }
        activeSlot = slot
        saveSlotRepository.setActiveSlot(slot)
        val snapshot = screen.currentWorldState()
        saveExecutor.execute { gameStateRepository.saveGameState(slot, snapshot) }
        saveExecutor.flush()
        logger.info(TAG, "Saved live game into slot ${slot.value}")
        returnToPlayFromSaveSlots()
    }

    /** Return from the save-slot screen to the (kept-alive) main menu, disposing the now-hidden slot screen. */
    private fun returnToMenuFromSaveSlots() {
        mainMenuScreen?.let { setScreen(it) }
        disposeSaveSlots()
    }

    /** Return from the save-slot screen to the (kept-alive, paused) play screen, disposing the slot screen. */
    private fun returnToPlayFromSaveSlots() {
        playScreen?.let { setScreen(it) }
        disposeSaveSlots()
    }

    /** Dispose + null the save-slot screen (libGDX only hide()s the previous screen). */
    private fun disposeSaveSlots() {
        saveSlotScreen?.dispose()
        saveSlotScreen = null
    }

    /**
     * Open the main-menu settings screen (UC37 AC#4), owning it so it can be disposed (libGDX only hide()s
     * the menu). It hosts the SAME shared [com.orbitalfrontier.screen.SettingsPanel] the in-flight overlay
     * does, seeded from the persisted settings; BACK returns to the (kept-alive) menu. UI scale changes
     * apply live to the settings screen's own viewport; handedness + joystick tuning are persisted here and
     * re-read fresh by [enterGame] when a game is next entered.
     */
    private fun openSettings() {
        val screen =
            SettingsScreen(
                logger = logger,
                repository = settingsRepository,
                saveExecutor = saveExecutor,
                audio = audio,
                initialHandedness = settingsRepository.loadHandedness(),
                initialAudio = settingsRepository.loadAudioSettings(),
                initialJoystickTuning = settingsRepository.loadJoystickTuning(),
                initialUiScale = settingsRepository.loadUiScale(),
                onBack = { returnToMenuFromSettings() },
            )
        settingsScreen = screen
        setScreen(screen)
    }

    /**
     * Return from the settings screen to the (kept-alive) main menu (UC37): re-show the menu and dispose the
     * now-hidden settings screen to free its GL. The menu instance is unchanged — settings never touch save
     * state, so Continue/Start availability is identical.
     */
    private fun returnToMenuFromSettings() {
        mainMenuScreen?.let { setScreen(it) }
        settingsScreen?.dispose()
        settingsScreen = null
    }

    /**
     * Enter gameplay with [worldState] — the shared tail of both Start (a fresh seed) and Continue (the
     * loaded save). Resolves the initial dock state (UC05 AC#4) — a saved dock station that no longer
     * resolves to a Station in the saved sector degrades gracefully to flight with a WARN, never
     * crashing ("never stranded") — builds the [AutosaveController] bound to the live play screen, then
     * shows the hub (if resumed docked) or the play screen.
     */
    private fun enterGame(
        worldState: WorldState,
        slot: SlotId,
    ) {
        // UC38: bind this session to [slot] — the autosave writes here (via the controller's slotSupplier)
        // and a later save-as updates it. The active-slot pointer was already persisted by the caller
        // (newGameIntoSlot / loadSlot / Continue's create()-read), so this just tracks it in memory.
        activeSlot = slot
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
                // UC38: the autosave follows the live active slot, so a save-as to another slot re-targets it.
                slotSupplier = { activeSlot },
            )
        autosave = controller

        // UC37: re-read handedness + joystick tuning FRESH here — the create()-cached [handedness] can be
        // stale if the player changed it on the main-menu settings screen since launch, and joystick tuning
        // is only ever read at flight start. UI scale is already live in the global [UiScale] knob.
        val currentHandedness = settingsRepository.loadHandedness()
        handedness = currentHandedness
        val joystickTuning: JoystickTuning = settingsRepository.loadJoystickTuning()

        val screen =
            PlayScreen(
                logger = logger,
                settingsRepository = settingsRepository,
                saveExecutor = saveExecutor,
                autosave = controller,
                sectorWorld = sectorWorld,
                gameAssets = requireNotNull(gameAssets) { "GameAssets must be loaded in create() before enterGame()" },
                initialHandedness = currentHandedness,
                initialJoystickTuning = joystickTuning,
                initialWorldState = initialWorldState,
                onDocked = { station -> openStationHub(station) },
                // UC32: the pause overlay's Quit button flushes a durable autosave (in PlayScreen) and then
                // hands back here to rebuild + show the main menu and dispose the play screen.
                onQuitToMainMenu = { returnToMainMenu() },
                // UC38: the pause overlay's SAVE button opens the save-slot screen in SAVE mode (manual save).
                // The game stays paused; BACK / a save returns to this same (paused) play screen.
                onOpenSaveSlots = { openSaveSlots(SaveSlotModel.Mode.SAVE, returnToMenuOnBack = false) },
                audio = audio,
                debug = debug,
            )
        playScreen = screen

        // Resume on the hub if the load left the ship docked at a resolvable station; otherwise fly.
        if (resumedStation != null) {
            logger.info(TAG, "Resuming docked at station ${resumedStation.id.value}")
            openStationHub(resumedStation)
        } else {
            // UC31: flight ambience while roaming (AC#2). Idempotent, so re-entering flight never restarts it.
            audio.playMusic(MusicTrack.FLIGHT)
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
        // UC31: switch to the station ambience (AC#2). Idempotent, so the on-foot walk-around, sub-desks
        // and hub-return (which never call playMusic) let STATION span them gap-free until undock.
        audio.playMusic(MusicTrack.STATION)
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
                gameAssets = requireNotNull(gameAssets) { "GameAssets must be loaded before openWalkaround()" },
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

    /**
     * Quit from flight back to the main menu (UC32 AC#4). The play screen has already flushed a durable
     * autosave before invoking this, so progress is safe and Continue is available. Stops the flight music,
     * rebuilds the menu from a fresh load, disposes the previous menu BEFORE reassigning (single-owner
     * discipline — libGDX only hide()s screens), shows it, then disposes the play screen and nulls the field
     * so its GL is released and no stale snapshot can be taken; a later Continue/New Game builds a fresh one.
     */
    private fun returnToMainMenu() {
        audio.stopMusic()
        // UC38: re-resolve the active slot (a save-as may have moved it) and rebuild the menu from its save.
        activeSlot = saveSlotRepository.activeSlot()
        val loaded = gameStateRepository.loadGameState(activeSlot)
        val menu = buildMainMenu(loaded)
        mainMenuScreen?.dispose()
        mainMenuScreen = menu
        setScreen(menu)
        playScreen?.dispose()
        playScreen = null
        logger.info(TAG, "Quit to main menu; play screen disposed (continueEnabled=${loaded != null})")
    }

    /** Undock and return to the play screen, then dispose the (now hidden) hub to free its GL. */
    private fun returnToFlight() {
        // UC31: back to flight ambience (AC#2). Idempotent — a no-op if FLIGHT is somehow already current.
        audio.playMusic(MusicTrack.FLIGHT)
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

    /**
     * App backgrounded (or losing audio focus): pause the music so it doesn't play under another app
     * (UC31 edge case — clean background behaviour). `super.pause()` still forwards to the active screen.
     */
    override fun pause() {
        super.pause()
        try {
            audio.pauseMusic()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to pause music on app pause", e)
        }
    }

    /** App foregrounded: resume the music paused in [pause]. `super.resume()` forwards to the screen. */
    override fun resume() {
        super.resume()
        try {
            audio.resumeMusic()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to resume music on app resume", e)
        }
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
            mainMenuScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose main menu screen on shutdown", e)
        }
        mainMenuScreen = null
        try {
            settingsScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose settings screen on shutdown", e)
        }
        settingsScreen = null
        try {
            saveSlotScreen?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose save-slot screen on shutdown", e)
        }
        saveSlotScreen = null
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

        // UC27: dispose the shared atlas only AFTER every screen (which borrowed it) is disposed, so no
        // live screen can draw from a freed texture. Single owner, single dispose (AC#1).
        try {
            gameAssets?.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose game assets on shutdown", e)
        }
        gameAssets = null

        // UC31: release the audio service AFTER every screen is disposed (no screen can still trigger a
        // cue) — its native Sound/Music handles. Single owner, single dispose; reset to the no-op so any
        // late call is harmless.
        // UC31: drop the static UI-tap hook first so no late button event can call the disposed service.
        OrbitalUiSkin.uiTapSound = null
        try {
            audio.dispose()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to dispose audio service on shutdown", e)
        }
        audio = NoOpAudioService

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
