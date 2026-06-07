package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.GateRenderer
import com.orbitalfrontier.render.HudRenderer
import com.orbitalfrontier.render.MinimapRenderer
import com.orbitalfrontier.render.ShipRenderer
import com.orbitalfrontier.render.StarfieldRenderer
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.screen.controls.MovementJoystick
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin
import com.orbitalfrontier.settings.ControlsLayout
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.ScreenSide
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipPhysics
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MvpSectorMap

/**
 * The single gameplay screen — a flyable ship in the current sector with inter-sector jump gates
 * (use-cases 01 + 03).
 *
 * Per frame it runs the ADR 0005 contract — read body kinematics, compute the next velocity with
 * the pure [ShipMovementModel], write it to [ShipPhysics], step Box2D — then runs UC03's gate
 * traversal: it calls the **same** pure [GateTraversal.resolve] the replay harness uses, and on a
 * jump switches [currentSector] and teleports the ship to the arrival point via the only sanctioned
 * transform-set path ([ShipPhysics.resetTo], ADR 0005 — velocity/heading preserved so live motion
 * matches replay momentum), logging one discrete INFO line. The world camera then follows the ship
 * and the screen draws the parallax starfield, the current sector's gates, the ship, the HUD and a
 * minimap. A Scene2D [Stage] hosts the movement joystick, the inert action cluster and the
 * handedness toggle; an [InputMultiplexer] makes the joystick and cluster register simultaneously
 * (multi-touch, AC#7 pitfall).
 *
 * The sector graph is the fixed authored [MvpSectorMap] (ADR 0004); GL-backed resources are created
 * in the constructor (libGDX has a live context by the time the game's `create()` builds this
 * screen) and released in [dispose].
 */
class PlayScreen(
    private val logger: Logger,
    settingsRepository: SettingsRepository,
    initialHandedness: Handedness,
) : ScreenAdapter() {
    private val worldCamera = OrthographicCamera()
    private val model = ShipMovementModel()
    private val params = ShipMovementParams()
    private val physics = ShipPhysics(spawn = ShipKinematics())

    private val starfield = StarfieldRenderer()
    private val shipRenderer = ShipRenderer()
    private val hudRenderer = HudRenderer()
    private val gateRenderer = GateRenderer()
    private val minimap = MinimapRenderer()

    // Fixed authored sector graph (ADR 0004); the current sector is the only mutable world state here.
    private val sectorWorld = MvpSectorMap.build()
    private var currentSector = MvpSectorMap.START_SECTOR

    private val skin = PlaceholderControlsSkin()
    private val stage = Stage(ScreenViewport())
    private val joystick = MovementJoystick(skin)
    private val actionCluster = ActionCluster(skin)
    private val settingsOverlay: SettingsOverlay
    private val inputMultiplexer = InputMultiplexer(stage)

    private var handedness = initialHandedness

    init {
        settingsOverlay =
            SettingsOverlay(
                skin = skin,
                logger = logger,
                repository = settingsRepository,
                initial = initialHandedness,
            ) { newHandedness ->
                handedness = newHandedness
                layoutControls()
            }

        actionCluster.actor.pack()
        stage.addActor(joystick.actor)
        stage.addActor(actionCluster.actor)
        stage.addActor(settingsOverlay.actor)
    }

    override fun show() {
        Gdx.input.inputProcessor = inputMultiplexer
        layoutControls()
        logger.info(TAG, "PlayScreen shown (handedness=$handedness)")
    }

    override fun render(delta: Float) {
        val dt = delta.coerceIn(MIN_DT, MAX_DT)

        // ADR 0005 per-frame contract: read -> model computes velocity -> apply -> Box2D steps.
        val state = physics.readKinematics()
        val next = model.update(state, joystick.currentInput(), params, dt)
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
                arrived
            } else {
                stepped
            }

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
        gateRenderer.render(worldCamera, sector.gates)
        shipRenderer.render(worldCamera, ship)
        hudRenderer.render(ship.speed, ship.headingRadians, viewportWidth, viewportHeight)
        minimap.render(sector.gates, ship.position, sector.contentExtent, viewportWidth, viewportHeight)

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
    }

    private fun sideX(
        side: ScreenSide,
        screenWidth: Float,
        widgetWidth: Float,
    ): Float = if (side == ScreenSide.LEFT) MARGIN else screenWidth - MARGIN - widgetWidth

    override fun hide() {
        if (Gdx.input.inputProcessor === inputMultiplexer) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
        skin.dispose()
        settingsOverlay.dispose()
        starfield.dispose()
        shipRenderer.dispose()
        hudRenderer.dispose()
        gateRenderer.dispose()
        minimap.dispose()
        physics.dispose()
    }

    private companion object {
        const val TAG = "Screen"

        // Discrete world events (sector jumps) log under the "World" tag (coding-guidelines § logging).
        const val WORLD_TAG = "World"
        const val MIN_DT = 1e-4f
        const val MAX_DT = 1f / 30f
        const val MARGIN = 24f
        const val JOYSTICK_SIZE = 220f
        const val SETTINGS_WIDTH = 200f
        const val SETTINGS_HEIGHT = 56f
        const val BG_R = 0.02f
        const val BG_G = 0.02f
        const val BG_B = 0.05f
    }
}
