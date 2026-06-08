package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelBurn
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.Refueling
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.render.AsteroidFieldRenderer
import com.orbitalfrontier.render.GateRenderer
import com.orbitalfrontier.render.HudRenderer
import com.orbitalfrontier.render.MinimapRenderer
import com.orbitalfrontier.render.ShipRenderer
import com.orbitalfrontier.render.StarfieldRenderer
import com.orbitalfrontier.save.AutosaveController
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.screen.controls.MovementJoystick
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin
import com.orbitalfrontier.settings.ControlsLayout
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.ScreenSide
import com.orbitalfrontier.ship.FuelLimitedMovement
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipPhysics
import com.orbitalfrontier.world.AsteroidField
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.Docking
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.Mining
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.Station
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
    private val minimap = MinimapRenderer()

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

    private val skin = PlaceholderControlsSkin()
    private val stage = Stage(ScreenViewport())
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

        actionCluster.actor.pack()
        stage.addActor(joystick.actor)
        stage.addActor(actionCluster.actor)
        stage.addActor(settingsOverlay.actor)
        stage.addActor(dockPanel)
        stage.addActor(minePanel)
    }

    override fun show() {
        Gdx.input.inputProcessor = inputMultiplexer
        layoutControls()
        logger.info(TAG, "PlayScreen shown (handedness=$handedness)")
    }

    override fun render(delta: Float) {
        val dt = delta.coerceIn(MIN_DT, MAX_DT)
        val input = joystick.currentInput()

        // UC07 fuel burn: the ship draws power every tick (base load even idle, more while thrusting),
        // and that draw burns fuel via THE shared [FuelBurn.step] (same fn the sim/replay path uses).
        // "Thrusting" = an active stick past the deadzone, matching the model's own thrust gate.
        val thrusting = !input.released && input.magnitude > params.inputDeadzone
        fuel = FuelBurn.step(fuel, thrusting, powerParams, dt)

        // ADR 0005 per-frame contract: read -> model computes velocity -> apply -> Box2D steps. The model
        // runs against FUEL-LIMITED params (UC07 AC#3): at/above the low-fuel threshold the factor is
        // exactly 1.0f and [effectiveParams] returns `params` unchanged, so movement stays byte-identical
        // to a fuel-less build; a low tank scales max forward/reverse speed down toward the floor.
        val effectiveParams = FuelLimitedMovement.effectiveParams(params, fuel, fuelParams)
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
        asteroidFieldRenderer.render(worldCamera, sector.asteroidFields)
        gateRenderer.render(worldCamera, sector.gates)
        shipRenderer.render(worldCamera, ship)
        // UC07: the HUD also shows the fuel tank with a low-fuel cue (red) below the threshold.
        hudRenderer.render(
            ship.speed,
            ship.headingRadians,
            fuel.level,
            fuel.capacity,
            fuel.isLow(fuelParams),
            viewportWidth,
            viewportHeight,
        )
        // The minimap renders every transponder POI (gates + stations), keyed by contact kind.
        minimap.render(sector.pois, ship.position, sector.contentExtent, viewportWidth, viewportHeight)

        stage.act(dt)
        stage.draw()
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

        settingsOverlay.actor.setSize(SETTINGS_WIDTH, SETTINGS_HEIGHT)
        settingsOverlay.actor.setPosition(
            screenWidth - MARGIN - SETTINGS_WIDTH,
            screenHeight - MARGIN - SETTINGS_HEIGHT,
        )

        positionDockPanel()
        positionMinePanel()
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

    private fun sideX(
        side: ScreenSide,
        screenWidth: Float,
        widgetWidth: Float,
    ): Float = if (side == ScreenSide.LEFT) MARGIN else screenWidth - MARGIN - widgetWidth

    /**
     * The live world snapshot (current sector, ship kinematics read back from the Box2D body, and
     * dock state) used by the [AutosaveController] (UC04/UC05 AC#4). Called on the render thread,
     * where touching the body is safe; the returned [WorldState] is immutable and handed to the save
     * executor thread.
     */
    fun currentWorldState(): WorldState = WorldState(currentSector, physics.readKinematics(), dockedStation, cargo, fieldDepletion, fuel)

    /**
     * A short fuel readout for the station hub's REFUEL row (UC07 AC#5), e.g. `FUEL 12/100`. Read on
     * the render thread; cheap, allocates a small String only when the hub asks (not per frame).
     */
    fun fuelStatusLine(): String = "FUEL ${fuel.level.roundToInt()}/${fuel.capacity.roundToInt()}"

    /**
     * Convert hydrogen cargo into fuel via the pure [Refueling.resolve] (UC07 AC#5) — the station hub's
     * REFUEL button routes here (mirroring [undock]). On a successful transfer it folds the new fuel +
     * cargo back in, logs one INFO line, and autosaves the event so the refuel is durable; a no-op tap
     * (no hydrogen / full tank) changes nothing and is not persisted.
     */
    fun refuel() {
        val result = Refueling.resolve(fuel, cargo, RefuelAction.REFUEL, fuelParams)
        if (result.transferredUnits <= 0) {
            logger.info(ECONOMY_TAG, "Refuel requested but nothing transferred (no hydrogen, or tank full)")
            return
        }
        fuel = result.fuel
        cargo = result.cargo
        logger.info(
            ECONOMY_TAG,
            "Refueled ${result.transferredUnits} hydrogen -> fuel; tank ${fuel.level}/${fuel.capacity}",
        )
        // Refuelling is a key world event (mirrors mining/dock) — persist it now.
        autosave.onEvent("refuel")
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
        minimap.dispose()
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
        const val DOCK_WIDTH = 200f
        const val DOCK_HEIGHT = 56f
        const val DOCK_PROMPT_GAP = 8f

        // Vertical gap between the dock panel and the mine panel stacked below it.
        const val MINE_PANEL_GAP = 16f
        const val BG_R = 0.02f
        const val BG_G = 0.02f
        const val BG_B = 0.05f
    }
}
