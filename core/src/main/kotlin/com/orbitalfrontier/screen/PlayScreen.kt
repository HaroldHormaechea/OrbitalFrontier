package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.combat.Combat
import com.orbitalfrontier.combat.CombatEvent
import com.orbitalfrontier.combat.CombatLimitedMovement
import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.EncounterSpawner
import com.orbitalfrontier.combat.FireAction
import com.orbitalfrontier.combat.PlayerCombatInput
import com.orbitalfrontier.combat.Respawn
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.crew.Hiring
import com.orbitalfrontier.crew.TurretOperability
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelBurn
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.Refueling
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationRefuel
import com.orbitalfrontier.economy.StationRefuelAction
import com.orbitalfrontier.economy.StationRefuelStatus
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.economy.Trading
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationGate
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionGenerator
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.mission.MissionParams
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.Missions
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.Outfitting
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.render.AsteroidFieldRenderer
import com.orbitalfrontier.render.GateRenderer
import com.orbitalfrontier.render.HostileRenderer
import com.orbitalfrontier.render.HudRenderer
import com.orbitalfrontier.render.MapOverlayRenderer
import com.orbitalfrontier.render.MapOverlayState
import com.orbitalfrontier.render.MinimapRenderer
import com.orbitalfrontier.render.ShipRenderer
import com.orbitalfrontier.render.ShipSchematicRenderer
import com.orbitalfrontier.render.StarfieldRenderer
import com.orbitalfrontier.render.WorldObjectRenderer
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.save.AutosaveController
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.screen.controls.MovementJoystick
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin
import com.orbitalfrontier.settings.ControlsLayout
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.ScreenSide
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.FleetResolver
import com.orbitalfrontier.ship.FuelLimitedMovement
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipPhysics
import com.orbitalfrontier.station.StationBuildOrder
import com.orbitalfrontier.station.StationBuilder
import com.orbitalfrontier.station.StationRegistry
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
    settingsRepository: SettingsRepository,
    saveExecutor: SaveExecutor,
    private val autosave: AutosaveController,
    private val sectorWorld: SectorWorld,
    initialHandedness: Handedness,
    initialWorldState: WorldState,
    private val onDocked: (Station) -> Unit,
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
    private val shipRenderer = ShipRenderer()
    private val hudRenderer = HudRenderer()
    private val gateRenderer = GateRenderer()
    private val asteroidFieldRenderer = AsteroidFieldRenderer()

    // ADR 0015: the one in-world renderer that draws a base glyph for EVERY POI (stations included),
    // so no POI can render as nothing; gate/asteroid renderers above now draw only their rings.
    private val worldObjectRenderer = WorldObjectRenderer()
    private val minimap = MinimapRenderer()

    // UC23: the click-to-zoom full-height map overlay. [mapOverlayState] is the pure open/closed toggle
    // (libGDX-free, JVM-testable); [mapOverlay] is its GL renderer (mirrors the minimap). The overlay is
    // LIVE — opening it does NOT pause the simulation (MapOverlayLayout.PAUSES_SIMULATION = false), so
    // the per-frame sim/autosave/combat below run unchanged whether or not the map is open.
    private var mapOverlayState = MapOverlayState()
    private val mapOverlay = MapOverlayRenderer()

    // Combat visuals (UC13): hostiles + projectiles in world space, and the per-section HUD ship
    // schematic. Both only read state and draw nothing while combat is inactive.
    private val hostileRenderer = HostileRenderer()
    private val shipSchematicRenderer = ShipSchematicRenderer()

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

    private val skin = PlaceholderControlsSkin()

    // ADR 0015: scale the Scene2D UI (controls + fonts) by UiScale.factor via the viewport's
    // unitsPerPixel; this is UI-only and does NOT touch the world camera (the playfield stays 1:1).
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })
    private val joystick = MovementJoystick(skin)
    private val actionCluster = ActionCluster(skin)
    private val settingsOverlay: SettingsOverlay
    private val inputMultiplexer = InputMultiplexer(stage)

    // Context dock control (UC05): an "IN RANGE: <name>" prompt above a DOCK button, shown only while
    // a station is dockable. A one-shot [dockRequested] flag set by the button's tap is consumed on
    // the next frame, so the dock commits inside the deterministic per-frame flow (after the step).
    private val dockPrompt = Label("", skin.labelStyle)
    private val dockButton = TextButton("DOCK", skin.settingsButtonStyle)
    private val dockPanel = Table()
    private var dockRequested = false

    // Context mine control (UC06): a "MINE: <units>/<capacity>" prompt above a MINE button, shown only
    // while an asteroid field is in range. Unlike docking (an edge-triggered tap), mining is a *held*
    // action — each frame the field is in range and the button is pressed, one [Mining.resolve] tick
    // runs. We therefore read [mineButton]'s pressed state per frame rather than latching a one-shot.
    private val minePrompt = Label("", skin.labelStyle)
    private val mineButton = TextButton("MINE", skin.settingsButtonStyle)
    private val minePanel = Table()

    // Scan control (UC10): unlike dock/mine (proximity-gated context buttons), an active scan is a
    // player ability available anytime in flight, so the SCAN button is persistent (not range-gated).
    // A one-shot [scanRequested] flag set by the tap is consumed on the next frame, so the reveal
    // commits inside the deterministic per-frame flow (after the step), mirroring the DOCK button.
    private val scanButton = TextButton("SCAN", skin.settingsButtonStyle)
    private val scanPanel = Table()
    private var scanRequested = false

    // Radio context control (UC12): a "RADIO: <offer>" prompt above an ACCEPT button, shown only while
    // an un-taken ship-radio mission broadcast is in range (range-based, mirroring the dock/mine context
    // panels). Like DOCK, ACCEPT is edge-triggered: the tap sets a one-shot flag consumed on the next
    // frame, so the accept commits inside the deterministic per-frame flow. The offer accepted is the
    // first surfaced this frame (the panel only shows when one is available).
    private val radioPrompt = Label("", skin.labelStyle)
    private val radioButton = TextButton("ACCEPT", skin.settingsButtonStyle)
    private val radioPanel = Table()
    private var radioAcceptRequested = false

    // UC23 map-overlay tap targets — invisible Scene2D actors (they draw nothing; the overlay visuals
    // are drawn by [mapOverlay] after stage.draw()). [minimapTapTarget] sits exactly on the drawn
    // minimap panel (bounds from the shared MinimapRenderer.panelRect — one geometry source for draw +
    // touch) and toggles the overlay open on a tap. [mapDismissActor] is a full-screen catch-all added
    // LAST (top z) and visible only while the overlay is open; any tap on it dismisses the overlay
    // (AC#5 — the player can never be trapped) and is consumed (ClickListener.touchDown returns true) so
    // it never leaks through to a flight control underneath.
    private val minimapTapTarget = Actor()
    private val mapDismissActor = Actor()

    private var handedness = initialHandedness

    init {
        settingsOverlay =
            SettingsOverlay(
                skin = skin,
                repository = settingsRepository,
                saveExecutor = saveExecutor,
                initial = initialHandedness,
            ) { newHandedness ->
                handedness = newHandedness
                layoutControls()
            }

        dockButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    // Edge-triggered intent; the dock commits on the next frame's render (post-step).
                    dockRequested = true
                }
            },
        )
        dockPanel.add(dockPrompt).padBottom(DOCK_PROMPT_GAP).row()
        dockPanel.add(dockButton).size(DOCK_WIDTH, DOCK_HEIGHT).row()
        dockPanel.pack()
        // Hidden until a station is in range; an invisible Scene2D actor receives no touches, so it
        // cannot be tapped (and does not affect flight controls) while undocked and out of range.
        dockPanel.isVisible = false

        // Mine panel mirrors the dock panel. No ClickListener: mining is held, so the render loop reads
        // mineButton.isPressed each frame rather than reacting to a discrete tap.
        minePanel.add(minePrompt).padBottom(DOCK_PROMPT_GAP).row()
        minePanel.add(mineButton).size(DOCK_WIDTH, DOCK_HEIGHT).row()
        minePanel.pack()
        minePanel.isVisible = false

        // Scan button (UC10): edge-triggered like DOCK (the reveal commits on the next frame), but
        // persistent — an active scan is not proximity-gated, so the panel stays visible in flight.
        scanButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    // Edge-triggered intent; the scan commits on the next frame's render (post-step).
                    scanRequested = true
                }
            },
        )
        scanPanel.add(scanButton).size(DOCK_WIDTH, DOCK_HEIGHT).row()
        scanPanel.pack()

        // Radio panel mirrors the dock panel (UC12): a prompt above an edge-triggered ACCEPT button,
        // shown only while a radio mission offer is in range. Hidden (and so untouchable) otherwise.
        radioButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    // Edge-triggered intent; the accept commits on the next frame's render (post-step).
                    radioAcceptRequested = true
                }
            },
        )
        radioPanel.add(radioPrompt).padBottom(DOCK_PROMPT_GAP).row()
        radioPanel.add(radioButton).size(DOCK_WIDTH, DOCK_HEIGHT).row()
        radioPanel.pack()
        radioPanel.isVisible = false

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

        actionCluster.actor.pack()
        stage.addActor(joystick.actor)
        stage.addActor(actionCluster.actor)
        stage.addActor(settingsOverlay.actor)
        stage.addActor(dockPanel)
        stage.addActor(minePanel)
        stage.addActor(scanPanel)
        stage.addActor(radioPanel)
        // UC23: the minimap tap target, then the full-screen dismiss actor LAST so it has the top z-order
        // and catches taps over everything (including the minimap) while the overlay is open.
        stage.addActor(minimapTapTarget)
        stage.addActor(mapDismissActor)
    }

    override fun show() {
        Gdx.input.inputProcessor = inputMultiplexer
        layoutControls()
        logger.info(TAG, "PlayScreen shown (handedness=$handedness)")
    }

    override fun render(delta: Float) {
        val dt = delta.coerceIn(MIN_DT, MAX_DT)
        // UC23: whether the zoomed map overlay is open this frame. It gates only the HUD control
        // visibility + the overlay draw below — the simulation/step/autosave/combat are LIVE and run
        // unchanged regardless (MapOverlayLayout.PAUSES_SIMULATION = false).
        val mapOpen = mapOverlayState.isOpen
        val input = joystick.currentInput()

        // UC07 fuel burn: the ship draws power every tick (base load even idle, more while thrusting),
        // and that draw burns fuel via THE shared [FuelBurn.step] (same fn the sim/replay path uses).
        // "Thrusting" = an active stick past the deadzone, matching the model's own thrust gate.
        val thrusting = !input.released && input.magnitude > params.inputDeadzone
        fuel = FuelBurn.step(fuel, thrusting, powerParams, dt)

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
        updateDockPanel(available)
        if (dockRequested) {
            dockRequested = false
            if (available != null) {
                dockedStation = Docking.resolve(sectorWorld, currentSector, dockedStation, ship.position, DockAction.DOCK)
                // UC13: remember this as the respawn point on destruction (persisted, retained after undock).
                lastDockedStation = available.id
                logger.info(WORLD_TAG, "Docked at station ${available.id.value} (${available.displayName})")
                // Docking is a key world event — event-driven autosave persists the dock state now.
                autosave.onEvent("dock")
                onDocked(available)
            }
        }

        // UC06 mining: same pure [Mining] the replay harness uses. Each frame, find the in-range field
        // (if any) to drive the context prompt/button; while the MINE button is held, run one
        // extraction tick and fold the result back into cargo + depletion (proximity + held action,
        // never automatic). No per-frame logging — only the discrete cargo-full / field-depleted
        // transitions trigger an event autosave (which logs once), protecting the 60 FPS budget.
        val field = Mining.availableField(sectorWorld, currentSector, ship.position)
        updateMinePanel(field)
        if (field != null && mineButton.isPressed) {
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
                MissionGenerator.radioOffers(sectorWorld, currentSector, ship.position, missionParams)
                    .filter { it.id !in missionLog.takenIds }
                    .filter { ReputationGate.isAvailable(it, reputation) }
            } else {
                emptyList()
            }
        updateRadioPanel(radioOffers.firstOrNull())
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
                    credits = result.credits
                    cargo = result.cargo
                    reputation = result.reputation
                    autosave.onEvent("mission-accept")
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
            credits = advanced.credits
            reputation = advanced.reputation
            if (advanced.changed) {
                autosave.onEvent("mission-expired")
                logger.info(WORLD_TAG, "A courier mission timed out in sector ${currentSector.value}")
            }
        }

        // UC13 combat: edge-triggered natural encounter spawn on the outside→inside zone crossing, then
        // the paced shared [Combat.step]. Hostiles/projectiles/combat-RNG are transient (regenerated, not
        // persisted); only the player's section damage + last docked station are durable.
        runCombat(dt, ship.position)

        // Periodic autosave: accumulate this frame; the controller enqueues a save only every
        // interval (no per-frame I/O or logging — coding-guidelines § concurrency/logging).
        autosave.update(dt)

        // Camera follows the ship so it stays centred on the unbounded map (AC#1/#7).
        worldCamera.position.set(ship.position.x, ship.position.y, 0f)
        worldCamera.update()

        Gdx.gl.glClearColor(BG_R, BG_G, BG_B, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val viewportWidth = Gdx.graphics.width.toFloat()
        val viewportHeight = Gdx.graphics.height.toFloat()
        val sector = sectorWorld.sector(currentSector)
        // Parallax keyed off the camera's world position conveys motion (AC#11).
        starfield.render(ship.position.x, ship.position.y, viewportWidth, viewportHeight)
        // Ring overlays first (mining / trigger radii), then the per-POI base markers on top: the
        // shared WorldObjectRenderer draws a glyph for every POI in the sector — stations included
        // (previously unrendered in-world) and revealed hidden contacts — keyed by WorldGlyphs.forPoi.
        asteroidFieldRenderer.render(worldCamera, sector.asteroidFields)
        gateRenderer.render(worldCamera, sector.gates)
        worldObjectRenderer.render(worldCamera, sector.pois, revealedContacts)
        shipRenderer.render(worldCamera, ship)
        // UC13: hostiles + projectiles in world space (no-op while combat is inactive).
        hostileRenderer.render(worldCamera, combat)
        // UC07: the HUD also shows the fuel tank with a low-fuel cue (red) below the threshold.
        hudRenderer.render(
            ship.speed,
            ship.headingRadians,
            fuel.level,
            fuel.capacity,
            fuel.isLow(fuelParams),
            viewportWidth,
            viewportHeight,
            inCombat = combat.active,
        )
        // The minimap renders every transponder POI (gates + stations) plus any revealed hidden
        // contacts (UC10), keyed by contact kind. It anchors top-right (UC22), fitting its size above
        // the bottom controls; reservedBottom is the worst-case bottom-control top (handedness-agnostic)
        // so it can never overlap the joystick or action cluster on either side.
        minimap.render(
            sector.pois,
            ship.position,
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
        settingsOverlay.actor.isVisible = !combat.active && !mapOpen
        if (mapOpen) {
            joystick.actor.isVisible = false
            actionCluster.actor.isVisible = false
            dockPanel.isVisible = false
            minePanel.isVisible = false
            scanPanel.isVisible = false
            radioPanel.isVisible = false
        } else {
            joystick.actor.isVisible = true
            actionCluster.actor.isVisible = true
            // The persistent SCAN panel re-shows when the overlay closes; the range-gated dock/mine/radio
            // context panels restore themselves via their per-frame updaters above.
            scanPanel.isVisible = true
        }
        mapDismissActor.isVisible = mapOpen
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

        // UC23: the zoomed map overlay is drawn LAST, on top of the gameplay and the (now-hidden) HUD
        // controls — a full-screen dim backdrop (scene faintly visible, AC#3) plus a full-height map
        // panel showing more sector area than the minimap (AC#2/#4). It is a pure overlay: the sim above
        // already ran this frame, so opening the map neither pauses nor corrupts game state (AC#6; the
        // LIVE-in-combat tradeoff is documented in docs/design/world-and-sector.md).
        if (mapOpen) {
            mapOverlay.render(
                sector.pois,
                ship.position,
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

    /** Position the controls for the current handedness (AC#7/#8). Idempotent. */
    private fun layoutControls() {
        val layout = ControlsLayout.forHandedness(handedness)
        val screenWidth = stage.viewport.worldWidth
        val screenHeight = stage.viewport.worldHeight

        joystick.actor.setSize(JOYSTICK_SIZE, JOYSTICK_SIZE)
        joystick.actor.setPosition(sideX(layout.movementStickSide, screenWidth, JOYSTICK_SIZE), MARGIN)

        val clusterWidth = actionCluster.actor.prefWidth
        actionCluster.actor.setSize(clusterWidth, actionCluster.actor.prefHeight)
        actionCluster.actor.setPosition(sideX(layout.actionClusterSide, screenWidth, clusterWidth), MARGIN)

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

        positionDockPanel()
        positionMinePanel()
        positionScanPanel()
        positionRadioPanel()

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
    }

    /** Centre the dock context panel near the top of the screen. */
    private fun positionDockPanel() {
        dockPanel.pack()
        dockPanel.setPosition(
            (stage.viewport.worldWidth - dockPanel.width) / 2f,
            stage.viewport.worldHeight - MARGIN - dockPanel.height,
        )
    }

    /**
     * Centre the mine context panel just below where the dock panel sits, so the two never overlap on
     * the rare frame a station and an asteroid field are both in range.
     */
    private fun positionMinePanel() {
        minePanel.pack()
        minePanel.setPosition(
            (stage.viewport.worldWidth - minePanel.width) / 2f,
            stage.viewport.worldHeight - MARGIN - dockPanel.height - MINE_PANEL_GAP - minePanel.height,
        )
    }

    /**
     * Centre the persistent SCAN panel below the dock/mine context slots (UC10), so it never overlaps
     * the dock/mine prompts on the rare frame a station or field is also in range. Its slot is reserved
     * whether or not those context panels are currently visible, keeping the SCAN button at a stable
     * position in flight.
     */
    private fun positionScanPanel() {
        scanPanel.pack()
        scanPanel.setPosition(
            (stage.viewport.worldWidth - scanPanel.width) / 2f,
            stage.viewport.worldHeight - MARGIN - dockPanel.height - MINE_PANEL_GAP - minePanel.height -
                MINE_PANEL_GAP - scanPanel.height,
        )
    }

    /**
     * Centre the radio context panel just below the scan panel (UC12), so it never overlaps the
     * dock/mine/scan slots on the rare frame several are active at once. Like the dock panel it is only
     * visible while a radio offer is in range (see [updateRadioPanel]).
     */
    private fun positionRadioPanel() {
        radioPanel.pack()
        radioPanel.setPosition(
            (stage.viewport.worldWidth - radioPanel.width) / 2f,
            stage.viewport.worldHeight - MARGIN - dockPanel.height - MINE_PANEL_GAP - minePanel.height -
                MINE_PANEL_GAP - scanPanel.height - MINE_PANEL_GAP - radioPanel.height,
        )
    }

    /**
     * Show or hide the context dock control for the frame's in-range station (UC05): visible with an
     * "IN RANGE: <name>" prompt when [available] is non-null, hidden otherwise. Re-centres after a
     * text change so the panel stays centred as its width varies. No allocation on the common
     * (out-of-range) path — keeps the 60 FPS budget (coding-guidelines § performance).
     */
    private fun updateDockPanel(available: Station?) {
        if (available == null) {
            if (dockPanel.isVisible) dockPanel.isVisible = false
            return
        }
        dockPanel.isVisible = true
        val prompt = "IN RANGE: ${available.displayName}"
        if (!dockPrompt.textEquals(prompt)) {
            dockPrompt.setText(prompt)
            positionDockPanel()
        }
    }

    /**
     * Show or hide the context mine control for the frame's in-range field (UC06): visible with a
     * "MINE <used>/<capacity>" cargo readout when [field] is non-null, hidden otherwise. Re-centres
     * after a text change so the panel stays centred as its width varies. The text is rebuilt only
     * while a field is in range (not on the common out-of-range path), keeping the 60 FPS budget.
     */
    private fun updateMinePanel(field: AsteroidField?) {
        if (field == null) {
            if (minePanel.isVisible) minePanel.isVisible = false
            return
        }
        minePanel.isVisible = true
        val prompt = "MINE ${cargo.usedUnits}/${cargo.capacity}"
        if (!minePrompt.textEquals(prompt)) {
            minePrompt.setText(prompt)
            positionMinePanel()
        }
    }

    /**
     * Show or hide the radio context control for the frame's in-range mission broadcast (UC12): visible
     * with a "RADIO: <reward>cr" prompt when [offer] is non-null, hidden otherwise. Re-centres after a
     * text change so the panel stays centred as its width varies. The text is rebuilt only while an offer
     * is in range (not the common out-of-range path), keeping the 60 FPS budget.
     */
    private fun updateRadioPanel(offer: Mission?) {
        if (offer == null) {
            if (radioPanel.isVisible) radioPanel.isVisible = false
            return
        }
        radioPanel.isVisible = true
        val prompt = "RADIO: ${offer.rewardCredits}cr mining"
        if (!radioPrompt.textEquals(prompt)) {
            radioPrompt.setText(prompt)
            positionRadioPanel()
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
        previousShipPosition = playerPosition

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
        val result = Combat.step(combat, playerInput, fireAction, combatParams, COMBAT_DT)
        combat = result.combat

        // Fold the player's new section damage onto the active ship (=== check skips a no-op tick).
        if (result.sectionDamage !== active.sectionDamage) {
            fleet = fleet.withActive(active.withSectionDamage(result.sectionDamage))
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
     * Forgiving destruction respawn (UC13 AC#5): relocate the active ship to the last docked station
     * (its sector + position) with a cargo-loss penalty and full repair via the pure [Respawn], re-seed
     * the Box2D body, clear the encounter, and autosave. With no last dock (a brand-new game), respawn in
     * place so the player is never stranded.
     */
    private fun respawnPlayer() {
        val active = fleet.active
        val (respawnSector, respawnPosition) = resolveRespawnLocation()
        val result = Respawn.respawn(respawnPosition, cargo, combatParams)
        cargo = result.cargo
        fleet =
            fleet.withActive(
                active.withSectionDamage(result.sectionDamage).copy(cargo = result.cargo, kinematics = result.kinematics),
            )
        physics.resetTo(result.kinematics)
        combat = result.combat
        combatTickAccumulator = 0f
        if (respawnSector != null) currentSector = respawnSector
        logger.info(
            WORLD_TAG,
            "Player ship destroyed; respawned at ${lastDockedStation?.value ?: "current location"} " +
                "(lost ${result.unitsLost} cargo units)",
        )
        autosave.onEvent("respawn")
    }

    /**
     * The respawn sector + position: the last docked station's location if it is still in the authored
     * world, else (null, current position) — respawn in place when there is no recorded dock.
     */
    private fun resolveRespawnLocation(): Pair<SectorId?, Vec2> {
        val stationId = lastDockedStation
        if (stationId != null) {
            for (sector in sectorWorld.sectors) {
                val station = sector.station(stationId)
                if (station != null) return sector.id to station.position
            }
        }
        return null to physics.readKinematics().position
    }

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
            lastDockedStation = lastDockedStation,
            // Reputation (UC14): the live per-faction standing, folded onto the snapshot for the autosave.
            reputation = reputation,
            // Owned stations (UC15): the live registry, folded onto the snapshot for the autosave.
            stations = stations,
        )
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
            return
        }
        credits = result.credits
        cargo = result.cargo
        logger.info(
            ECONOMY_TAG,
            "Traded ${result.kind} ${result.tradedUnits} units; credits=$credits, cargo=${cargo.usedUnits}/${cargo.capacity}",
        )
        // Trading is a key world event (mirrors mining/dock/refuel) — persist it now.
        autosave.onEvent("trade")
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
            return
        }
        credits = result.credits
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
            return
        }
        credits = result.credits
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
            return
        }
        val previousActive = fleet.activeShipId
        credits = result.credits
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
                credits = result.credits
                fuel = result.fuel
                logger.info(
                    ECONOMY_TAG,
                    "Bought ${result.unitsBought} fuel for ${result.unitsBought * (price ?: 0L)} credits; " +
                        "tank ${fuel.level}/${fuel.capacity}, credits=$credits",
                )
                // Buying fuel is a key world event (mirrors trade/refuel) — persist it now.
                autosave.onEvent("buyFuel")
                "Refueled ${result.unitsBought} units"
            }
            StationRefuelStatus.FULL -> "Tank full"
            StationRefuelStatus.BROKE -> "Insufficient credits"
            StationRefuelStatus.UNAVAILABLE -> "No fuel sold here"
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
    }

    /**
     * The mission-board offers for the docked station (UC12 AC#2), already filtered against the accepted
     * / terminal mission ids — what the [com.orbitalfrontier.screen.MissionBoardScreen] lists as
     * ACCEPTable. Empty when not docked (the board is only reachable while docked). Regenerated
     * deterministically from the static authored world on each call (regenerate-and-filter, ADR 0011).
     */
    fun stationMissionBoard(): List<Mission> {
        val station = dockedStation ?: return emptyList()
        return MissionGenerator.boardOffers(sectorWorld, station, missionParams)
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
        return MissionGenerator.boardOffers(sectorWorld, station, missionParams)
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
        credits = result.credits
        cargo = result.cargo
        reputation = result.reputation
        logger.info(ECONOMY_TAG, "Mission order applied; active=${activeMissions().size}, credits=$credits")
        autosave.onEvent("mission")
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
        credits = result.credits
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
        if (Gdx.input.inputProcessor === inputMultiplexer) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
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
        physics.dispose()
    }

    private companion object {
        const val TAG = "Screen"

        // Discrete world events (sector jumps) log under the "World" tag (coding-guidelines § logging).
        const val WORLD_TAG = "World"

        // Economy events (refuelling) log under the "Economy" tag (coding-guidelines § logging).
        const val ECONOMY_TAG = "Economy"
        const val MIN_DT = 1e-4f
        const val MAX_DT = 1f / 30f
        const val MARGIN = 24f
        const val JOYSTICK_SIZE = 220f
        const val SETTINGS_WIDTH = 200f
        const val SETTINGS_HEIGHT = 56f

        // UC22: world-space height of the top-left HUD readout block (HudRenderer's three scaled text
        // lines plus the combat "IN COMBAT" cue), measured down from the top. The settings/handedness
        // button is centred in the band below this and above the bottom controls, so it clears the HUD
        // even during a combat encounter and keeps a symmetric gap (≥ 16 world units at supported sizes)
        // from both neighbours.
        const val HUD_BLOCK_HEIGHT = 104f
        const val DOCK_WIDTH = 200f
        const val DOCK_HEIGHT = 56f
        const val DOCK_PROMPT_GAP = 8f

        // Vertical gap between the dock panel and the mine panel stacked below it.
        const val MINE_PANEL_GAP = 16f

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
        const val BG_R = 0.02f
        const val BG_G = 0.02f
        const val BG_B = 0.05f
    }
}
