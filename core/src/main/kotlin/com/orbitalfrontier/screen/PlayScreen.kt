package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.HudRenderer
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

/**
 * The single gameplay screen for use-case 01: an empty sector with one flyable ship.
 *
 * Per frame it runs the ADR 0005 contract — read body kinematics, compute the next velocity with
 * the pure [ShipMovementModel], write it to [ShipPhysics], step Box2D — then follows the ship with
 * the world camera and draws the parallax starfield, ship and HUD. A Scene2D [Stage] hosts the
 * movement joystick, the inert action cluster and the handedness toggle; an [InputMultiplexer]
 * makes the joystick and cluster register simultaneously (multi-touch, AC#7 pitfall).
 *
 * GL-backed resources are created in the constructor (libGDX has a live context by the time the
 * game's `create()` builds this screen) and released in [dispose].
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
        val ship = physics.readKinematics()

        // Camera follows the ship so it stays centred on the unbounded map (AC#1).
        worldCamera.position.set(ship.position.x, ship.position.y, 0f)
        worldCamera.update()

        Gdx.gl.glClearColor(BG_R, BG_G, BG_B, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val viewportWidth = Gdx.graphics.width.toFloat()
        val viewportHeight = Gdx.graphics.height.toFloat()
        // Parallax keyed off the camera's world position conveys motion (AC#11).
        starfield.render(ship.position.x, ship.position.y, viewportWidth, viewportHeight)
        shipRenderer.render(worldCamera, ship)
        hudRenderer.render(ship.speed, ship.headingRadians, viewportWidth, viewportHeight)

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
        physics.dispose()
    }

    private companion object {
        const val TAG = "Screen"
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
