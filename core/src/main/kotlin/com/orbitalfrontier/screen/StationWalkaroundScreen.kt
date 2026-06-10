package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.GameAssets
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.WalkaroundRenderer
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.MovementJoystick
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin
import com.orbitalfrontier.walkaround.Avatar
import com.orbitalfrontier.walkaround.StationInterior
import com.orbitalfrontier.walkaround.WalkaroundModel
import com.orbitalfrontier.walkaround.WalkaroundParams

/**
 * The on-foot station walk-around screen (UC19), shown when the player disembarks from the station
 * hub. A **thin view** mirroring [PlayScreen]'s control rig but with no game-logic/world coupling
 * (SRP): the pure [WalkaroundModel] integrates the avatar against the pure [StationInterior], and
 * [WalkaroundRenderer] draws the programmer-art result in world space through a zoomed-in follow
 * camera.
 *
 * **Deliberately decoupled from persisted state.** The constructor takes only the [interior], its
 * tunables/[logger], and two callbacks ([onReboard], [onInteract]) — *no* `WorldState`, repository,
 * autosave, or [PlayScreen] reference. The interior is transient and never persisted; re-boarding
 * leaves the docked state exactly as it was (AC#7). The owning [com.orbitalfrontier.app.OrbitalFrontierGame]
 * keeps this screen alive across a shop visit, so the avatar's position is preserved when the trade
 * desk closes and this screen is re-shown (AC re: repeatable re-entry).
 *
 * Reuses [MovementJoystick] for movement (AC#4); facing follows the stick. A RE-BOARD button is
 * always visible (AC#7); the INTERACT button appears only while the avatar is near the shopkeeper
 * (AC#6) and fires [onInteract] to open the existing shop UI.
 *
 * Owns its GL-backed resources (skin, stage, renderer) and releases them in [dispose]; the game
 * disposes it explicitly when the player re-boards (libGDX `setScreen` only `hide()`s).
 */
class StationWalkaroundScreen(
    private val logger: Logger,
    private val interior: StationInterior,
    // UC27: the shared design-system art atlas, BORROWED (owned + disposed by the game, not here).
    private val gameAssets: GameAssets,
    private val onReboard: () -> Unit,
    private val onInteract: () -> Unit,
    private val params: WalkaroundParams = WalkaroundParams(),
) : ScreenAdapter() {
    private val worldCamera = OrthographicCamera()
    private val model = WalkaroundModel()
    private val renderer = WalkaroundRenderer(gameAssets)

    private val skin = PlaceholderControlsSkin(gameAssets)
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })
    private val joystick = MovementJoystick(skin)

    private val reboardButton = TextButton("RE-BOARD", skin.settingsButtonStyle)
    private val interactButton = TextButton("INTERACT", skin.settingsButtonStyle)

    // Avatar lives in screen state (transient): spawns at the authored point and is preserved while
    // the screen is kept alive across a shop visit (AC re: repeatable re-entry without corruption).
    private var avatar: Avatar = Avatar.spawnedAt(interior.avatarSpawn)

    init {
        reboardButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onReboard()
                }
            },
        )
        interactButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onInteract()
                }
            },
        )
        // Hidden until the avatar is near the shopkeeper (AC#6); updated each frame.
        interactButton.isVisible = false

        stage.addActor(joystick.actor)
        stage.addActor(reboardButton)
        stage.addActor(interactButton)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        layoutControls()
        logger.info(TAG, "StationWalkaroundScreen shown")
    }

    override fun render(delta: Float) {
        val dt = delta.coerceIn(MIN_DT, MAX_DT)

        // Read joystick -> pure model integrates the avatar against the interior (AC#4/#8).
        val input = joystick.currentInput()
        avatar = model.update(avatar, interior, input, params, dt)

        // INTERACT prompt visible only when in range of the shopkeeper (AC#6).
        interactButton.isVisible = model.isNearShopkeeper(avatar, interior, params)

        // Zoomed-in camera follows the avatar so the ship + interior read at close range (AC#2).
        worldCamera.position.set(avatar.position.x, avatar.position.y, 0f)
        worldCamera.update()

        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        renderer.render(worldCamera, interior, avatar, params.avatarRadius)

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
        // Zoom so a fixed slice of the world is visible regardless of device resolution (zoomed-in).
        worldCamera.zoom = if (height > 0) VISIBLE_WORLD_HEIGHT / height.toFloat() else 1f
        worldCamera.update()
        layoutControls()
    }

    /** Position the joystick (bottom-left) and the RE-BOARD / INTERACT buttons. Idempotent. */
    private fun layoutControls() {
        val screenWidth = stage.viewport.worldWidth
        val screenHeight = stage.viewport.worldHeight

        joystick.actor.setSize(JOYSTICK_SIZE, JOYSTICK_SIZE)
        joystick.actor.setPosition(MARGIN, MARGIN)

        reboardButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT)
        reboardButton.setPosition(screenWidth - MARGIN - BUTTON_WIDTH, screenHeight - MARGIN - BUTTON_HEIGHT)

        interactButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT)
        interactButton.setPosition(screenWidth - MARGIN - BUTTON_WIDTH, MARGIN)
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
        skin.dispose()
        renderer.dispose()
    }

    private companion object {
        const val TAG = "Screen"

        // Clamp dt so a stall can't teleport the avatar across the interior (mirrors PlayScreen).
        const val MIN_DT = 0f
        const val MAX_DT = 0.05f

        /** World units shown vertically; the camera zoom is derived from this so the view stays zoomed-in. */
        const val VISIBLE_WORLD_HEIGHT = 360f

        const val MARGIN = 32f
        const val JOYSTICK_SIZE = 220f
        const val BUTTON_WIDTH = 220f
        const val BUTTON_HEIGHT = 64f
    }
}
