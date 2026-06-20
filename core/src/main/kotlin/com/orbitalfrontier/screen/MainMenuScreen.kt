package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.menu.MainMenuModel
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin

/**
 * The main/title menu shown on every launch before gameplay begins (UC21 AC#1/#5).
 *
 * Intentionally a **thin view over the pure [MainMenuModel]**: it renders the menu (Start / Continue),
 * forwards taps to the model, and acts on the returned [MainMenuModel.MenuAction]. All the transition
 * logic — when Start needs the double confirmation, what Cancel does, whether Continue is allowed —
 * lives in the model (JVM-unit-tested), so this class holds no game logic of its own (SRP). The owner
 * ([com.orbitalfrontier.app.OrbitalFrontierGame]) wires the two callbacks:
 *  - [onStartNewGame] wipes the save (if any) and seeds + enters a brand-new game, and
 *  - [onContinue] resumes the existing save.
 *
 * The screen never touches the world or the save itself; it only signals intent. **Continue** is shown
 * **disabled / greyed (not hidden)** when [continueEnabled] is false (no usable save), per AC#4 and the
 * deliberate UI choice in the use case.
 *
 * Mirrors [StationHubScreen]'s resource model: owns a [OrbitalUiSkin] + [Stage] and releases
 * them in [dispose] (the game disposes every owned screen explicitly, since libGDX `setScreen` only
 * `hide()`s the previous screen).
 */
class MainMenuScreen(
    private val logger: Logger,
    private val continueEnabled: Boolean,
    private val onContinue: () -> Unit,
    private val onStartNewGame: () -> Unit,
    // UC37 AC#4: SETTINGS opens the standalone main-menu settings screen. Defaults to a no-op so any
    // headless/JVM context (or a test) that builds the menu without a settings host need not wire it.
    private val onOpenSettings: () -> Unit = {},
    // UC38: LOAD GAME opens the save-slot screen (load mode) — pick a slot to resume, start a new game
    // into an empty slot, or delete a slot (AC#1/#2). Defaulted to a no-op so headless/JVM contexts need
    // not wire it; always available (it manages saves, not the current game).
    private val onLoadGame: () -> Unit = {},
    // UC52: an optional explanatory line shown under the title — e.g. "your save was recovered from a
    // backup" or "your save can't be opened (it's from a newer version)". Null in the normal launch.
    private val notice: String? = null,
    // UC52: force the Start double confirmation even when Continue is disabled. Set when a present-but-
    // unopenable save (newer schema / corrupt) is on disk, so New Game still warns before discarding it.
    private val forceNewGameConfirm: Boolean = false,
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    // The pure state machine. continueEnabled doubles as "a usable save exists": it gates Continue and
    // decides whether Start double-confirms (save present) or starts immediately (no save) — AC#3/#4.
    // UC52: a present-but-unopenable save forces the confirm independently of Continue (forceNewGameConfirm).
    private val model =
        MainMenuModel(
            saveUsable = continueEnabled,
            requireNewGameConfirm = continueEnabled || forceNewGameConfirm,
        )

    // The single root table; rebuilt in place from the model's current phase on every transition.
    private val root = Table()

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel
        stage.addActor(root)
        rebuild()
    }

    /** Redraw [root] for the model's current [MainMenuModel.Phase] (AC#1/#3). */
    private fun rebuild() {
        root.clearChildren()
        when (model.phase) {
            MainMenuModel.Phase.MENU -> buildMenu()
            MainMenuModel.Phase.CONFIRM_FIRST ->
                buildConfirm(
                    "WARNING: Starting a new game will ERASE your saved progress.",
                )
            MainMenuModel.Phase.CONFIRM_SECOND ->
                buildConfirm(
                    "ARE YOU SURE? This cannot be undone — your saved game will be lost permanently.",
                )
        }
    }

    /** The menu itself: title + START + CONTINUE (CONTINUE greyed/disabled when there is no save). */
    private fun buildMenu() {
        root.add(Label("ORBITAL FRONTIER", skin.titleLabelStyle)).padBottom(if (notice == null) TITLE_GAP else NOTICE_GAP).row()

        // UC52: the explanatory notice (recovered-from-backup, or save-unopenable) — a wrapped line in the
        // amber "warning" tone so it reads as a status message, not a normal label.
        notice?.let { text ->
            val noticeLabel = Label(text, skin.labelStyle)
            noticeLabel.wrap = true
            noticeLabel.color = Palette.WARNING
            root.add(noticeLabel).width(WARNING_WIDTH).padBottom(TITLE_GAP).row()
        }

        val startButton = menuButton("START") { act(model.onStart()) }
        root.add(startButton).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()

        val continueButton = menuButton("CONTINUE") { act(model.onContinue()) }
        if (!continueEnabled) {
            // Shown disabled, never hidden (AC#4). isDisabled stops Scene2D from treating it as a live
            // button AND drives the skin's `disabled` drawable + `disabledFontColor` (UC29 AC#4), so the
            // muted state is the finished design-system styling — no manual colour tint here. The model
            // also guards onContinue (returns NONE) so even a stray tap is a safe no-op.
            continueButton.isDisabled = true
        }
        root.add(continueButton).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()

        // UC38: LOAD GAME opens the save-slot screen (load mode). Always available — it manages saves
        // (resume / new-game-into / delete a slot), independent of whether the active slot has a save.
        root.add(menuButton("LOAD GAME") { onLoadGame() }).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()

        // UC37 AC#4: SETTINGS is always available (it configures preferences, not save state), so unlike
        // CONTINUE it is never disabled. The owner opens the standalone settings screen.
        root.add(menuButton("SETTINGS") { onOpenSettings() }).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()
    }

    /** A confirmation step: the [warning] line + CONFIRM (advance/commit) and CANCEL (back to menu). */
    private fun buildConfirm(warning: String) {
        val warningLabel = Label(warning, skin.labelStyle)
        warningLabel.wrap = true
        root.add(warningLabel).width(WARNING_WIDTH).padBottom(TITLE_GAP).row()

        root.add(menuButton("CONFIRM") { act(model.onConfirm()) }).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()
        root.add(menuButton("CANCEL") { act(model.onCancel()) }).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()
    }

    /**
     * Act on a model transition: commit to a new / resumed game (the owner navigates away), or — for a
     * NONE transition (phase changed, or a no-op) — just redraw the menu for the new phase.
     */
    private fun act(action: MainMenuModel.MenuAction) {
        when (action) {
            MainMenuModel.MenuAction.BEGIN_NEW_GAME -> onStartNewGame()
            MainMenuModel.MenuAction.RESUME_SAVED_GAME -> onContinue()
            MainMenuModel.MenuAction.NONE -> rebuild()
        }
    }

    /** A labelled menu button that runs [onTap] when clicked. */
    private fun menuButton(
        label: String,
        onTap: () -> Unit,
    ): TextButton {
        val button = TextButton(label, skin.settingsButtonStyle)
        button.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onTap()
                }
            },
        )
        return button
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "MainMenuScreen shown (continueEnabled=$continueEnabled)")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        stage.viewport.update(width, height, true)
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
        skin.dispose()
    }

    private companion object {
        const val TAG = "Screen"
        const val MARGIN = 32f
        const val TITLE_GAP = 24f

        // UC52: a tighter gap under the title when a notice line follows, so the title + notice read as a group.
        const val NOTICE_GAP = 12f
        const val BTN_GAP = 8f
        const val BTN_WIDTH = 240f
        const val BTN_HEIGHT = 64f
        const val WARNING_WIDTH = 420f
    }
}
