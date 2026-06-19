package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.audio.Sfx
import com.orbitalfrontier.combat.Combat
import com.orbitalfrontier.combat.CombatEvent
import com.orbitalfrontier.combat.CombatLimitedMovement
import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.DestroyedHostile
import com.orbitalfrontier.combat.DestructionSummary
import com.orbitalfrontier.combat.EncounterSpawner
import com.orbitalfrontier.combat.FireAction
import com.orbitalfrontier.combat.PlayerCombatInput
import com.orbitalfrontier.combat.Respawn
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.combat.SalvageDrop
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.crew.Hiring
import com.orbitalfrontier.crew.TurretOperability
import com.orbitalfrontier.debugnav.PointAndGo
import com.orbitalfrontier.debugnav.PointAndGoState
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelBurn
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.PurchaseGate
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.Refueling
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.SpendDecision
import com.orbitalfrontier.economy.StationRefuel
import com.orbitalfrontier.economy.StationRefuelAction
import com.orbitalfrontier.economy.StationRefuelStatus
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.economy.Trading
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationGate
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.mission.BountyParams
import com.orbitalfrontier.mission.BountyTracking
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionGenerator
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.mission.MissionParams
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.mission.Missions
import com.orbitalfrontier.notify.GameNotifications
import com.orbitalfrontier.notify.NotificationQueue
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.Outfitting
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.NoOpAudioService
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.render.AsteroidFieldRenderer
import com.orbitalfrontier.render.DestructionState
import com.orbitalfrontier.render.GameAssets
import com.orbitalfrontier.render.GateRenderer
import com.orbitalfrontier.render.HostileRenderer
import com.orbitalfrontier.render.HudLayout
import com.orbitalfrontier.render.HudRenderer
import com.orbitalfrontier.render.HudViewModel
import com.orbitalfrontier.render.MapOverlayRenderer
import com.orbitalfrontier.render.MapOverlayState
import com.orbitalfrontier.render.MinimapRenderer
import com.orbitalfrontier.render.MotionPreference
import com.orbitalfrontier.render.NotificationRenderer
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.PauseState
import com.orbitalfrontier.render.ShipRenderer
import com.orbitalfrontier.render.ShipSchematicRenderer
import com.orbitalfrontier.render.StarfieldRenderer
import com.orbitalfrontier.render.TextScale
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.render.WorldObjectRenderer
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.save.AutosaveController
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.screen.controls.DestructionOverlay
import com.orbitalfrontier.screen.controls.MovementJoystick
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.screen.controls.PauseOverlay
import com.orbitalfrontier.screen.controls.TutorialOverlay
import com.orbitalfrontier.settings.ControlsLayout
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning
import com.orbitalfrontier.settings.ScreenSide
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.FleetResolver
import com.orbitalfrontier.ship.FuelLimitedMovement
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipPhysics
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.station.StationBuildOrder
import com.orbitalfrontier.station.StationBuilder
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.tutorial.TutorialEvent
import com.orbitalfrontier.tutorial.TutorialHighlight
import com.orbitalfrontier.tutorial.TutorialState
import com.orbitalfrontier.tutorial.TutorialStep
import com.orbitalfrontier.world.AsteroidField
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.Docking
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.Mining
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.ScanAction
import com.orbitalfrontier.world.Scanning
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.Station
import com.orbitalfrontier.world.StationKind
import com.orbitalfrontier.world.WorldState
import kotlin.math.roundToInt

/**
 * The single gameplay screen — a flyable ship in the current sector with inter-sector jump gates
 * and dockable stations (use-cases 01 + 03 + 05).
 *
 * Per frame it runs the ADR 0005 contract — read body kinematics, compute the next velocity with
 * the pure [ShipMovementModel], write it to [ShipPhysics], step Box2D — then runs UC03's gate
 * traversal: it calls the **same** pure [GateTraversal.resolve] the replay harness uses, and on a
 * jump switches [currentSector] and teleports the ship to the arrival point via the only sanctioned
 * transform-set path ([ShipPhysics.resetTo], ADR 0005 — velocity/heading preserved so live motion
 * matches replay momentum), logging one discrete INFO line. It then runs UC05's docking check via
 * the pure [Docking]: each frame it asks [Docking.availableStation] whether a station is in range to
 * drive the context DOCK button + "IN RANGE" prompt; docking is **proximity + explicit action**
 * (never automatic, UC05 pitfall), so only a DOCK tap commits the dock — it sets [dockedStation] via
 * [Docking.resolve], autosaves the event, and hands off to the station hub through [onDocked].
 *
 * The world camera then follows the ship and the screen draws the parallax starfield, the current
 * sector's gates, the ship, the HUD and a minimap (now of all transponder POIs — gates and
 * stations). A Scene2D [Stage] hosts the movement joystick, the inert action cluster, the handedness
 * toggle and the dock context control; an [InputMultiplexer] makes the joystick and cluster register
 * simultaneously (multi-touch, AC#7 pitfall). The dock control is just another Scene2D actor, so
 * flight controls/multitouch are untouched while undocked.
 *
 * The sector graph is the fixed authored map injected as [sectorWorld] (ADR 0004), shared with the
 * game so dock-state resolution agrees across screens; GL-backed resources are created in the
 * constructor (libGDX has a live context by the time the game's `create()` builds this screen) and
 * released in [dispose].
 */
class PlayScreen(
    private val logger: Logger,
    // UC36: retained as fields (was a plain param) so the first-run tutorial can read the persisted
    // completion flag at construction and persist it (via [saveExecutor]) when the tutorial finishes.
    private val settingsRepository: SettingsRepository,
    private val saveExecutor: SaveExecutor,
    private val autosave: AutosaveController,
    private val sectorWorld: SectorWorld,
    // UC27: the shared design-system art atlas, loaded once by the game and BORROWED here — the renderers
    // and the control skin draw from it but never dispose it (the game owns its lifecycle, AC#1).
    private val gameAssets: GameAssets,
    initialHandedness: Handedness,
    // UC37: the persisted joystick tuning (sensitivity + deadzone), read fresh by the app at flight start.
    // Applied to the [joystick] boundary below and seeded into the settings panel; the settings control
    // updates it live via [onJoystickTuningChanged]. Defaults to neutral so headless/JVM tests need not wire it.
    initialJoystickTuning: JoystickTuning = JoystickTuning.DEFAULT,
    initialWorldState: WorldState,
    private val onDocked: (Station) -> Unit,
    // UC32: quit-to-main-menu hand-off. The pause overlay's Quit button flushes a durable autosave and
    // then invokes this; the app ([com.orbitalfrontier.app.OrbitalFrontierGame.returnToMainMenu]) rebuilds
    // and shows the main menu and disposes this screen. Defaults to a no-op so headless/JVM tests that
    // exercise the pause gate need not wire it.
    private val onQuitToMainMenu: () -> Unit = {},
    // UC38: open the save-slot screen in SAVE mode from the pause overlay (manual save, AC#2). The app
    // ([com.orbitalfrontier.app.OrbitalFrontierGame]) shows the save UI over this (paused) screen, persists
    // the live snapshot into the chosen slot, and re-shows this screen. Defaults to a no-op so headless/JVM
    // tests that exercise the pause gate need not wire it.
    private val onOpenSaveSlots: () -> Unit = {},
    // UC31: the injected audio port. SFX are triggered here from the SAME gameplay event seams the
    // autosave/HUD use (combat events, the thrust transition, a productive mining tick, dock, jump,
    // mission accept/complete), so sound is event-driven and the pure simulation stays audio-free
    // (AC#4). Defaults to the no-op so headless/JVM contexts (the replay harness, tests) run silently.
    private val audio: AudioService = NoOpAudioService,
    // UC25: debug-build flag. When true, the debug-only point-and-go navigation aid (arm toggle +
    // world-tap teleport) is wired below; when false (release, tests) none of it is constructed, so
    // release controls, tap handling, and gameplay are byte-for-byte unchanged.
    private val debug: Boolean = false,
    // UC40: the SHARED transient notification queue, constructed once by the game and injected into this
    // screen AND the five economy screens + the hub, so a credit delta / styled error enqueued while a desk
    // is open surfaces on that desk (the queue is pure and only one screen renders at a time, preserving
    // single-render-thread ownership). Defaults to a fresh queue so headless/JVM tests need not wire it.
    private val notifications: NotificationQueue = NotificationQueue(),
) : ScreenAdapter() {
    private val worldCamera = OrthographicCamera()
    private val model = ShipMovementModel()
    private val params = ShipMovementParams()

    // Fuel/power tunables (UC07). Authored defaults; the same params feed the shared pure [FuelBurn]
    // and [FuelLimitedMovement]/[Refueling] here, so live fuel behaviour matches the model/tests.
    private val powerParams = PowerParams()
    private val fuelParams = FuelParams()

    // Re-seed the Box2D body from the persisted (or default) kinematics — never persist Box2D
    // internals; the kinematics are the save's source of truth on load (UC04 AC#6 / pitfall).
    private val physics = ShipPhysics(spawn = initialWorldState.ship)

    private val starfield = StarfieldRenderer()
    private val shipRenderer = ShipRenderer(gameAssets)
    private val hudRenderer = HudRenderer()

    // UC35: the transient notification feed. [notifications] is the pure, libGDX-free queue (event-driven,
    // JVM-testable — it holds/coalesces/auto-dismisses the toasts); [notificationRenderer] is its GL-bound
    // draw side (mirrors HudRenderer). The screen enqueues from the SAME gameplay seams that drive SFX
    // (jump, dock/undock, the mission life-cycle, the combat boundary, low fuel, credit changes), advances
    // the queue only while the sim advances, and draws visible() unless a full-screen overlay is up (AC#3/#4).
    private val notificationRenderer = NotificationRenderer()
    private val gateRenderer = GateRenderer()
    private val asteroidFieldRenderer = AsteroidFieldRenderer()

    // ADR 0015: the one in-world renderer that draws a base glyph for EVERY POI (stations included),
    // so no POI can render as nothing; gate/asteroid renderers above now draw only their rings.
    private val worldObjectRenderer = WorldObjectRenderer(gameAssets)
    private val minimap = MinimapRenderer(gameAssets)

    // UC23: the click-to-zoom full-height map overlay. [mapOverlayState] is the pure open/closed toggle
    // (libGDX-free, JVM-testable); [mapOverlay] is its GL renderer (mirrors the minimap). The overlay is
    // LIVE — opening it does NOT pause the simulation (MapOverlayLayout.PAUSES_SIMULATION = false), so
    // the per-frame sim/autosave/combat below run unchanged whether or not the map is open.
    private var mapOverlayState = MapOverlayState()
    private val mapOverlay = MapOverlayRenderer()

    // Combat visuals (UC13): hostiles + projectiles in world space, and the per-section HUD ship
    // schematic. Both only read state and draw nothing while combat is inactive.
    private val hostileRenderer = HostileRenderer(gameAssets)
    private val shipSchematicRenderer = ShipSchematicRenderer(gameAssets)

    // Mining tunables (UC06). Authored defaults; the same params feed the pure [Mining.resolve] each
    // frame so live mining matches the replay harness exactly.
    private val miningParams = MiningParams()

    // The current sector + dock state are the only mutable world state held here (the sector graph
    // itself is fixed authored data, injected as [sectorWorld]). [dockedStation] is null while flying;
    // when the ship docks it holds the station id and the game shows the station hub instead.
    private var currentSector = initialWorldState.currentSector
    private var dockedStation: PoiId? = initialWorldState.dockedStation

    // Cargo + per-field depletion (UC06): the only other mutable world state held here. Seeded from
    // the loaded/initial snapshot; mining folds each tick's [Mining.resolve] result back into them and
    // [currentWorldState] hands them to the autosave.
    private var cargo: Cargo = initialWorldState.cargo
    private var fieldDepletion: Map<PoiId, Map<ResourceType, Int>> = initialWorldState.fieldDepletion

    // Fuel (UC07): the active ship's tank, seeded from the loaded/initial snapshot. Burned each tick by
    // the shared [FuelBurn], topped up by [refuel] (station REFUEL), and handed to the autosave via
    // [currentWorldState]. Low fuel scales the speed caps through [FuelLimitedMovement] (never strands).
    private var fuel: Fuel = initialWorldState.fuel

    // Credits (UC08): the player's single-currency wallet, seeded from the loaded/initial snapshot.
    // Mutated only by [trade] (the station trade desk, via the pure [Trading.resolve]) and handed to
    // the autosave via [currentWorldState]. Save-wide (not per-ship), so it lives here alongside the
    // other mutable world state rather than on the ship.
    private var credits: Long = initialWorldState.credits

    // Fleet (UC09): the ships the player owns and which is active. Seeded from the loaded/initial
    // snapshot. The active ship's live kinematics/cargo/fuel are tracked in the fields above (read back
    // from the Box2D body / mining / fuel burn each frame); [currentWorldState] folds them onto the
    // active ship in this fleet before handing the snapshot to the autosave. In Stage A there is only
    // ever one (starter) ship and this never changes after construction; outfitting / ship switching
    // (Stage B) mutate it.
    private var fleet: Fleet = initialWorldState.fleet

    // Revealed hidden contacts (UC10): the ids of no-transponder contacts the player has uncovered by
    // active scanning, seeded from the loaded/initial snapshot. A SCAN tap folds the pure
    // [Scanning.resolve] result back in (union-only — revealed contacts never re-hide, AC#4);
    // [currentWorldState] hands the set to the autosave, and it is passed to the minimap so revealed
    // contacts draw while hidden ones stay invisible. Save-wide (a contact id is globally unique).
    private var revealedContacts: Set<PoiId> = initialWorldState.revealedContacts

    // Missions (UC12): the player's mission log, seeded from the loaded/initial snapshot. The HELD field
    // carries only the persisted ACCEPTED/terminal missions (its `available` stays empty); the available
    // BOARD/RADIO offers are recomputed on demand from the deterministic [MissionGenerator], filtered
    // against the accepted ids (regenerate-and-filter). Accept/turn-in fold the pure [Missions.resolve]
    // result back in (docked board branch + in-flight radio accept); [currentWorldState] hands the log
    // to the autosave (which persists only the accepted missions).
    private var missionLog: MissionLog =
        MissionLog(available = emptyList(), accepted = initialWorldState.missions.accepted)

    // Mission tunables (UC12). Authored defaults; the same params feed the generator and the pure
    // [Missions.resolve]/[Missions.advance] here, so live mission behaviour matches the replay harness.
    private val missionParams = MissionParams()

    // Bounty tunables (UC41). Authored defaults; the same params feed the bounty offer generation here and
    // in the replay harness, so a bounty offer's reward matches between live and replay.
    private val bountyParams = BountyParams()

    // Reputation (UC14): the player's per-faction standing, seeded from the loaded/initial snapshot.
    // Save-wide (like credits). Mutated only by the pure [Missions.resolve] (a faction mission turn-in)
    // and [Missions.advance] (a courier expiry), and read by [ReputationGate] to filter which mission
    // offers surface (board + radio). [currentWorldState] hands it to the autosave. The same
    // [reputationParams] feed the resolvers here and the replay harness, so live behaviour matches.
    private var reputation: Reputation = initialWorldState.reputation
    private val reputationParams = ReputationParams()

    // Owned stations (UC15): the player-built stations, seeded from the loaded/initial snapshot. Save-wide
    // (like the fleet/credits). Mutated only by [build] (the docked station-build service, via the pure
    // [StationBuilder.resolve]) and handed to the autosave via [currentWorldState]. Defaults to EMPTY for a
    // fresh / pre-UC15 save, so the snapshot stays byte-identical until the player builds their first station.
    private var stations: StationRegistry = initialWorldState.stations

    // Courier timer drive (UC12): the model timer is TICK-based ([Missions.advance] decrements one
    // `remainingTicks` per call); the device paces those calls off accumulated real time so the
    // countdown is frame-rate-independent. We accumulate dt and fire one advance per [MISSION_TICK_SECONDS]
    // — the model timer stays the authority; this is just its dt-paced surface (ADR 0011).
    private var missionTickAccumulator = 0f

    // Play time (UC38 AC#1): the accumulated wall-of-play seconds shown per save slot, seeded from the
    // loaded/initial snapshot so resuming a slot continues its counter. Accumulated on the render thread
    // from the per-frame `dt` ONLY while the sim actually advances (not while paused / on the destruction
    // screen), so it tracks real play time. The fractional remainder is carried in [playTimeAccumulator]
    // so sub-second frames are not lost; whole seconds roll into [playTimeSeconds] and are folded onto the
    // autosave snapshot in [currentWorldState]. It is NOT part of the deterministic sim (replay ignores it).
    private var playTimeSeconds: Long = initialWorldState.playTimeSeconds.coerceAtLeast(0)
    private var playTimeAccumulator = 0f

    // Combat (UC13): the live encounter, seeded from the loaded/initial snapshot (NONE on load — combat
    // is transient and never persisted, ADR 0012). [Combat.step] folds each tick's result back in;
    // [lastDockedStation] is the persisted respawn point (set on each dock, retained after undock). The
    // edge-triggered natural spawner needs the player's PREVIOUS position to detect an outside→inside zone
    // crossing, so we track it. [combatSpawnTick] is a monotonic counter seeding each spawn so successive
    // encounters differ deterministically; the combat tick is paced like the courier timer.
    private var combat: CombatState = initialWorldState.combat
    private var lastDockedStation: PoiId? = initialWorldState.lastDockedStation
    private var previousShipPosition: Vec2 = initialWorldState.ship.position
    private var combatSpawnTick = 0
    private var combatTickAccumulator = 0f
    private val combatParams = CombatParams()

    // Salvage (UC42): the wrecks currently floating in the world + the monotonic id allocator, seeded
    // from the loaded/initial snapshot (empty on load — salvage is transient, never persisted, like
    // combat). A combat kill spawns one drop via the shared [Salvage.spawn] in [stepCombatOnce]; the
    // per-frame [collectSalvage] (run after movement, before the combat branch) folds proximity pickups
    // into credits + cargo via [Salvage.collect]. [currentWorldState] hands both to the autosave (the
    // repository never persists them). Held here alongside the other transient combat state.
    private var salvage: List<SalvageDrop> = initialWorldState.salvage
    private var nextSalvageId: Long = initialWorldState.nextSalvageId

    // UC31: previous-frame thrust state for the looping THRUST cue. The engine sound starts on the
    // not-thrusting -> thrusting rising edge ([audio].play) and stops on the falling edge ([audio].stopSfx),
    // so it plays continuously while the stick is held and exactly once per ignition (AC#1).
    private var previousThrusting = false

    // UC31: cooldown that paces the MINING_TICK cue. Mining resolves EVERY held frame (60/s), so the cue
    // is throttled to one play per MINING_SFX_INTERVAL of productive mining — a pleasant pulse, not a
    // 60 Hz buzz. Counts down only on productive ticks; the first tick of a session plays immediately.
    private var miningSfxCooldown = 0f

    // UC35: edge-tracking for the two notifications that have no single discrete event seam of their own.
    // [previousCombatActive] drives the ENTERED-COMBAT toast on the combat-active false->true edge (the
    // CombatEvent hierarchy has no "encounter started" event; LEFT-COMBAT flows through forCombatEvent on
    // EncounterCleared instead). [previousLowFuel] drives the LOW-FUEL toast on the fuel-is-low false->true
    // edge, so it fires once per dip into the low band rather than every frame the tank stays low.
    private var previousCombatActive = initialWorldState.combat.active
    private var previousLowFuel = initialWorldState.fuel.isLow(fuelParams)

    private val skin = OrbitalUiSkin(gameAssets)

    // ADR 0015: scale the Scene2D UI (controls + fonts) by UiScale.factor via the viewport's
    // unitsPerPixel; this is UI-only and does NOT touch the world camera (the playfield stays 1:1).
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })
    private val joystick = MovementJoystick(skin)
    private val actionCluster = ActionCluster(skin)
    private val settingsOverlay: SettingsOverlay
    private val inputMultiplexer = InputMultiplexer(stage)

    // UC36: the first-run tutorial. [tutorialOverlay] is the thin draw-only hint band (copy + SKIP / SKIP
    // ALL); the pure [TutorialState] holds the progression. It starts at the first step on a genuine first
    // run (the persisted flag is unset) and at the terminal state otherwise, so a returning player sees
    // nothing (AC#3). [tutorialCompletionPersisted] guards the one-shot completion write; the seams below
    // record [TutorialEvent]s into [pendingTutorialEvents], which are drained — and the state advanced —
    // inside the gated per-frame advance (beside notifications.update), so the onboarding freezes under
    // pause/destruction and never participates in the deterministic simulation (AC#4).
    private val tutorialOverlay = TutorialOverlay(skin)
    private var tutorialState: TutorialState =
        if (settingsRepository.loadTutorialCompleted()) TutorialState.COMPLETED else TutorialState.NEW
    private var tutorialCompletionPersisted: Boolean = tutorialState.isComplete
    private val pendingTutorialEvents = mutableListOf<TutorialEvent>()

    // UC32: the in-flight pause overlay. [pauseState] is the pure paused/running gate (libGDX-free,
    // JVM-testable, the deliberate inverse of [mapOverlayState] — it FREEZES the sim, ADR 0021); read once
    // per frame to gate the entire per-frame state-advance below (AC#2). [pauseSettingsShown] tracks the
    // Settings sub-view (the in-flight settings panel surfaced over the pause backdrop, AC#3). [pauseOverlay]
    // is the modal Scene2D widget (dim backdrop + Resume/Settings/Quit), added to the stage LAST (top z), and
    // [pauseButton] is the top-centre HUD control that opens it (reachable in combat, hidden while an overlay
    // is open). The Android back key also drives pause/resume (AC#1) via an input processor wired in [init].
    private var pauseState = PauseState()
    private var pauseSettingsShown = false
    private val pauseOverlay = PauseOverlay(skin)
    private val pauseButton = TextButton("PAUSE", skin.settingsButtonStyle)

    // UC33: the destruction / game-over gate + overlay. [destructionState] is the pure gate (libGDX-free,
    // JVM-testable) raised by the SIMULATION on a hull-destroying hit (not a player tap); like the pause
    // gate it FREEZES the per-frame advance, but it is read NESTED under the pause gate so the two compose,
    // it carries the [DestructionSummary] the overlay renders, and it clears only on a deliberate CONTINUE
    // (AC#1/#2/#3). [destructionOverlay] is the modal Scene2D widget (dim backdrop + "SHIP DESTROYED" +
    // consequence lines + CONTINUE), added to the stage LAST (above the pause overlay) for the top z-order.
    private var destructionState = DestructionState()
    private val destructionOverlay = DestructionOverlay(skin)

    // UC26: every player action now lives on the bottom-corner action arc ([actionCluster]) instead of in
    // standalone context panels. The contextual actions still latch the same one-shot intents the panels
    // did, set by the arc button's tap callback (wired in [init]) and consumed inside the deterministic
    // per-frame flow (after the step) so the game-state effect is byte-identical (UC26 AC#8):
    //  - DOCK (UC05): edge-triggered — [dockRequested] commits the dock on the next render.
    //  - MINE (UC06): a *held* action — the render loop reads [ActionCluster.isMinePressed] each frame.
    //  - SCAN (UC10): edge-triggered — [scanRequested] reveals contacts on the next render.
    //  - RADIO accept (UC12): edge-triggered — [radioAcceptRequested] accepts the in-range offer.
    private var dockRequested = false
    private var scanRequested = false
    private var radioAcceptRequested = false

    // UC26 context readout: the decision-relevant info the retired DOCK/MINE/RADIO panels showed (station
    // name, cargo fill, mission reward) relocated to a single status line near the arc, rebuilt each frame
    // from the same availability the arc buttons are gated on, so nothing essential is lost (UC26 risk).
    private val contextReadout = Label("", skin.labelStyle)

    // UC23 map-overlay tap targets — invisible Scene2D actors (they draw nothing; the overlay visuals
    // are drawn by [mapOverlay] after stage.draw()). [minimapTapTarget] sits exactly on the drawn
    // minimap panel (bounds from the shared MinimapRenderer.panelRect — one geometry source for draw +
    // touch) and toggles the overlay open on a tap. [mapDismissActor] is a full-screen catch-all added
    // LAST (top z) and visible only while the overlay is open; any tap on it dismisses the overlay
    // (AC#5 — the player can never be trapped) and is consumed (ClickListener.touchDown returns true) so
    // it never leaks through to a flight control underneath.
    private val minimapTapTarget = Actor()
    private val mapDismissActor = Actor()

    // UC25 debug point-and-go (debug builds only). UC26 moved its arm toggle onto the action arc (a
    // debug-only arc button), but the world-tap processor is still constructed in [init] ONLY when
    // [debug] is true, so a release build never builds any of it and tap handling stays byte-for-byte
    // unchanged. [pointAndGoState] is the pure arm/disarm gate (defaults OFF so normal taps are never
    // hijacked); [pendingTeleport] is a one-shot world point set by an armed tap and consumed at the top
    // of the next [render] (last-wins), mirroring [dockRequested].
    private var pointAndGoState = PointAndGoState()
    private var pendingTeleport: Vec2? = null

    private var handedness = initialHandedness

    init {
        // UC37: apply the persisted joystick tuning to the live input boundary (the ONLY place it takes
        // effect); the settings control updates it live via onJoystickTuningChanged below.
        joystick.tuning = initialJoystickTuning.coerced()

        settingsOverlay =
            SettingsOverlay(
                skin = skin,
                repository = settingsRepository,
                saveExecutor = saveExecutor,
                initialHandedness = initialHandedness,
                // UC31: seed the audio controls from persisted settings (already applied to [audio] by the
                // app at startup); the overlay then keeps them in sync as the player adjusts them.
                initialAudio = settingsRepository.loadAudioSettings(),
                // UC37: seed the joystick + UI-scale controls from the live values — the tuning just applied
                // to the joystick, and the global UI scale already restored by the app at startup.
                initialJoystickTuning = initialJoystickTuning,
                initialUiScale = UiScale.factor,
                // UC39: seed the accessibility controls from the live globals the app restored at startup
                // (mirrors initialUiScale = UiScale.factor) — colour-vision mode, text scale, reduced motion.
                initialColorVisionMode = Palette.mode,
                initialTextScale = TextScale.factor,
                initialReducedMotion = MotionPreference.reduced,
                audio = audio,
                onHandednessChanged = { newHandedness ->
                    handedness = newHandedness
                    layoutControls()
                },
                // UC37: a sensitivity/deadzone change applies live at the joystick boundary — the model +
                // replay never see it, so determinism is preserved (ADR 0006).
                onJoystickTuningChanged = { newTuning -> joystick.tuning = newTuning },
                // UC37: a UI-scale change is already in the global UiScale knob; re-apply it to THIS stage's
                // viewport and re-flow the controls so the on-screen chrome rescales without leaving flight.
                // The screen-space HUD/minimap renderers captured the factor at construction, so they reflect
                // the new scale on the next screen rebuild (no app restart) — documented in ADR 0025.
                onUiScaleChanged = { applyUiScaleLive() },
                // UC39: colour-vision mode is already on the global Palette; the in-flight HUD/minimap/map
                // overlay read its mode-aware accessors every frame, so the change is visible next frame with
                // nothing extra to do here.
                onColorVisionModeChanged = {},
                // UC39: a text-size change is already in the global TextScale knob; re-apply it to this
                // screen's skin font and re-flow the open panel so the resized UI text shows immediately.
                onTextScaleChanged = { applyTextScaleLive() },
                // UC39: reduced motion is already in the global MotionPreference; the starfield reads it each
                // frame, so the static/scrolling field switches on the next frame with nothing extra here.
                onReducedMotionChanged = {},
                // UC36 AC#3: REPLAY TUTORIAL restarts the onboarding from the first step (the persisted
                // first-run flag is left set, so this is a one-session replay, not a re-arm of first-run).
                onReplayTutorial = { replayTutorial() },
            )

        // UC36: SKIP advances past the current step; SKIP ALL jumps to the end. Both run on the UI thread;
        // a resulting completion is persisted immediately (these are deliberate, not per-frame, events).
        tutorialOverlay.onSkip = {
            tutorialState = tutorialState.skipped()
            persistTutorialIfComplete()
        }
        tutorialOverlay.onSkipAll = {
            tutorialState = tutorialState.dismissed()
            persistTutorialIfComplete()
        }

        // UC26: wire the arc's edge-triggered taps to the same one-shot intents the retired context
        // panels set. Each callback runs on the UI thread between frames (like the old ClickListeners);
        // the flag is consumed inside the deterministic per-frame flow, so the game-state effect is
        // byte-identical (UC26 AC#8). MINE/FIRE are held and read directly each frame (no callback).
        actionCluster.onDock = { dockRequested = true }
        actionCluster.onScan = { scanRequested = true }
        actionCluster.onAcceptRadio = { radioAcceptRequested = true }
        // SCAN is persistent in flight (not proximity-gated, UC10); the render loop keeps it available.
        actionCluster.setActionAvailable(ActionCluster.Action.SCAN, true)
        // The context readout is decorative — hidden until an action surfaces info, never hittable.
        contextReadout.touchable = Touchable.disabled
        contextReadout.isVisible = false

        // UC23: tap the minimap to open the zoomed overlay. While the overlay is open the full-screen
        // dismiss actor sits on top and intercepts taps, so this listener only ever runs closed -> open.
        minimapTapTarget.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    mapOverlayState = mapOverlayState.toggled()
                }
            },
        )
        // Any tap while the overlay is open dismisses it (AC#5 — no trap). Hidden (and so untouchable)
        // while the overlay is closed; its visibility is driven each frame in render().
        mapDismissActor.isVisible = false
        mapDismissActor.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    mapOverlayState = mapOverlayState.dismissed()
                }
            },
        )

        // UC32: open the pause overlay from the top-centre HUD button (AC#1). Resume/Quit transitions and
        // the Settings sub-view are wired to the overlay's callbacks; Settings shows the existing in-flight
        // settings panel on top of the backdrop (and Back returns to the pause menu, AC#3).
        pauseButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    openPause()
                }
            },
        )
        pauseOverlay.onResume = { resumeGame() }
        pauseOverlay.onSave = { onOpenSaveSlots() } // UC38: manual save (AC#2); game stays paused.
        pauseOverlay.onSettings = { enterPauseSettings() }
        pauseOverlay.onBack = { exitPauseSettings() }
        pauseOverlay.onQuit = { quitToMainMenu() }
        // UC33: the destruction overlay's single CONTINUE button acknowledges the consequence screen and
        // resumes control at the respawn point (the respawn was already applied + persisted, AC#3).
        destructionOverlay.onContinue = { continueAfterDestruction() }

        stage.addActor(joystick.actor)
        stage.addActor(actionCluster.actor)
        stage.addActor(contextReadout)
        stage.addActor(settingsOverlay.actor)
        // UC23: the minimap tap target, then the full-screen dismiss actor so it catches taps over
        // everything (including the minimap) while the map overlay is open.
        stage.addActor(minimapTapTarget)
        stage.addActor(mapDismissActor)
        // UC36: the tutorial hint band — above the HUD/controls (so its copy reads over the playfield and
        // its SKIP buttons are tappable) but BELOW the pause button and the pause/destruction backdrops, so
        // a pause/destruction frame covers it and its SKIP taps are suppressed while paused (it is hidden
        // whenever a full-screen overlay is up, see renderFrame).
        stage.addActor(tutorialOverlay.actor)
        // UC32: the HUD pause button, then the modal pause overlay LAST so its backdrop holds the top
        // z-order over the HUD, the minimap and the map dismiss actor while the game is paused.
        stage.addActor(pauseButton)
        stage.addActor(pauseOverlay.actor)
        // UC33: the modal destruction overlay LAST of all, so its backdrop holds the very top z-order
        // (above the HUD, the map/pause overlays and every tap target) while a destruction is pending.
        stage.addActor(destructionOverlay.actor)

        // UC32: Android back key drives pause (AC#1, pitfall#2). Appended AFTER the stage (never
        // setProcessors) so on-screen controls keep first crack at touches; this only handles the BACK key.
        // In the settings sub-view BACK steps back to the pause menu; while paused it resumes; in flight it
        // opens pause. Scoped to this screen — the catch is enabled in [show] and released in [hide].
        inputMultiplexer.addProcessor(
            object : InputAdapter() {
                override fun keyDown(keycode: Int): Boolean {
                    if (keycode != Input.Keys.BACK) return false
                    when {
                        pauseSettingsShown -> exitPauseSettings()
                        pauseState.isPaused -> resumeGame()
                        else -> openPause()
                    }
                    return true
                }
            },
        )

        // UC25/UC26: wire the debug-only point-and-go aid. Everything here is gated on [debug], so a
        // release build constructs none of it. UC26 moved its arm toggle onto the action arc; the
        // world-tap processor below is byte-for-byte unchanged.
        if (debug) {
            // The point-and-go arc button (debug only): tapping it toggles the arm state and reflects it
            // on the button label, exactly as the old standalone toggle did.
            actionCluster.setActionAvailable(ActionCluster.Action.POINT_AND_GO, true)
            actionCluster.onPointAndGoToggle = {
                pointAndGoState = pointAndGoState.toggled()
                actionCluster.setPointAndGoArmed(pointAndGoState.armed)
            }

            // Appended AFTER the stage (never setProcessors) so flight controls keep first crack at every
            // touch; this processor only claims a touch while armed. touchDown returns false unless armed
            // (so normal taps fall through to the controls/stage); while armed it unprojects the tap into
            // world space via the live camera (UC25 pitfall — accounts for camera/zoom) and stores it as a
            // one-shot pending teleport (last-wins), consumed at the top of the next render().
            inputMultiplexer.addProcessor(
                object : InputAdapter() {
                    override fun touchDown(
                        screenX: Int,
                        screenY: Int,
                        pointer: Int,
                        button: Int,
                    ): Boolean {
                        if (!pointAndGoState.armed) return false
                        val unprojected = worldCamera.unproject(Vector3(screenX.toFloat(), screenY.toFloat(), 0f))
                        pendingTeleport = Vec2(unprojected.x, unprojected.y)
                        return true
                    }
                },
            )
        }
    }

    /**
     * Open the pause overlay (UC32 AC#1): freeze the sim via [pauseState], enforce the one-overlay rule by
     * dismissing the map overlay (pitfall — only one overlay visible), and neutralise held inputs so a
     * stick/FIRE held at the moment of pause does not "stick" on resume (pitfall#3): cancel Scene2D touch
     * focus and stop the looping THRUST cue (mirrors [hide]). No-op when already paused.
     */
    private fun openPause() {
        if (pauseState.isPaused) return
        pauseState = pauseState.paused()
        pauseSettingsShown = false
        pauseOverlay.showSettingsSubView(false)
        mapOverlayState = mapOverlayState.dismissed()
        stage.cancelTouchFocus()
        if (previousThrusting) {
            audio.stopSfx(Sfx.THRUST)
            previousThrusting = false
        }
        logger.info(TAG, "Game paused")
    }

    /** Resume from pause (UC32 AC#4): unfreeze the sim and leave the Settings sub-view. No-op when running. */
    private fun resumeGame() {
        if (!pauseState.isPaused) return
        pauseState = pauseState.resumed()
        pauseSettingsShown = false
        pauseOverlay.showSettingsSubView(false)
        logger.info(TAG, "Game resumed")
    }

    /**
     * Enter the pause Settings sub-view (UC32 AC#3): hide the pause button column and surface the existing
     * in-flight settings panel. The panel was added to the stage BEFORE the pause backdrop, so bring it to
     * the front — otherwise the backdrop swallows its taps and the controls are dead.
     */
    private fun enterPauseSettings() {
        pauseSettingsShown = true
        pauseOverlay.showSettingsSubView(true)
        settingsOverlay.actor.toFront()
    }

    /** Leave the Settings sub-view back to the pause menu (UC32 AC#3). */
    private fun exitPauseSettings() {
        pauseSettingsShown = false
        pauseOverlay.showSettingsSubView(false)
    }

    /**
     * Quit to the main menu (UC32 AC#4): flush a durable autosave FIRST so no progress is lost, then hand
     * off to the app, which rebuilds + shows the main menu and disposes this screen. The blocking flush
     * mirrors the Android pause/exit path ([pause] -> [AutosaveController.onPauseOrExit]).
     */
    private fun quitToMainMenu() {
        logger.info(TAG, "Quit to main menu (flushing autosave)")
        autosave.onPauseOrExit()
        onQuitToMainMenu()
    }

    override fun show() {
        Gdx.input.inputProcessor = inputMultiplexer
        // UC32: catch the Android BACK key so it maps to pause/resume here instead of the default
        // screen-back/exit behaviour (AC#1). Released in [hide], so the catch is scoped to this screen.
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        layoutControls()
        logger.info(TAG, "PlayScreen shown (handedness=$handedness)")
    }

    override fun render(delta: Float) {
        val dt = delta.coerceIn(MIN_DT, MAX_DT)
        // UC23: whether the zoomed map overlay is open this frame. It gates the HUD control visibility +
        // the map overlay draw below. (UC32: the pause overlay, unlike this LIVE map overlay, additionally
        // FREEZES the sim — see the pause gate just below.)
        val mapOpen = mapOverlayState.isOpen
        // UC32: the pause gate, read once. While paused the ENTIRE deterministic per-frame advance is
        // skipped — no game time passes (AC#2/#5) — and only [renderFrame] runs (reading the frozen body),
        // so resuming continues exactly where the player left off (AC#4).
        val paused = pauseState.isPaused
        // UC33: a pending destruction ALSO freezes the deterministic advance (the consequence screen halts
        // play until the player taps CONTINUE — AC#1). The gate is nested inside the pause gate so the two
        // compose; while either is raised no game time passes and only [renderFrame] runs (AC#2/#5).
        if (!paused) {
            if (!destructionState.isPending) {
                advanceSimulation(dt)
                accumulatePlayTime(dt) // UC38 AC#1: count play time only while the sim actually advances.
            }
        }
        // Render the ship from the live Box2D transform: this frame's step when running, the frozen
        // kinematics when paused. After a gate jump the advance already reset the body to the arrival point.
        val renderShip = physics.readKinematics()
        renderFrame(dt, mapOpen, paused, renderShip)
    }

    /**
     * The entire deterministic per-frame state-advance (UC32 gates this on the pause state, so while paused
     * no game time passes — AC#2/#5). The flow is byte-identical to the pre-UC32 inline body: debug
     * teleport, fuel burn + thrust cue, the ADR 0005 movement step, gate traversal, docking, mining,
     * scanning, radio offers, courier timers, combat, then the periodic autosave.
     */
    private fun advanceSimulation(dt: Float) {
        val input = joystick.currentInput()

        // UC25 debug point-and-go: consume an armed-tap teleport BEFORE this frame reads the body, so the
        // frame proceeds from the teleported state (and the docking check below shows the DOCK prompt when
        // we land in a station's range). The pure [PointAndGo.resolve] maps the tap to a stationary,
        // sensibly-oriented kinematics; [ShipPhysics.resetTo] is the only sanctioned transform-set path
        // (ADR 0005). NOT a recorded input and NO autosave.onEvent — a manual debug action must not perturb
        // the deterministic record/replay harness or autosave (UC25 AC#5). One INFO log, then we fall
        // through to the normal per-frame flow. pendingTeleport is null on every release build (never set).
        val teleport = pendingTeleport
        if (teleport != null) {
            pendingTeleport = null
            val resolution =
                PointAndGo.resolve(teleport, sectorWorld.sector(currentSector).pois, physics.readKinematics())
            physics.resetTo(resolution.kinematics)
            logger.info(
                WORLD_TAG,
                "Debug point-and-go: teleported to (${resolution.kinematics.position.x}, " +
                    "${resolution.kinematics.position.y}); target=${resolution.targetPoiId?.value ?: "free space"}",
            )
        }

        // UC07 fuel burn: the ship draws power every tick (base load even idle, more while thrusting),
        // and that draw burns fuel via THE shared [FuelBurn.step] (same fn the sim/replay path uses).
        // "Thrusting" = an active stick past the deadzone, matching the model's own thrust gate.
        val thrusting = !input.released && input.magnitude > params.inputDeadzone
        fuel = FuelBurn.step(fuel, thrusting, powerParams, dt)

        // UC31: drive the looping engine cue off the thrust transition (edge-triggered, AC#1) — start it
        // when thrust begins, stop it when thrust ends, so it plays only while the stick is held.
        if (thrusting != previousThrusting) {
            if (thrusting) audio.play(Sfx.THRUST) else audio.stopSfx(Sfx.THRUST)
            // UC36: the first thrust completes the STEER tutorial step (recorded on the rising edge).
            if (thrusting) recordTutorialEvent(TutorialEvent.STEERED)
            previousThrusting = thrusting
        }

        // UC35: low-fuel notification on the false->true edge of the low-fuel band (AC#1) — fired once when
        // the tank dips low, not every frame it stays low; re-arms once the tank is topped back up.
        val lowFuelNow = fuel.isLow(fuelParams)
        if (lowFuelNow && !previousLowFuel) notifications.enqueue(GameNotifications.lowFuel())
        previousLowFuel = lowFuelNow

        // ADR 0005 per-frame contract: read -> model computes velocity -> apply -> Box2D steps. UC09:
        // the model runs against the ACTIVE SHIP's params — its type + engine loadout (ShipStats) — then
        // the fuel-limited scaling on top (UC07 AC#3). For the starter ship with an empty loadout
        // ShipStats returns `params` unchanged and, at a full-enough tank, [effectiveParams] also returns
        // it unchanged, so movement stays byte-identical to the pre-UC09 build.
        val active = fleet.active
        val shipParams = ShipStats.effectiveMovementParams(params, active.type, active.loadout)
        val fuelParamsScaled = FuelLimitedMovement.effectiveParams(shipParams, fuel, fuelParams)
        // UC13: engine damage scales speed on top of fuel limiting. At a pristine engine (the common
        // case) [CombatLimitedMovement] returns the same instance, so movement stays byte-identical.
        val effectiveParams =
            CombatLimitedMovement.effectiveParams(
                fuelParamsScaled,
                active.sectionDamage,
                ShipStats.sectionHp(active.type, active.loadout, ShipSection.ENGINE),
                combatParams,
            )
        val state = physics.readKinematics()
        val next = model.update(state, input, effectiveParams, dt)
        physics.applyKinematics(next)
        physics.step(dt)
        val stepped = physics.readKinematics()

        // UC03: same pure GateTraversal the replay harness runs. On a jump, switch sector and place
        // the ship at the arrival point via ADR 0005's only sanctioned transform-set path
        // (resetTo preserves velocity/heading, so live momentum matches replay). Discrete INFO log.
        val traversal = GateTraversal.resolve(sectorWorld, currentSector, stepped.position)
        val ship =
            if (traversal != null) {
                currentSector = traversal.destinationSector
                val arrived = stepped.copy(position = traversal.arrivalPosition)
                physics.resetTo(arrived)
                logger.info(
                    WORLD_TAG,
                    "Jumped to sector ${currentSector.value} at " +
                        "(${arrived.position.x}, ${arrived.position.y})",
                )
                // Event-driven autosave: a jump is a key world event (UC04 AC#2). The snapshot the
                // controller reads now reflects the post-jump sector + re-seeded kinematics.
                autosave.onEvent("jump")
                audio.play(Sfx.JUMP) // UC31: jump-gate transition cue (AC#1)
                // UC35: jump-completed toast, naming the sector just entered (AC#1).
                notifications.enqueue(GameNotifications.jumpCompleted(sectorWorld.sector(currentSector).displayName))
                arrived
            } else {
                stepped
            }

        // UC05 docking: same pure [Docking] the (future) replay harness would use. Each frame, find the
        // in-range station (if any) to drive the context prompt/button; commit a dock only on an
        // explicit DOCK tap (proximity + action, never automatic — UC05 pitfall). While the play
        // screen is active the ship is always undocked (a dock hands off to the hub), so a successful
        // resolve yields the station id and we switch screens.
        val available = Docking.availableStation(sectorWorld, currentSector, ship.position)
        actionCluster.setActionAvailable(ActionCluster.Action.DOCK, available != null)
        if (dockRequested) {
            dockRequested = false
            if (available != null) {
                dockedStation = Docking.resolve(sectorWorld, currentSector, dockedStation, ship.position, DockAction.DOCK)
                // UC13: remember this as the respawn point on destruction (persisted, retained after undock).
                lastDockedStation = available.id
                logger.info(WORLD_TAG, "Docked at station ${available.id.value} (${available.displayName})")
                // Docking is a key world event — event-driven autosave persists the dock state now.
                autosave.onEvent("dock")
                audio.play(Sfx.DOCK) // UC31: dock cue (AC#1)
                // UC35: dock toast, naming the station (AC#1). Enqueued before the hub hand-off so it is in
                // the queue and surfaces the next time the play screen is shown (on undock).
                notifications.enqueue(GameNotifications.docked(available.displayName))
                recordTutorialEvent(TutorialEvent.DOCKED) // UC36: completes the DOCK step
                onDocked(available)
            }
        }

        // UC06 mining: same pure [Mining] the replay harness uses. Each frame, find the in-range field
        // (if any) to drive the context prompt/button; while the MINE button is held, run one
        // extraction tick and fold the result back into cargo + depletion (proximity + held action,
        // never automatic). No per-frame logging — only the discrete cargo-full / field-depleted
        // transitions trigger an event autosave (which logs once), protecting the 60 FPS budget.
        val field = Mining.availableField(sectorWorld, currentSector, ship.position)
        actionCluster.setActionAvailable(ActionCluster.Action.MINE, field != null)
        if (field != null && actionCluster.isMinePressed()) {
            val wasFull = cargo.isFull
            val result =
                Mining.resolve(
                    sectorWorld,
                    currentSector,
                    ship.position,
                    cargo,
                    fieldDepletion,
                    MineAction.MINE,
                    miningParams,
                )
            if (result.minedUnits > 0) {
                cargo = result.cargo
                fieldDepletion = result.fieldDepletion
                recordTutorialEvent(TutorialEvent.GATHERED) // UC36: a productive mining tick completes GATHER
                // UC31: mining cue, throttled (mining resolves every held frame; pulse, don't buzz — AC#1).
                miningSfxCooldown -= dt
                if (miningSfxCooldown <= 0f) {
                    audio.play(Sfx.MINING_TICK)
                    miningSfxCooldown = MINING_SFX_INTERVAL
                }
                val fieldEmptied = (result.fieldDepletion[field.id]?.values?.sum() ?: 0) <= 0
                // Event-driven autosave on the two key transitions (UC06): the hold just filled, or the
                // field just emptied. Each fires at most once because subsequent ticks are no-ops.
                if (cargo.isFull && !wasFull) {
                    autosave.onEvent("cargo-full")
                } else if (fieldEmptied) {
                    autosave.onEvent("field-depleted")
                }
            }
        }

        // UC10 active scan: same pure [Scanning] the replay harness uses. An edge-triggered SCAN tap
        // reveals every hidden contact in the current sector within the active ship's sensor range
        // (ShipStats.scanRange — its type + sensor loadout, so a SCANNER_I upgrade widens it). The
        // resolver only ever unions (revealed contacts never re-hide, AC#4), and returns the SAME set
        // instance when nothing new is found, so a fruitless scan costs no allocation or autosave.
        // Gated to in-flight (skipped while docked, like mining); the play screen runs in flight, so
        // this is normally a pass-through guard.
        if (scanRequested) {
            scanRequested = false
            if (dockedStation == null) {
                val scanRange = ShipStats.scanRange(active.type, active.loadout)
                val updated =
                    Scanning.resolve(sectorWorld, currentSector, ship.position, scanRange, revealedContacts, ScanAction.SCAN)
                if (updated !== revealedContacts) {
                    val newlyRevealed = updated.size - revealedContacts.size
                    revealedContacts = updated
                    // A scan that reveals something is a key world event (UC10 AC#4) — persist it now.
                    autosave.onEvent("scan")
                    logger.info(
                        WORLD_TAG,
                        "Scan revealed $newlyRevealed hidden contact(s) in sector ${currentSector.value}; " +
                            "known=${revealedContacts.size}",
                    )
                }
            }
        }

        // UC12 radio broadcasts: surface range-based mission offers while in flight (mirrors the
        // dock/mine context panels and the pure [MissionGenerator.radioOffers], which itself mirrors
        // [Scanning.contactsInRange]). Recompute the in-range, un-taken offers each frame; an
        // edge-triggered ACCEPT takes the first via the same pure [Missions.resolve] the sim uses.
        val radioOffers =
            if (dockedStation == null) {
                // UC14: the reputation gate is a SEPARATE filter applied AFTER the takenIds filter (one of
                // the three symmetric sites) — generation stays a pure function of static state; this only
                // hides offers the player hasn't unlocked.
                MissionGenerator.radioOffers(
                    sectorWorld,
                    currentSector,
                    ship.position,
                    missionParams,
                    MvpSectorMap.BOUNTY_CONTRACTS,
                    bountyParams,
                )
                    .filter { it.id !in missionLog.takenIds }
                    .filter { ReputationGate.isAvailable(it, reputation) }
            } else {
                emptyList()
            }
        val radioOffer = radioOffers.firstOrNull()
        actionCluster.setActionAvailable(ActionCluster.Action.RADIO, radioOffer != null)
        // UC26: refresh the arc layout for this frame's available set, then the relocated status readout.
        // Both are cheap, dirty-gated no-ops when nothing changed (60 FPS budget).
        actionCluster.relayout()
        updateContextReadout(available, field, radioOffer)
        if (radioAcceptRequested) {
            radioAcceptRequested = false
            val offer = radioOffers.firstOrNull()
            if (offer != null) {
                val result =
                    Missions.resolve(
                        missionLog,
                        radioOffers,
                        MissionOrder.Accept(offer.id),
                        null,
                        cargo,
                        credits,
                        missionParams,
                        reputation,
                        reputationParams,
                    )
                if (result.changed) {
                    missionLog = result.log
                    applyCreditChange(result.credits)
                    cargo = result.cargo
                    reputation = result.reputation
                    autosave.onEvent("mission-accept")
                    audio.play(Sfx.MISSION_ACCEPT) // UC31: mission-accept cue (AC#1)
                    notifications.enqueue(GameNotifications.missionAccepted()) // UC35 (AC#1)
                    recordTutorialEvent(TutorialEvent.MISSION_ACCEPTED) // UC36: completes the ACCEPT_MISSION step
                    logger.info(WORLD_TAG, "Accepted radio mission ${offer.id.value} in sector ${currentSector.value}")
                }
            }
        }

        // UC12 courier timers: decrement the tick-based model timer at a frame-rate-independent cadence
        // (one [Missions.advance] per MISSION_TICK_SECONDS of accumulated dt). advance() returns the same
        // instances when no courier is active, so this is free pre-UC12 and when only mining missions are
        // held; a courier expiry (terminal transition) folds the new log + penalised credits and autosaves.
        missionTickAccumulator += dt
        while (missionTickAccumulator >= MISSION_TICK_SECONDS) {
            missionTickAccumulator -= MISSION_TICK_SECONDS
            val advanced = Missions.advance(missionLog, credits, cargo, missionParams, reputation, reputationParams)
            missionLog = advanced.log
            applyCreditChange(advanced.credits)
            reputation = advanced.reputation
            if (advanced.changed) {
                autosave.onEvent("mission-expired")
                notifications.enqueue(GameNotifications.missionFailedTimeout()) // UC35 (AC#1)
                logger.info(WORLD_TAG, "A courier mission timed out in sector ${currentSector.value}")
            }
        }

        // UC42 collect: fold any proximity salvage pickup into credits + cargo. Runs once per tick,
        // UNCONDITIONALLY, after movement and BEFORE the combat branch (post-movement position) — so a
        // tick that ends combat (and would early-return inside the combat branch) can never skip the
        // collection. This ordering is mirrored byte-for-byte by the test-set Simulation (project rule #1).
        collectSalvage(ship.position)

        // UC13 combat: edge-triggered natural encounter spawn on the outside→inside zone crossing, then
        // the paced shared [Combat.step]. Hostiles/projectiles/combat-RNG are transient (regenerated, not
        // persisted); only the player's section damage + last docked station are durable.
        runCombat(dt, ship.position)

        // Periodic autosave: accumulate this frame; the controller enqueues a save only every
        // interval (no per-frame I/O or logging — coding-guidelines § concurrency/logging).
        autosave.update(dt)

        // UC35: advance the notification feed only here, inside the gated sim advance — so toasts age and
        // auto-dismiss in sim time and FREEZE under pause / the destruction screen exactly like everything
        // else (AC#3). The draw side reads visible() in renderFrame regardless, so frozen toasts stay shown.
        notifications.update(dt)

        // UC36: drain and apply this frame's recorded tutorial events HERE, inside the gated advance and
        // beside the notification update — so the onboarding only ever progresses while the sim is running
        // (frozen under pause/destruction, AC#4) and never touches the deterministic simulation itself; it
        // merely observes events the sim already produced. Cross-screen seams (trade/refuel/board-accept)
        // record while docked; those drain on the next flight frame.
        applyTutorialEvents()
    }

    /**
     * Draw the frame in BOTH paused and running states (UC32): camera follow, the world/HUD/minimap draws,
     * the control-visibility pass, the Scene2D stage, and the map + pause overlays. The ship is taken from
     * [renderShip] (the live, frozen-while-paused Box2D transform) so the scene stays visible while paused.
     */
    private fun renderFrame(
        dt: Float,
        mapOpen: Boolean,
        paused: Boolean,
        renderShip: ShipKinematics,
    ) {
        // Camera follows the ship so it stays centred on the unbounded map (AC#1/#7).
        worldCamera.position.set(renderShip.position.x, renderShip.position.y, 0f)
        worldCamera.update()

        // UC27: deepest-space backdrop from the design-system palette (void-900, AC#8).
        Gdx.gl.glClearColor(Palette.SURFACE_APP.r, Palette.SURFACE_APP.g, Palette.SURFACE_APP.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val viewportWidth = Gdx.graphics.width.toFloat()
        val viewportHeight = Gdx.graphics.height.toFloat()
        val sector = sectorWorld.sector(currentSector)
        // Parallax keyed off the camera's world position conveys motion (AC#11).
        starfield.render(renderShip.position.x, renderShip.position.y, viewportWidth, viewportHeight)
        // Ring overlays first (mining / trigger radii), then the per-POI base markers on top: the
        // shared WorldObjectRenderer draws a glyph for every POI in the sector — stations included
        // (previously unrendered in-world) and revealed hidden contacts — keyed by WorldGlyphs.forPoi.
        asteroidFieldRenderer.render(worldCamera, sector.asteroidFields)
        gateRenderer.render(worldCamera, sector.gates)
        worldObjectRenderer.render(worldCamera, sector.pois, revealedContacts)
        shipRenderer.render(worldCamera, renderShip)
        // UC13: hostiles + projectiles in world space (no-op while combat is inactive).
        hostileRenderer.render(worldCamera, combat)
        // UC07/UC34: the expanded HUD readout block. The pure HudViewModel is assembled each frame from
        // the live sim state — speed/heading/fuel plus credits, cargo fill, the current sector name and
        // the active-mission objective — so every readout updates live (UC34 AC#1/#2/#4); the renderer
        // only formats and draws it.
        hudRenderer.render(
            HudViewModel.build(
                speed = renderShip.speed,
                headingRadians = renderShip.headingRadians,
                fuelLevel = fuel.level,
                fuelCapacity = fuel.capacity,
                lowFuel = fuel.isLow(fuelParams),
                inCombat = combat.active,
                credits = credits,
                cargo = cargo,
                sectorName = sector.displayName,
                missionLog = missionLog,
            ),
            viewportWidth,
            viewportHeight,
        )
        // The minimap renders every transponder POI (gates + stations) plus any revealed hidden
        // contacts (UC10), keyed by contact kind. It anchors top-right (UC22), fitting its size above
        // the bottom controls; reservedBottom is the worst-case bottom-control top (handedness-agnostic)
        // so it can never overlap the joystick or action cluster on either side.
        minimap.render(
            sector.pois,
            renderShip.position,
            sector.contentExtent,
            revealedContacts,
            viewportWidth,
            viewportHeight,
            reservedBottom = bottomControlBand(),
        )
        // UC22: the relocated top-left settings/handedness button shares its band with the combat-only
        // ship schematic below, so hide it for the duration of an encounter — handedness isn't changed
        // mid-combat — keyed on the same combat-active flag that gates the schematic. It reappears when
        // combat ends. An invisible Scene2D actor also stops receiving touch, so nothing under it leaks.
        // UC23: also hide it — and every gameplay control — while the zoomed map overlay is open, so
        // nothing shows through / under the 80%-opaque backdrop and no stray tap reaches a flight control
        // (an invisible actor receives no touch; the full-screen dismiss actor on top catches taps). Only
        // the controls hide — the simulation keeps running (the overlay is LIVE).
        // UC32: while paused the settings panel is the Settings sub-view of the pause overlay — visible
        // only when the player taps Settings ([pauseSettingsShown]), and NOT ANDed with combat/map (so
        // pausing mid-combat then opening Settings works). While running it keeps the pre-UC32 rule.
        settingsOverlay.actor.isVisible = if (paused) pauseSettingsShown else (!combat.active && !mapOpen)
        // UC33: a pending destruction screen owns the foreground — force the settings panel hidden under it
        // (the destruction overlay offers only CONTINUE, no settings sub-view), regardless of the above.
        if (destructionState.isPending) settingsOverlay.actor.isVisible = false
        // UC26: the whole action arc (FIRE + every contextual button) and the context readout hide with
        // the rest of the controls only while the map overlay is open — NOT during combat, so FIRE stays
        // visible and enabled throughout an encounter (UC26 AC#3/#6). UC32: they also hide while paused, so
        // a held stick/FIRE can't sit live under the pause backdrop (pitfall#3). The arc's own per-action
        // availability (set above) governs which contextual buttons show when neither overlay is open.
        val controlsHidden = mapOpen || paused || destructionState.isPending
        if (controlsHidden) {
            joystick.actor.isVisible = false
            actionCluster.actor.isVisible = false
            contextReadout.isVisible = false
        } else {
            joystick.actor.isVisible = true
            actionCluster.actor.isVisible = true
        }
        mapDismissActor.isVisible = mapOpen
        // UC36: the first-run tutorial hint band shows only while the gameplay controls are up (so it is
        // suppressed under any full-screen overlay — map/pause/destruction — AC#1 composes with UC32/UC35)
        // and a tutorial step is still active. It STAYS visible in combat (the FIRE step needs it). When
        // shown, refresh the copy and emphasise the step's control (visual only — never gates input, AC#4);
        // when hidden, clear the emphasis so the controls return to their normal tint.
        val tutorialStep = tutorialState.activeStep
        val showTutorial = !controlsHidden && tutorialStep != null
        tutorialOverlay.actor.isVisible = showTutorial
        if (showTutorial) tutorialOverlay.setStep(tutorialStep)
        actionCluster.setHighlightedAction(if (showTutorial) arcHighlightFor(tutorialStep) else null)
        joystick.setHighlighted(showTutorial && tutorialStep?.highlight == TutorialHighlight.JOYSTICK)
        // UC32: the HUD pause button is reachable any time the game is running and no overlay is open —
        // including mid-combat (AC#1); it hides while either overlay is up. The modal pause overlay (dim
        // backdrop + buttons) shows exactly while paused.
        pauseButton.isVisible = !mapOpen && !paused
        // UC33: the destruction screen also suppresses the HUD pause button (the player must CONTINUE, not
        // open pause, while destroyed) and shows the modal destruction overlay exactly while one is pending.
        if (destructionState.isPending) pauseButton.isVisible = false
        pauseOverlay.actor.isVisible = paused
        destructionOverlay.actor.isVisible = destructionState.isPending
        // UC13: the per-section ship schematic (HUD) — only while a combat encounter is live.
        if (combat.active) {
            val active = fleet.active
            shipSchematicRenderer.render(
                active.sectionDamage,
                ShipStats.sectionHpMap(active.type, active.loadout),
                viewportWidth,
                viewportHeight,
            )
        }

        stage.act(dt)
        stage.draw()

        // UC35: draw the transient toasts above the HUD/controls but suppressed under any full-screen modal
        // — the zoomed map overlay, the pause backdrop, or the destruction screen ([controlsHidden]) — so a
        // toast never bleeds over a modal (AC#4). The queue keeps holding them; they reappear once cleared.
        if (!controlsHidden) {
            notificationRenderer.render(notifications.visibleWithProgress(), viewportWidth, viewportHeight)
        }

        // UC23: the zoomed map overlay is drawn LAST, on top of the gameplay and the (now-hidden) HUD
        // controls — a full-screen dim backdrop (scene faintly visible, AC#3) plus a full-height map
        // panel showing more sector area than the minimap (AC#2/#4). It is a pure overlay: the sim above
        // already ran this frame, so opening the map neither pauses nor corrupts game state (AC#6; the
        // LIVE-in-combat tradeoff is documented in docs/design/world-and-sector.md).
        if (mapOpen) {
            mapOverlay.render(
                sector.pois,
                renderShip.position,
                sector.contentExtent,
                revealedContacts,
                viewportWidth,
                viewportHeight,
            )
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        stage.viewport.update(width, height, true)
        worldCamera.viewportWidth = width.toFloat()
        worldCamera.viewportHeight = height.toFloat()
        worldCamera.update()
        layoutControls()
    }

    /**
     * UC37: re-apply the (already-updated global) [com.orbitalfrontier.render.UiScale] to this stage's
     * Scene2D viewport and re-flow the controls, so a UI-scale change made in the in-flight settings panel
     * rescales the on-screen chrome live without leaving flight. The world camera is untouched (the
     * playfield stays 1:1, ADR 0015), and the screen-space HUD/minimap renderers — which captured the
     * factor at construction — reflect the new scale on the next screen rebuild (ADR 0025).
     */
    private fun applyUiScaleLive() {
        (stage.viewport as? ScreenViewport)?.applyUiScale()
        stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        layoutControls()
    }

    /**
     * UC39: re-apply the (already-updated global) [com.orbitalfrontier.render.TextScale] to this screen's
     * skin font and re-flow the open settings panel so a text-size change made in flight resizes the UI
     * text live. The settings panel is the surface visible when this fires, so invalidating its scroll
     * pane's hierarchy is enough to re-measure/re-flow the resized labels (the shared skin font carries the
     * new scale to every other widget on its next layout). The in-flight HUD/world-space text follows
     * UiScale only and is intentionally unaffected (see [com.orbitalfrontier.render.TextScale]).
     */
    private fun applyTextScaleLive() {
        skin.applyTextScale()
        settingsOverlay.actor.invalidateHierarchy()
    }

    /** Position the controls for the current handedness (AC#7/#8). Idempotent. */
    private fun layoutControls() {
        val layout = ControlsLayout.forHandedness(handedness)
        val screenWidth = stage.viewport.worldWidth
        val screenHeight = stage.viewport.worldHeight

        joystick.actor.setSize(JOYSTICK_SIZE, JOYSTICK_SIZE)
        joystick.actor.setPosition(sideX(layout.movementStickSide, screenWidth, JOYSTICK_SIZE), MARGIN)

        // UC26: anchor the fixed-footprint action arc in its corner and pivot it on that corner. The
        // footprint is constant (independent of the visible button count), so this position and the
        // minimap reservation never drift as contextual buttons appear/disappear.
        actionCluster.actor.setSize(actionCluster.prefWidth, actionCluster.prefHeight)
        actionCluster.actor.setPosition(sideX(layout.actionClusterSide, screenWidth, actionCluster.prefWidth), MARGIN)
        actionCluster.setSide(layout.actionClusterSide)
        actionCluster.relayout()

        // UC22: the minimap now owns the top-right corner, so the settings/handedness button moves to
        // the top-LEFT band — the clear vertical gap between the HUD readout block at the top
        // (HUD_BLOCK_HEIGHT) and the worst-case left-edge control cluster at the bottom
        // (bottomControlBand()). It is centred in that band so the clearance is symmetric on both sides
        // (≥ 16 world units at supported sizes), and the worst-case band is handedness-agnostic, so the
        // button stays put when the player flips handedness.
        settingsOverlay.actor.setSize(SETTINGS_WIDTH, SETTINGS_HEIGHT)
        val leftBandBottom = bottomControlBand()
        val leftBandTop = screenHeight - HUD_BLOCK_HEIGHT
        settingsOverlay.actor.setPosition(
            MARGIN,
            (leftBandBottom + leftBandTop) / 2f - SETTINGS_HEIGHT / 2f,
        )

        positionContextReadout()

        // UC23: place the invisible minimap tap target exactly on the drawn minimap panel — same
        // geometry source (MinimapRenderer.panelRect) called with the SAME world-unit args the per-frame
        // draw uses (stage world size == pixel viewport / UiScale.factor) — so tapping the visible
        // minimap opens the overlay. The dismiss actor covers the whole stage so any tap dismisses it.
        val minimapRect =
            minimap.panelRect(
                vpWidth = screenWidth,
                vpHeight = screenHeight,
                reservedBottom = bottomControlBand(),
            )
        minimapTapTarget.setBounds(minimapRect.x, minimapRect.y, minimapRect.width, minimapRect.height)
        mapDismissActor.setBounds(0f, 0f, screenWidth, screenHeight)

        // UC32: the pause button sits centred along the TOP edge — clear of the top-left HUD readout /
        // settings band, the top-right minimap, and the combat ship-schematic — so it stays reachable
        // (including mid-combat) without overlapping another HUD element at the 960×540 floor.
        pauseButton.setSize(PAUSE_BUTTON_WIDTH, PAUSE_BUTTON_HEIGHT)
        pauseButton.setPosition(
            (screenWidth - PAUSE_BUTTON_WIDTH) / 2f,
            screenHeight - MARGIN - PAUSE_BUTTON_HEIGHT,
        )
        // UC32: the modal pause overlay fills the stage and re-centres its button column.
        pauseOverlay.resize(screenWidth, screenHeight)
        // UC33: the modal destruction overlay fills the stage and re-centres its consequence column.
        destructionOverlay.resize(screenWidth, screenHeight)
        // UC36: place the tutorial hint band in the bottom-centre clear strip above the corner controls.
        tutorialOverlay.resize(screenWidth, screenHeight)
    }

    /**
     * Centre the relocated context readout (UC26) horizontally in the clear bottom band between the two
     * corner controls, floored at [MARGIN]. Re-run on layout changes and after a text change so it stays
     * centred as its width varies. The arc and joystick own the bottom corners; the readout sits in the
     * gap between them, so it overlaps neither control nor the top-right minimap (UC26 AC#9).
     */
    private fun positionContextReadout() {
        contextReadout.pack()
        contextReadout.setPosition(
            (stage.viewport.worldWidth - contextReadout.width) / 2f,
            MARGIN,
        )
    }

    /**
     * Rebuild the context readout from this frame's available contextual actions (UC26): the station
     * name (DOCK), cargo fill (MINE) and mission reward (RADIO) the retired panels used to show, joined
     * into one status line and shown only while at least one is available. No allocation on the common
     * path where nothing is in range — preserves the 60 FPS budget (coding-guidelines § performance).
     */
    private fun updateContextReadout(
        station: Station?,
        field: AsteroidField?,
        offer: Mission?,
    ) {
        if (station == null && field == null && offer == null) {
            if (contextReadout.isVisible) contextReadout.isVisible = false
            return
        }
        val parts =
            buildList {
                if (station != null) add("IN RANGE: ${station.displayName}")
                if (field != null) add("MINE ${cargo.usedUnits}/${cargo.capacity}")
                if (offer != null) add("RADIO: ${offer.rewardCredits}cr mining")
            }
        contextReadout.isVisible = true
        val text = parts.joinToString("   •   ")
        if (!contextReadout.textEquals(text)) {
            contextReadout.setText(text)
            positionContextReadout()
        }
    }

    private fun sideX(
        side: ScreenSide,
        screenWidth: Float,
        widgetWidth: Float,
    ): Float = if (side == ScreenSide.LEFT) MARGIN else screenWidth - MARGIN - widgetWidth

    /**
     * The world-space top of the bottom control band (UC22): the higher of the joystick and the action
     * cluster, sitting at [MARGIN] above the screen floor. Handedness-agnostic — it always uses the
     * worst-case (tallest) of the two controls, so it bounds whichever control ends up on a given side.
     * Used both to reserve the minimap's bottom clearance and to anchor the top-left settings band.
     */
    private fun bottomControlBand(): Float = MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

    /**
     * The single chokepoint every credit mutation routes through (UC35 AC#1): set the wallet and, on a real
     * change, enqueue the gain/loss toast from the pure [GameNotifications.creditDelta]. Centralising it here
     * means a credit gain or loss surfaces consistently no matter which system moved the balance (a trade,
     * a hire, an outfit, a ship purchase, a fuel buy, a mission reward, a courier penalty, a station build),
     * and no individual site has to remember to notify. A no-change call enqueues nothing (creditDelta → null).
     */
    private fun applyCreditChange(newCredits: Long) {
        GameNotifications.creditDelta(credits, newCredits)?.let(notifications::enqueue)
        credits = newCredits
    }

    /**
     * Enqueue a styled error toast for an economy action that did NOT happen (UC40 AC#3), classifying it
     * via the pure [PurchaseGate]: when a [cost] is known and exceeds the live balance the cue is the
     * dedicated INSUFFICIENT-CREDITS error; otherwise (an affordable-but-invalid action — full hold, no free
     * slot, item not offered, already active — or an unknown cost) it is the generic ACTION-REJECTED error.
     * Replaces the old silent/bare-string no-op so a refused tap always surfaces a clear, styled message.
     * The economy screens already pre-gate the *unaffordable* case at the tap, so this mostly fires for the
     * affordable-but-invalid branch; the classification keeps it correct either way.
     */
    private fun enqueueEconomyError(cost: Long?) {
        val insufficient = cost != null && PurchaseGate.evaluate(cost, credits) == SpendDecision.INSUFFICIENT
        notifications.enqueue(
            if (insufficient) GameNotifications.insufficientCredits() else GameNotifications.actionRejected(),
        )
    }

    /**
     * Record a tutorial [event] from a gameplay seam (UC36). It is only buffered while the tutorial is
     * still running, so a finished/skipped onboarding adds no work; the buffer is drained and applied once
     * per frame inside [advanceSimulation] (see [applyTutorialEvents]). Recording — not applying — at the
     * seam keeps the state advance on the gated per-frame path, so it freezes under pause/destruction and
     * never perturbs the deterministic simulation (AC#4). Safe to call from the cross-screen hub methods
     * (trade/refuel/board-accept): those buffer while docked and drain on the next flight frame.
     */
    private fun recordTutorialEvent(event: TutorialEvent) {
        if (!tutorialState.isComplete) pendingTutorialEvents.add(event)
    }

    /**
     * Drain this frame's recorded [pendingTutorialEvents] and advance the [tutorialState] through them in
     * order via the pure [TutorialState.advancedBy] (a non-matching event is a no-op), then persist the
     * completion flag once if the tutorial just finished. Called once per frame from the gated advance.
     */
    private fun applyTutorialEvents() {
        if (pendingTutorialEvents.isEmpty()) return
        for (event in pendingTutorialEvents) {
            tutorialState = tutorialState.advancedBy(event)
        }
        pendingTutorialEvents.clear()
        persistTutorialIfComplete()
    }

    /**
     * Persist the first-run-tutorial completion flag exactly once, the moment the tutorial reaches its
     * terminal state (finished, skipped, or skip-all'd) — through the SAME [saveExecutor] single writer the
     * settings/autosave use, so it never blocks the render thread (AC#3). Idempotent: the
     * [tutorialCompletionPersisted] guard means repeated completions (or a replay that re-completes) write
     * at most once per transition.
     */
    private fun persistTutorialIfComplete() {
        if (tutorialState.isComplete && !tutorialCompletionPersisted) {
            tutorialCompletionPersisted = true
            saveExecutor.execute { settingsRepository.saveTutorialCompleted(true) }
            logger.info(TAG, "First-run tutorial complete; flag persisted")
        }
    }

    /**
     * Replay the tutorial from the first step (UC36 AC#3), invoked from the settings panel's REPLAY
     * TUTORIAL button. Resets the in-memory progression only; the persisted first-run flag stays set (a
     * replay is a one-session re-run, not a re-arm of first launch), and re-completing it simply re-persists
     * the already-set flag harmlessly. Runs on the render thread; the overlay surfaces next frame (it is
     * hidden under the pause backdrop the player taps this from, and appears on resume).
     */
    private fun replayTutorial() {
        tutorialState = TutorialState.NEW
        tutorialCompletionPersisted = false
        pendingTutorialEvents.clear()
        logger.info(TAG, "Replaying first-run tutorial from settings")
    }

    /**
     * Map a [TutorialStep]'s [TutorialHighlight] to the action-arc button to emphasise, or null when the
     * step points at the joystick (handled separately) — so the arc tints DOCK/RADIO/MINE/FIRE for the
     * matching step and nothing for STEER. Visual only (UC36 AC#2/#4).
     */
    private fun arcHighlightFor(step: TutorialStep?): ActionCluster.Action? =
        when (step?.highlight) {
            TutorialHighlight.DOCK -> ActionCluster.Action.DOCK
            TutorialHighlight.RADIO -> ActionCluster.Action.RADIO
            TutorialHighlight.MINE -> ActionCluster.Action.MINE
            TutorialHighlight.FIRE -> ActionCluster.Action.FIRE
            TutorialHighlight.JOYSTICK, null -> null
        }

    /**
     * Run UC13 combat for this frame: an edge-triggered natural-encounter spawn on the outside→inside
     * crossing of an authored zone (suppressed while docked or already fighting), then the paced shared
     * [Combat.step]. [playerPosition] is the post-gate ship position this frame; the previous frame's
     * position drives the edge detection.
     */
    private fun runCombat(
        dt: Float,
        playerPosition: Vec2,
    ) {
        if (!combat.active && dockedStation == null) {
            for (zone in MvpSectorMap.encounterZones(currentSector)) {
                val spawned =
                    EncounterSpawner.naturalSpawn(combat, zone, previousShipPosition, playerPosition, combatSpawnTick, combatParams)
                if (spawned !== combat) {
                    combat = spawned
                    combatSpawnTick++
                    autosave.onEvent("encounter")
                    logger.info(WORLD_TAG, "Hostiles ambushed the player in zone ${zone.id} (sector ${currentSector.value})")
                    break
                }
            }
        }
        // UC41 (a): edge-triggered BOUNTY spawn. For each ACTIVE bounty whose target zone is in this sector,
        // detect the SAME outside->inside crossing the natural spawner uses, then inject the contracted
        // hostiles via [EncounterSpawner.missionSpawn] (no positional crossing of its own — the trigger is
        // here). Seeded by [combatSpawnTick] (the device's spawn-seed convention; the sim uses state.tick),
        // and suppressed while a fight is already active so a natural and a bounty encounter can't stack.
        // The spawned [CombatState.zoneId] equals the bounty's targetZoneId, so kills attribute back to it.
        if (!combat.active && dockedStation == null) {
            for (bounty in missionLog.activeMissions) {
                if (bounty.type != MissionType.BOUNTY) continue
                val zoneId = bounty.targetZoneId ?: continue
                val zone = MvpSectorMap.bountyTargetZone(zoneId) ?: continue
                if (zone.sectorId != currentSector.value) continue
                if (zone.contains(previousShipPosition) || !zone.contains(playerPosition)) continue
                val spawned =
                    EncounterSpawner.missionSpawn(
                        combat,
                        zoneId,
                        zone.archetypeId,
                        zone.hostileCount,
                        playerPosition,
                        combatSpawnTick,
                        combatParams,
                    )
                if (spawned !== combat) {
                    combat = spawned
                    combatSpawnTick++
                    autosave.onEvent("bounty-encounter")
                    logger.info(WORLD_TAG, "Bounty hostiles spawned in zone $zoneId (sector ${currentSector.value})")
                    break
                }
            }
        }
        previousShipPosition = playerPosition

        // UC35: entered-combat toast on the combat-active false->true edge (AC#1). The CombatEvent hierarchy
        // has no "encounter started" event — only the natural spawn above flips combat live — so this edge is
        // detected here, symmetric with the left-combat toast that flows through forCombatEvent on
        // EncounterCleared. Captured BEFORE the step loop below, so a same-frame spawn-then-clear still reads
        // as a rising edge here (and the clear is caught as a falling edge next frame).
        if (combat.active && !previousCombatActive) {
            notifications.enqueue(GameNotifications.enteredCombat())
        }
        previousCombatActive = combat.active

        if (!combat.active) {
            combatTickAccumulator = 0f
            return
        }
        // Pace the tick-based model off accumulated real time (frame-rate-independent), capped per frame
        // so a long stall can't run an unbounded number of catch-up ticks. The model is the authority;
        // the replay harness steps it at a fixed dt instead.
        combatTickAccumulator += dt
        var ticks = 0
        while (combat.active && combatTickAccumulator >= COMBAT_DT && ticks < MAX_COMBAT_TICKS_PER_FRAME) {
            combatTickAccumulator -= COMBAT_DT
            ticks++
            stepCombatOnce()
        }
    }

    /** One [Combat.step]: build the player's combat input, fold the result back, handle destruction/clear. */
    private fun stepCombatOnce() {
        val active = fleet.active
        val playerInput =
            PlayerCombatInput(
                kinematics = physics.readKinematics(),
                weapons = ShipStats.weaponLoadout(active.type, active.loadout),
                maxSectionHp = ShipStats.sectionHpMap(active.type, active.loadout),
                crew = active.crew,
                sectionDamage = active.sectionDamage,
            )
        val fireAction = if (actionCluster.isFirePressed()) FireAction.FIRE else FireAction.NONE
        // UC36: holding FIRE in an encounter completes the FIRE tutorial step (visual/observational only —
        // the fire intent itself is unchanged). Recorded into the pending buffer; applied at frame end.
        if (fireAction == FireAction.FIRE) recordTutorialEvent(TutorialEvent.FIRED)
        // UC41 (b): capture the encounter's zone id BEFORE the step — a destroyed/cleared encounter resets
        // combat to NONE (zoneId ""), so the attribution key must be read pre-step.
        val preStepZoneId = combat.zoneId
        // UC42: snapshot the hostiles BEFORE the step too — a [CombatEvent.HostileDestroyed] carries only
        // the id, and the kill culls the hostile from the post-step state, so the kill position +
        // archetype (the salvage spawn inputs) must be read from this pre-step list.
        val preStepHostiles = combat.hostiles
        val result = Combat.step(combat, playerInput, fireAction, combatParams, COMBAT_DT)
        combat = result.combat

        // UC31: play SFX for this tick's combat events (weapon fire, hostile hit, hostile destroyed) from
        // the pure model's structured output — audio is event-driven, the simulation stays audio-free (AC#4).
        result.events.forEach { event -> Sfx.forCombatEvent(event)?.let(audio::play) }

        // UC35: surface combat-boundary toasts from the SAME structured events (left-combat on
        // EncounterCleared; every per-shot event maps to null upstream so the feed never floods — AC#1).
        result.events.forEach { event -> GameNotifications.forCombatEvent(event)?.let(notifications::enqueue) }

        // Fold the player's new section damage onto the active ship (=== check skips a no-op tick).
        if (result.sectionDamage !== active.sectionDamage) {
            fleet = fleet.withActive(active.withSectionDamage(result.sectionDamage))
        }

        // UC41 (b): fold this tick's hostile kills into any matching ACTIVE bounty (auto-complete + pay).
        // Done BEFORE the destruction return so a kill that lands on the same tick the player dies still
        // counts (mirrored byte-for-byte in the test-set Simulation). killProgress is now in missionLog, so
        // it persists with the hostile-destroyed autosave below (normal path) or the respawn critical
        // autosave (destroyed path) — durable per kill. No-op (same instances) when there were no kills.
        val killsThisTick = result.events.count { it is CombatEvent.HostileDestroyed }
        if (killsThisTick > 0) {
            val bounty =
                BountyTracking.applyKills(
                    missionLog,
                    preStepZoneId,
                    killsThisTick,
                    credits,
                    cargo,
                    reputation,
                    reputationParams,
                )
            if (bounty.changed) {
                missionLog = bounty.log
                applyCreditChange(bounty.credits)
                reputation = bounty.reputation
            }
        }

        // UC42 (a): spawn one salvage wreck per kill at the hostile's pre-step position, loot rolled
        // deterministically from the encounter zone id + hostile id (independent of the combat RNG, so
        // the committed combat fixtures stay byte-identical). Done BEFORE the destruction return so a
        // kill on the death tick still drops its wreck — symmetric with the bounty fold above and
        // mirrored byte-for-byte in the test-set Simulation. No-op (same list) when nothing died.
        if (killsThisTick > 0) {
            val byId = preStepHostiles.associateBy { it.id }
            val destroyed =
                result.events.asSequence()
                    .filterIsInstance<CombatEvent.HostileDestroyed>()
                    .mapNotNull { event -> byId[event.id] }
                    .map { hostile -> DestroyedHostile(hostile.id, hostile.archetypeId, hostile.kinematics.position) }
                    .toList()
            val spawned = Salvage.spawn(salvage, nextSalvageId, preStepZoneId, destroyed)
            salvage = spawned.drops
            nextSalvageId = spawned.nextSalvageId
        }

        if (result.destroyed) {
            respawnPlayer()
            return
        }

        // Autosave the durable transitions: a destroyed hostile and the cleared encounter (the latter
        // also when every hostile broke off). Per-hit damage is caught by the periodic autosave.
        if (result.events.any { it is CombatEvent.HostileDestroyed }) autosave.onEvent("hostile-destroyed")
        if (!combat.active) {
            autosave.onEvent("combat-cleared")
            logger.info(WORLD_TAG, "Combat ended in sector ${currentSector.value}")
        }
    }

    /**
     * UC42 (b): collect any salvage within the pickup radius of [playerPosition] (the post-movement ship
     * position this frame) via the shared pure [Salvage.collect] — the same function the test-set
     * Simulation runs (project rule #1), so live and replayed pickup match. Credits route through the
     * single [applyCreditChange] chokepoint (so the gain toast surfaces like any other), resources flow
     * into [cargo] with a capacity-respecting partial fill, and a hold-full overflow surfaces the reused
     * "CARGO FULL" [GameNotifications.actionRejected] cue (no new NotificationKind). A value-moving pickup
     * triggers an event autosave so the collected loot is durable. A no-op tick (nothing in range, or a
     * full hold over a credit-less leftover) returns the same instances and does nothing.
     */
    private fun collectSalvage(playerPosition: Vec2) {
        val result = Salvage.collect(salvage, playerPosition, cargo, credits, combatParams.salvagePickupRadius)
        if (!result.collectedAny) return
        salvage = result.drops
        cargo = result.cargo
        applyCreditChange(result.credits)
        if (result.overflow) notifications.enqueue(GameNotifications.actionRejected("CARGO FULL"))
        autosave.onEvent("salvage-collected")
    }

    /**
     * Forgiving destruction respawn + consequence screen (UC13 AC#5 / UC33). Relocate the active ship to
     * the respawn point with a cargo-loss penalty and full repair via the pure [Respawn], re-seed the
     * Box2D body, and clear the encounter — exactly as before — then, instead of a silent teleport,
     * **surface the destruction**: build the pure [DestructionSummary], raise the [destructionState] gate
     * (which freezes the per-frame advance), and show the modal [destructionOverlay] the player must
     * acknowledge with CONTINUE (UC33 AC#1/#2/#3). The respawn is committed and **durably persisted now**
     * via [AutosaveController.onCriticalEvent], so a crash/close on the consequence screen reloads the
     * post-respawn state and the penalty is applied exactly once (UC33 AC#4). A held stick/FIRE is
     * neutralised (mirrors [openPause]) so it cannot sit live under the frozen overlay.
     */
    private fun respawnPlayer() {
        val active = fleet.active
        val destination = resolveRespawnLocation()
        val result = Respawn.respawn(destination.position, cargo, combatParams)
        cargo = result.cargo
        fleet =
            fleet.withActive(
                active.withSectionDamage(result.sectionDamage).copy(cargo = result.cargo, kinematics = result.kinematics),
            )
        physics.resetTo(result.kinematics)
        combat = result.combat
        combatTickAccumulator = 0f
        currentSector = destination.sector

        // UC33: neutralise any held flight input (mirrors openPause pitfall#3) so a stick/FIRE held at the
        // instant of destruction does not stick live under the frozen overlay, and stop the looping THRUST
        // cue so it cannot drone on while the sim is frozen.
        stage.cancelTouchFocus()
        if (previousThrusting) {
            audio.stopSfx(Sfx.THRUST)
            previousThrusting = false
        }

        // UC33: raise the consequence screen with the pure summary, then commit the respawn DURABLY before
        // the player acknowledges it (AC#4) — the event-driven analogue of the pause/exit blocking flush.
        val summary = DestructionSummary.from(result, destination.name)
        destructionOverlay.setSummary(summary)
        destructionState = destructionState.pending(summary)
        autosave.onCriticalEvent("respawn")
        logger.info(
            WORLD_TAG,
            "Player ship destroyed; respawned at ${destination.name} (lost ${result.unitsLost} cargo units)",
        )
    }

    /**
     * Acknowledge the destruction consequence screen (UC33 AC#3): clear the pending [destructionState] —
     * which un-freezes the simulation and hides the overlay next frame — returning control at the respawn
     * point where the ship already sits (the respawn was applied and persisted at the moment of
     * destruction). No-op when nothing is pending.
     */
    private fun continueAfterDestruction() {
        if (!destructionState.isPending) return
        destructionState = destructionState.cleared()
        logger.info(TAG, "Destruction acknowledged; resuming flight at the respawn point")
    }

    /**
     * The respawn destination on destruction (UC33): the last docked station's sector + position +
     * display name when it is still in the authored world; otherwise the **game-start point**
     * ([MvpSectorMap.START_SECTOR], origin) — which is exactly where a new game spawns
     * ([com.orbitalfrontier.ship.Fleet.starter] at [Vec2.ZERO]) — so a player destroyed before their
     * first dock is sent back to the start rather than stranded in deep space. The location name is read
     * from the authored world ([com.orbitalfrontier.world.Sector.displayName]), never hardcoded.
     */
    private fun resolveRespawnLocation(): RespawnLocation {
        val stationId = lastDockedStation
        if (stationId != null) {
            for (sector in sectorWorld.sectors) {
                val station = sector.station(stationId)
                if (station != null) return RespawnLocation(sector.id, station.position, station.displayName)
            }
        }
        val startSector = MvpSectorMap.START_SECTOR
        return RespawnLocation(startSector, Vec2.ZERO, sectorWorld.sector(startSector).displayName)
    }

    /** The resolved respawn destination (UC33): which sector + position to respawn at, and its display name. */
    private data class RespawnLocation(
        val sector: SectorId,
        val position: Vec2,
        val name: String,
    )

    /**
     * The live world snapshot (current sector, the fleet with the active ship's kinematics read back
     * from the Box2D body, dock state, and credits) used by the [AutosaveController] (UC04/UC05 AC#4;
     * UC09 AC#6). Called on the render thread, where touching the body is safe; the returned
     * [WorldState] is immutable and handed to the save executor thread. The active ship's live
     * kinematics/cargo/fuel are folded back onto it (the loadout is unchanged, so capacities stay as
     * derived).
     */
    fun currentWorldState(): WorldState {
        val active = fleet.active.copy(kinematics = physics.readKinematics(), cargo = cargo, fuel = fuel)
        return WorldState(
            currentSector,
            fleet.withActive(active),
            dockedStation,
            fieldDepletion,
            credits,
            revealedContacts,
            missionLog,
            // Combat (UC13) is transient — handed through so a snapshot is complete, but the repository
            // never persists it (a reload starts with no encounter). lastDockedStation IS persisted.
            combat = combat,
            // Salvage (UC42) is transient too — handed through for snapshot completeness, but the
            // repository never persists it (a reload starts with no pending wrecks), like combat.
            salvage = salvage,
            nextSalvageId = nextSalvageId,
            lastDockedStation = lastDockedStation,
            // Reputation (UC14): the live per-faction standing, folded onto the snapshot for the autosave.
            reputation = reputation,
            // Owned stations (UC15): the live registry, folded onto the snapshot for the autosave.
            stations = stations,
            // Play time (UC38 AC#1): the accumulated wall-of-play seconds, folded onto the snapshot so each
            // save/autosave persists it and the slot list shows it.
            playTimeSeconds = playTimeSeconds,
        )
    }

    /**
     * Accumulate real play time (UC38 AC#1) from this frame's [dt]. Called only while the sim advances
     * (not paused / not on the destruction screen), so it tracks play time, not wall-clock-while-idle. The
     * fractional remainder is carried so sub-second frames are not lost; whole seconds roll into
     * [playTimeSeconds]. Render-thread only, allocation-free — it must not perturb the 60 FPS budget.
     */
    private fun accumulatePlayTime(dt: Float) {
        playTimeAccumulator += dt
        if (playTimeAccumulator >= 1f) {
            val wholeSeconds = playTimeAccumulator.toLong()
            playTimeSeconds += wholeSeconds
            playTimeAccumulator -= wholeSeconds.toFloat()
        }
    }

    /** The player's current credit balance (UC08) — read by the station trade desk for its readout. */
    fun creditsBalance(): Long = credits

    /** The active ship's current cargo (UC08) — read by the trade desk for its per-resource held counts. */
    fun cargoSnapshot(): Cargo = cargo

    /**
     * Execute one trade [order] against the **docked** station's market via the pure [Trading.resolve]
     * (UC08 AC#3) — the economy analogue of [refuel]/[undock]. The station trade desk
     * ([com.orbitalfrontier.screen.TradeScreen]) routes BUY/SELL taps here. The market is the docked
     * station's authored [com.orbitalfrontier.world.Station.market]; when not docked (or the station is
     * unresolvable) the market is null and [Trading.resolve] no-ops, so trading is implicitly gated on
     * being docked (the trade desk is only reachable while docked anyway).
     *
     * On a real trade it folds the new credits + cargo back in, logs one INFO line under the "Economy"
     * tag, and autosaves the event so the trade is durable; a no-op tap (unaffordable, hold full,
     * nothing to sell, not offered) changes nothing and is not persisted. **Buy-hydrogen → fuel (AC#5)**
     * is compositional: bought Hydrogen lands in the cargo hold and is converted to fuel by the
     * existing hub REFUEL ([refuel] → [Refueling.resolve]); no special-case path is needed here.
     */
    fun trade(order: TradeOrder) {
        val market = dockedStation?.let { sectorWorld.sector(currentSector).station(it)?.market }
        val result = Trading.resolve(credits, cargo, market, order)
        if (result.tradedUnits <= 0) {
            logger.info(ECONOMY_TAG, "Trade requested but nothing changed hands (unaffordable, hold full, nothing to sell, or not offered)")
            // UC40 AC#3: a refused BUY surfaces a styled error (cost = unit buy price × units; null for a SELL).
            val cost = (order as? TradeOrder.Buy)?.let { buy -> market?.offerFor(buy.resource)?.buyPrice?.times(buy.units) }
            enqueueEconomyError(cost)
            return
        }
        applyCreditChange(result.credits)
        cargo = result.cargo
        logger.info(
            ECONOMY_TAG,
            "Traded ${result.kind} ${result.tradedUnits} units; credits=$credits, cargo=${cargo.usedUnits}/${cargo.capacity}",
        )
        // Trading is a key world event (mirrors mining/dock/refuel) — persist it now.
        autosave.onEvent("trade")
        recordTutorialEvent(TutorialEvent.GATHERED) // UC36: a station trade completes GATHER (trade path)
    }

    /** The active ship's current crew count (UC11) — read by the hire desk for its readout. */
    fun activeCrew(): Int = fleet.active.crew

    /**
     * The active ship's derived crew **capacity** (UC11 AC#1) — `ShipStats.crewCapacity(type,
     * loadout)`. Read by the hire desk to show "crew N / capacity". Derived, never stored.
     */
    fun activeCrewCapacity(): Int = ShipStats.crewCapacity(fleet.active.type, fleet.active.loadout)

    /**
     * Whether the active ship's turrets are crew-operable (UC11 AC#3) — the pure
     * [TurretOperability.turretsOperable] derived flag the future combat model (UC13) will read. Shown
     * on the hire desk so the player sees a hire flip turrets from inoperable to operable.
     */
    fun turretsOperable(): Boolean = TurretOperability.turretsOperable(fleet.active.crew)

    /**
     * Execute one crew-hire [order] against the **docked** station via the pure [Hiring.resolve]
     * (UC11 AC#2) — the crew analogue of [trade]/[outfit]. The [com.orbitalfrontier.screen.HireScreen]
     * routes HIRE taps here. Resolves against the docked station's
     * [com.orbitalfrontier.world.Station.hiresCrew] flag, the player's credits, the active ship's crew,
     * and its derived crew capacity (`ShipStats.crewCapacity(active.type, active.loadout)` — the SAME
     * capacity source the deterministic simulation's docked-hire branch uses, so live and replayed
     * hiring match). When not docked (or the station doesn't hire crew) it no-ops.
     *
     * On a real hire it deducts credits, folds the new crew onto the active ship in the fleet, logs one
     * INFO line, and autosaves so the hire is durable; a no-op tap (station doesn't hire, at capacity,
     * unaffordable) changes nothing and is not persisted.
     */
    fun hire(order: HireOrder) {
        val station = dockedStation?.let { sectorWorld.sector(currentSector).station(it) } ?: return
        val active = fleet.active
        val result =
            Hiring.resolve(
                credits = credits,
                currentCrew = active.crew,
                crewCapacity = ShipStats.crewCapacity(active.type, active.loadout),
                offersCrew = station.hiresCrew,
                order = order,
            )
        if (!result.changed) {
            logger.info(ECONOMY_TAG, "Hire requested but nothing changed (station doesn't hire, at capacity, or unaffordable)")
            // UC40 AC#3: a refused HIRE surfaces a styled error (cost = per-crew price × requested units).
            val cost = (order as? HireOrder.Hire)?.let { Hiring.HIRE_COST_PER_CREW * it.units }
            enqueueEconomyError(cost)
            return
        }
        applyCreditChange(result.credits)
        fleet = fleet.withActive(active.withCrew(result.crew))
        logger.info(
            ECONOMY_TAG,
            "Hired ${result.hired} crew; crew=${fleet.active.crew}/${activeCrewCapacity()}, credits=$credits, " +
                "turretsOperable=${turretsOperable()}",
        )
        autosave.onEvent("hire")
    }

    /** The current fleet (UC09) — read by the outfit / shipyard screens for their readouts. */
    fun fleetSnapshot(): Fleet = fleet

    /**
     * Execute one outfit [order] against the **docked** station via the pure [Outfitting.resolve]
     * (UC09 AC#2/#3/#4) — the outfitting analogue of [trade]. The [com.orbitalfrontier.screen.OutfitScreen]
     * routes BUY-INSTALL / REMOVE-SELL taps here. Resolves against the active ship's loadout + slot
     * layout, the docked station's [com.orbitalfrontier.world.Station.outfitMarket], and whether the
     * station is a junkyard; when not docked it no-ops.
     *
     * On a real change it deducts/refunds credits, **re-derives the active ship's cargo/fuel capacities**
     * from the new loadout (preserving the live contents/level — the Δ-capacity propagation, AC#2),
     * folds the result back into the fleet, logs one line, and autosaves. A no-op tap changes nothing.
     */
    fun outfit(order: OutfitOrder) {
        val station = dockedStation?.let { sectorWorld.sector(currentSector).station(it) } ?: return
        val active = fleet.active
        val result =
            Outfitting.resolve(
                credits = credits,
                loadout = active.loadout,
                slotCounts = active.type.slotCounts,
                outfitMarket = station.outfitMarket,
                isJunkyard = station.kind == StationKind.JUNKYARD,
                order = order,
            )
        if (!result.changed) {
            logger.info(ECONOMY_TAG, "Outfit requested but nothing changed (not offered, unaffordable, no free slot, or empty slot)")
            // UC40 AC#3: a refused INSTALL surfaces a styled error (cost = the upgrade's price; null for a REMOVE).
            val cost = (order as? OutfitOrder.BuyInstall)?.let { UpgradeCatalog.MVP.upgrade(it.upgradeId)?.price }
            enqueueEconomyError(cost)
            return
        }
        applyCreditChange(result.credits)
        // Re-derive capacities from the new fit on the LIVE active ship (current cargo/fuel), then sync.
        val refitted = active.copy(cargo = cargo, fuel = fuel).withLoadout(result.loadout)
        cargo = refitted.cargo
        fuel = refitted.fuel
        fleet = fleet.withActive(refitted)
        logger.info(
            ECONOMY_TAG,
            "Outfit applied; credits=$credits, cargoCap=${cargo.capacity}, fuelCap=${fuel.capacity}",
        )
        autosave.onEvent("outfit")
    }

    /**
     * Execute one fleet [order] against the **docked** station via the pure [FleetResolver.resolve]
     * (UC09 AC#5) — buy a ship from the station's shipyard, or switch the active ship. The
     * [com.orbitalfrontier.screen.ShipyardScreen] routes taps here; when not docked it no-ops.
     *
     * Before resolving, the live cargo/fuel/kinematics are folded into the current active ship so a
     * switch preserves them. On a switch, the now-active ship is brought to the docked position (you
     * walked over to it), its live cargo/fuel are reloaded, and the Box2D body is re-seeded so flight
     * resumes from the station. On a buy, the new hull is appended (it spawns at the docked position).
     * A no-op tap changes nothing.
     */
    fun fleetCommand(order: FleetOrder) {
        val station = dockedStation?.let { sectorWorld.sector(currentSector).station(it) } ?: return
        val dockedKinematics = physics.readKinematics()
        // Fold the live active-ship state in so switching away from it preserves cargo/fuel/position.
        fleet = fleet.withActive(fleet.active.copy(cargo = cargo, fuel = fuel, kinematics = dockedKinematics))

        val result = FleetResolver.resolve(fleet, credits, station.shipyard, order)
        if (!result.changed) {
            logger.info(
                ECONOMY_TAG,
                "Fleet command requested but nothing changed (not offered, unaffordable, not owned, or already active)",
            )
            // UC40 AC#3: a refused BUY surfaces a styled error (cost = the ship's price; null for a SWITCH).
            val cost = (order as? FleetOrder.BuyShip)?.let { ShipRoster.byId(it.typeId)?.price }
            enqueueEconomyError(cost)
            return
        }
        val previousActive = fleet.activeShipId
        applyCreditChange(result.credits)
        fleet = result.fleet

        if (fleet.activeShipId != previousActive) {
            // Switched ships: present the new active ship at the docked position, reload its live
            // cargo/fuel, and re-seed the body so undocking resumes from the station (not its old spot).
            val arrived =
                fleet.active.copy(
                    kinematics = fleet.active.kinematics.copy(position = dockedKinematics.position, velocity = Vec2.ZERO),
                )
            fleet = fleet.withActive(arrived)
            cargo = arrived.cargo
            fuel = arrived.fuel
            physics.resetTo(arrived.kinematics)
            logger.info(WORLD_TAG, "Switched active ship to ${fleet.activeShipId.value} (${fleet.active.type.displayName})")
        } else {
            logger.info(WORLD_TAG, "Bought ship; fleet now has ${fleet.ships.size} ships, credits=$credits")
        }
        autosave.onEvent("fleet")
    }

    /**
     * A short fuel readout for the station hub's REFUEL row (UC07 AC#5), e.g. `FUEL 12/100`. Read on
     * the render thread; cheap, allocates a small String only when the hub asks (not per frame).
     */
    fun fuelStatusLine(): String = "FUEL ${fuel.level.roundToInt()}/${fuel.capacity.roundToInt()}"

    /**
     * Convert hydrogen cargo into fuel via the pure [Refueling.resolve] (UC07 AC#5) — the station hub's
     * "Refuel (H₂)" button routes here (mirroring [undock]). On a successful transfer it folds the new
     * fuel + cargo back in, logs one INFO line, and autosaves the event so the refuel is durable; a
     * no-op tap (no hydrogen / full tank) changes nothing and is not persisted.
     *
     * Returns a short feedback line for the hub to display (UC18 AC#1/#4: the conversion path no longer
     * fails silently). The returned text is informational only — state changes happen here.
     */
    fun refuel(): String {
        val result = Refueling.resolve(fuel, cargo, RefuelAction.REFUEL, fuelParams)
        if (result.transferredUnits <= 0) {
            logger.info(ECONOMY_TAG, "Refuel requested but nothing transferred (no hydrogen, or tank full)")
            return if (fuel.remainingCapacity < 1f) "Tank full" else "No hydrogen to convert"
        }
        fuel = result.fuel
        cargo = result.cargo
        logger.info(
            ECONOMY_TAG,
            "Refueled ${result.transferredUnits} hydrogen -> fuel; tank ${fuel.level}/${fuel.capacity}",
        )
        // Refuelling is a key world event (mirrors mining/dock) — persist it now.
        autosave.onEvent("refuel")
        recordTutorialEvent(TutorialEvent.REFUELLED) // UC36: completes the REFUEL step
        return "Converted ${result.transferredUnits} H₂ to fuel"
    }

    /**
     * Buy fuel for credits at the **docked** station via the pure [StationRefuel.resolve] (UC18) — the
     * additive sibling of the hydrogen-conversion [refuel]. The station hub's "Buy Fuel (credits)"
     * button routes here. The fuel price is the docked station's authored HYDROGEN buy price (credits
     * per fuel unit, reconstructed from the world map per ADR 0007); when not docked, the station is
     * unresolvable, or it sells no hydrogen, the price is null and the resolver reports
     * [StationRefuelStatus.UNAVAILABLE].
     *
     * On a successful purchase ([StationRefuelStatus.REFUELED]) it folds the new credits + fuel back in,
     * logs one INFO line, and autosaves the event so the purchase is durable. Every other status
     * (FULL / BROKE / UNAVAILABLE / NONE) is a deterministic no-op that changes nothing and is not
     * persisted (UC18 AC#1/#3/#4). Returns a short feedback line for the hub to display.
     */
    fun buyFuel(): String {
        val price =
            dockedStation?.let {
                sectorWorld.sector(currentSector).station(it)?.market?.offerFor(ResourceType.HYDROGEN)?.buyPrice
            }
        val result = StationRefuel.resolve(credits, fuel, price, StationRefuelAction.BUY)
        return when (result.status) {
            StationRefuelStatus.REFUELED -> {
                applyCreditChange(result.credits)
                fuel = result.fuel
                logger.info(
                    ECONOMY_TAG,
                    "Bought ${result.unitsBought} fuel for ${result.unitsBought * (price ?: 0L)} credits; " +
                        "tank ${fuel.level}/${fuel.capacity}, credits=$credits",
                )
                // Buying fuel is a key world event (mirrors trade/refuel) — persist it now.
                autosave.onEvent("buyFuel")
                recordTutorialEvent(TutorialEvent.REFUELLED) // UC36: a credits fuel buy also completes REFUEL
                "Refueled ${result.unitsBought} units"
            }
            // UC40 AC#3: the old bare-string statuses now also raise a styled toast on the shared queue so a
            // refused buy-fuel reads the same as the other economy refusals. BROKE is the dedicated
            // insufficient-credits error; FULL / UNAVAILABLE are generic action-rejected with a named reason.
            // The hub still shows the returned line in its own label (additive — toast + label).
            StationRefuelStatus.FULL -> {
                notifications.enqueue(GameNotifications.actionRejected("TANK FULL"))
                "Tank full"
            }
            StationRefuelStatus.BROKE -> {
                notifications.enqueue(GameNotifications.insufficientCredits())
                "Insufficient credits"
            }
            StationRefuelStatus.UNAVAILABLE -> {
                notifications.enqueue(GameNotifications.actionRejected("NO FUEL SOLD HERE"))
                "No fuel sold here"
            }
            StationRefuelStatus.NONE -> ""
        }
    }

    /**
     * Return to flight from the station hub (UC05 AC#2/#4). Runs the same pure [Docking.resolve] with
     * [DockAction.UNDOCK] the dock path uses (docked → null), then autosaves so the cleared dock state
     * is durable. Called on the render thread by the game when the hub's UNDOCK button is tapped,
     * before the play screen is shown again — so the snapshot the autosave reads is already undocked.
     */
    fun undock() {
        dockedStation =
            Docking.resolve(sectorWorld, currentSector, dockedStation, physics.readKinematics().position, DockAction.UNDOCK)
        logger.info(WORLD_TAG, "Undocked in sector ${currentSector.value}")
        autosave.onEvent("undock")
        notifications.enqueue(GameNotifications.undocked()) // UC35 (AC#1)
    }

    /**
     * The mission-board offers for the docked station (UC12 AC#2), already filtered against the accepted
     * / terminal mission ids — what the [com.orbitalfrontier.screen.MissionBoardScreen] lists as
     * ACCEPTable. Empty when not docked (the board is only reachable while docked). Regenerated
     * deterministically from the static authored world on each call (regenerate-and-filter, ADR 0011).
     */
    fun stationMissionBoard(): List<Mission> {
        val station = dockedStation ?: return emptyList()
        return MissionGenerator.boardOffers(sectorWorld, station, missionParams, MvpSectorMap.bountyContracts(station), bountyParams)
            .filter { it.id !in missionLog.takenIds }
            // UC14: the reputation gate — the SEPARATE filter applied AFTER generation + the takenIds
            // filter (the board site of the three symmetric gating sites). A gated `:premium` offer only
            // surfaces once the player's standing with the station's faction reaches its threshold.
            .filter { ReputationGate.isAvailable(it, reputation) }
    }

    /** The player's ACTIVE missions (UC12 AC#3) — what the mission board lists as TURN-IN-able. */
    fun activeMissions(): List<Mission> = missionLog.accepted.filter { it.status == MissionStatus.ACTIVE }

    /**
     * The docked station's gated offers the player has NOT yet unlocked (UC14) — the board surfaces these
     * as LOCKED rows (with their faction + threshold) so the player can see what reputation unlocks. The
     * complement of [stationMissionBoard]'s gate filter: un-taken offers that fail [ReputationGate].
     */
    fun lockedStationOffers(): List<Mission> {
        val station = dockedStation ?: return emptyList()
        return MissionGenerator.boardOffers(sectorWorld, station, missionParams, MvpSectorMap.bountyContracts(station), bountyParams)
            .filter { it.id !in missionLog.takenIds }
            .filter { !ReputationGate.isAvailable(it, reputation) }
    }

    /** The player's current per-faction reputation (UC14) — read by the board for its standings readout. */
    fun reputationSnapshot(): Reputation = reputation

    /**
     * Execute one mission [order] against the docked station via the pure [Missions.resolve] (UC12
     * AC#3/#4) — the mission analogue of [trade]/[hire]. The
     * [com.orbitalfrontier.screen.MissionBoardScreen] routes ACCEPT / TURN IN taps here. Resolves against
     * the docked station's board offers ([stationMissionBoard]) and the docked station id (so courier
     * pickup/turn-in and mining turn-in gate correctly), plus the live cargo and credits. When not docked
     * it no-ops (the board is unreachable anyway).
     *
     * On a real change it folds the new mission log + credits + cargo back in (a mining turn-in consumes
     * the quota and grants credits; an accept moves the offer to ACTIVE), logs one INFO line, and autosaves
     * so the change is durable; a no-op tap (offer gone, quota not held, wrong station) is not persisted.
     */
    fun applyMissionOrder(order: MissionOrder) {
        val offers = stationMissionBoard()
        val result =
            Missions.resolve(
                missionLog,
                offers,
                order,
                dockedStation,
                cargo,
                credits,
                missionParams,
                reputation,
                reputationParams,
            )
        if (!result.changed) {
            logger.info(
                ECONOMY_TAG,
                "Mission order requested but nothing changed (offer gone, quota not held, or wrong station)",
            )
            return
        }
        missionLog = result.log
        applyCreditChange(result.credits)
        cargo = result.cargo
        reputation = result.reputation
        logger.info(ECONOMY_TAG, "Mission order applied; active=${activeMissions().size}, credits=$credits")
        autosave.onEvent("mission")
        // UC31/UC35: distinct cue + toast for accept vs. turn-in (AC#1); only on a real change (no-ops
        // returned early above). The credit reward from a turn-in already surfaced via applyCreditChange.
        when (order) {
            is MissionOrder.Accept -> {
                audio.play(Sfx.MISSION_ACCEPT)
                notifications.enqueue(GameNotifications.missionAccepted())
                recordTutorialEvent(TutorialEvent.MISSION_ACCEPTED) // UC36: a board accept also completes ACCEPT_MISSION
            }
            is MissionOrder.TurnIn -> {
                audio.play(Sfx.MISSION_COMPLETE)
                notifications.enqueue(GameNotifications.missionCompleted())
            }
            MissionOrder.None -> Unit
        }
    }

    /** The player's owned stations (UC15) — read by the station-build hub action for its readout/intent. */
    fun stationsSnapshot(): StationRegistry = stations

    /**
     * Execute one station-build [order] against the **docked** station via the pure
     * [StationBuilder.resolve] (UC15 AC#1) — the station analogue of [fleetCommand]/[outfit]. The
     * station hub's BUILD action routes here. Resolves against the player's owned-station registry, the
     * live credits + active-ship cargo (build resources are drawn from the hold), whether the docked
     * station is build-capable ([com.orbitalfrontier.world.Station.buildsStations]), and the current
     * sector (where a newly-founded station is anchored). When not docked (or the station is
     * unresolvable) it no-ops, so building is implicitly gated on being docked.
     *
     * On a real build it deducts credits, folds the post-build cargo back onto the active ship's live
     * hold (only when changed — same same-instance discipline as the mission step; [currentWorldState]
     * carries it onto the active ship for the autosave), updates the registry, logs one line, and
     * autosaves so the build is durable. A no-op tap (not build-capable, unknown module, unaffordable,
     * not owned) changes nothing and is not persisted.
     */
    fun build(order: StationBuildOrder) {
        val station = dockedStation?.let { sectorWorld.sector(currentSector).station(it) } ?: return
        val result =
            StationBuilder.resolve(
                registry = stations,
                credits = credits,
                cargo = cargo,
                buildsStations = station.buildsStations,
                sector = currentSector,
                order = order,
            )
        if (!result.changed) {
            logger.info(
                WORLD_TAG,
                "Station build requested but nothing changed (not build-capable, unknown module, unaffordable, or not owned)",
            )
            return
        }
        applyCreditChange(result.credits)
        cargo = result.cargo
        stations = result.registry
        logger.info(
            WORLD_TAG,
            "Station build applied; stations=${stations.size}, credits=$credits, cargo=${cargo.usedUnits}/${cargo.capacity}",
        )
        autosave.onEvent("build")
    }

    /**
     * Android pause/exit lifecycle (forwarded by [com.badlogic.gdx.Game]). Enqueue a final autosave
     * and block until it is durably written before the app is backgrounded (UC04 AC#2).
     */
    override fun pause() {
        autosave.onPauseOrExit()
    }

    override fun hide() {
        // UC31: stop the looping engine cue when the play screen is hidden (docking hand-off, app pause,
        // screen switch) so it can't bleed into the hub/other screens; reset the edge so it restarts
        // cleanly on the next thrust when the screen is shown again.
        if (previousThrusting) {
            audio.stopSfx(Sfx.THRUST)
            previousThrusting = false
        }
        // UC32: release the BACK-key catch so it returns to default handling once this screen is hidden.
        Gdx.input.setCatchKey(Input.Keys.BACK, false)
        if (Gdx.input.inputProcessor === inputMultiplexer) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
        pauseOverlay.dispose()
        destructionOverlay.dispose()
        skin.dispose()
        starfield.dispose()
        shipRenderer.dispose()
        hudRenderer.dispose()
        gateRenderer.dispose()
        asteroidFieldRenderer.dispose()
        worldObjectRenderer.dispose()
        minimap.dispose()
        mapOverlay.dispose()
        hostileRenderer.dispose()
        shipSchematicRenderer.dispose()
        notificationRenderer.dispose()
        physics.dispose()
    }

    // UC34: `internal` (was `private`) so the HUD-vs-controls geometric guard reads the real layout
    // constants (MARGIN/JOYSTICK_SIZE feeding bottomControlBand(), HUD_BLOCK_HEIGHT) rather than mirroring
    // literals; the values themselves are unchanged.
    internal companion object {
        const val TAG = "Screen"

        // Discrete world events (sector jumps) log under the "World" tag (coding-guidelines § logging).
        const val WORLD_TAG = "World"

        // Economy events (refuelling) log under the "Economy" tag (coding-guidelines § logging).
        const val ECONOMY_TAG = "Economy"
        const val MIN_DT = 1e-4f
        const val MAX_DT = 1f / 30f
        const val MARGIN = 24f
        const val JOYSTICK_SIZE = 220f

        // UC31: minimum seconds between MINING_TICK cues while mining is held (≈8 pulses/sec).
        const val MINING_SFX_INTERVAL = 0.12f

        // UC32: the top-centre HUD pause button footprint (world units; the ×2 UI viewport magnifies it).
        const val PAUSE_BUTTON_WIDTH = 140f
        const val PAUSE_BUTTON_HEIGHT = 56f

        // UC37: the in-flight settings panel is now the shared grouped [SettingsPanel] wrapped in a
        // ScrollPane. Slightly wider than the pre-UC37 single-column overlay (200) to seat the grouped
        // 210-wide rows + the vertical scrollbar without horizontal clipping; still left-banded.
        const val SETTINGS_WIDTH = 240f

        // UC37: the grouped content is far taller than this fixed footprint, so the ScrollPane scrolls it
        // vertically — the panel stays a fixed-size box centred in the top-left band, hidden in combat /
        // when the map overlay is open (and surfaced as the UC32 pause Settings sub-view).
        const val SETTINGS_HEIGHT = 250f

        // UC22/UC34: world-space height of the top-left HUD readout block, measured down from the top.
        // The settings/handedness button is centred in the band below this and above the bottom controls,
        // so it clears the HUD even during a combat encounter. UC34 expanded the block from 3–4 lines to a
        // worst-case seven (SPEED, HDG, FUEL, CR+CRG, SEC, OBJ, IN COMBAT), so the reservation now tracks
        // HudLayout.BLOCK_HEIGHT — the single source the pure HUD geometry and this band reservation share.
        const val HUD_BLOCK_HEIGHT = HudLayout.BLOCK_HEIGHT

        // Real seconds per courier model tick on the device (UC12). The model timer is tick-based; the
        // device fires one [Missions.advance] per this many accumulated dt seconds so the countdown is
        // frame-rate-independent. The replay harness instead decrements one tick per fixed sim step — the
        // model timer is the shared authority, this constant only paces the device's view of it. [TUNE]
        const val MISSION_TICK_SECONDS = 1f

        // Fixed combat sub-tick (UC13): the device paces [Combat.step] off accumulated dt at this step so
        // the real-time fight is frame-rate-independent; the replay harness steps the model at a fixed dt
        // too. [MAX_COMBAT_TICKS_PER_FRAME] caps catch-up ticks after a stall. [TUNE]
        const val COMBAT_DT = 1f / 30f
        const val MAX_COMBAT_TICKS_PER_FRAME = 5
    }
}
