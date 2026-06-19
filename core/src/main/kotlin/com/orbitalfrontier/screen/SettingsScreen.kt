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
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning

/**
 * The main-menu settings screen (UC37 AC#4): a standalone surface reached from [MainMenuScreen]'s SETTINGS
 * button, so settings are configurable before a game is even entered (the in-flight [SettingsOverlay] covers
 * the pause path). It hosts the SAME shared, grouped [SettingsPanel] builder the overlay does — one source
 * of truth, so the two surfaces can never drift.
 *
 * Mirrors [MainMenuScreen]'s resource model: it owns a [OrbitalUiSkin] + [Stage] and releases them in
 * [dispose] (the game disposes every owned screen explicitly, since libGDX `setScreen` only `hide()`s the
 * previous screen). The stage's [ScreenViewport] is built at the persisted UI scale (via [applyUiScale]),
 * and a live UI-scale change re-applies it to this very viewport ([applyUiScaleLive]) so the effect is
 * immediate without leaving the screen.
 *
 * Menu-context wiring of the panel's live callbacks (the gameplay surfaces don't exist here, so several are
 * persistence-only):
 *  - handedness + joystick tuning: no live on-screen control to update — the panel still persists them, and
 *    [OrbitalFrontierGame] re-reads them fresh when a game is entered, so a change made here takes effect on
 *    the next flight.
 *  - UI scale: re-applied to this screen's own viewport immediately ([applyUiScaleLive]).
 *  - replay tutorial: there is no running game to replay into, so this **re-arms the first-run flag**
 *    (persists `tutorial_completed = false`) — the next new game shows the onboarding again. An honest,
 *    useful menu-context meaning rather than a dead button.
 */
class SettingsScreen(
    private val logger: Logger,
    repository: SettingsRepository,
    private val saveExecutor: SaveExecutor,
    audio: AudioService,
    initialHandedness: Handedness,
    initialAudio: AudioSettings,
    initialJoystickTuning: JoystickTuning,
    initialUiScale: Float,
    initialColorVisionMode: ColorVisionMode,
    initialTextScale: Float,
    initialReducedMotion: Boolean,
    private val onBack: () -> Unit,
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val viewport = ScreenViewport().apply { applyUiScale() }
    private val stage = Stage(viewport)

    private val panel =
        SettingsPanel(
            skin = skin,
            repository = repository,
            saveExecutor = saveExecutor,
            initialHandedness = initialHandedness,
            initialAudio = initialAudio,
            initialJoystickTuning = initialJoystickTuning,
            initialUiScale = initialUiScale,
            initialColorVisionMode = initialColorVisionMode,
            initialTextScale = initialTextScale,
            initialReducedMotion = initialReducedMotion,
            audio = audio,
            // No on-screen joystick / controls to relayout on the menu — the panel persists these, and the
            // game re-reads them when a flight starts.
            onHandednessChanged = {},
            onJoystickTuningChanged = {},
            // Re-apply the new scale to this screen's own viewport so the change is visible immediately.
            onUiScaleChanged = { applyUiScaleLive() },
            // UC39: colourblind mode is already applied to the global Palette by the panel — this menu's
            // surfaces use neutral colours, so nothing extra to refresh; the next redraw reflects it.
            onColorVisionModeChanged = {},
            // UC39: re-apply the new text scale to this screen's own skin font and re-flow the layout so the
            // larger/smaller text is visible immediately without leaving the screen.
            onTextScaleChanged = { applyTextScaleLive() },
            // UC39: reduced motion is a global the (starfield-less) menu doesn't render — already applied.
            onReducedMotionChanged = {},
            // No running game to replay into here: re-arm first-run so the next new game shows the tutorial.
            onReplayTutorial = { saveExecutor.execute { repository.saveTutorialCompleted(false) } },
        )

    private val root = Table()

    init {
        skin.installTapSound(stage)
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel

        root.add(Label("SETTINGS", skin.titleLabelStyle)).padBottom(TITLE_GAP).row()
        root.add(panel.scrollPane()).width(PANEL_WIDTH).height(PANEL_HEIGHT).padBottom(TITLE_GAP).row()
        root.add(backButton()).size(BTN_WIDTH, BTN_HEIGHT).pad(BTN_GAP).row()

        stage.addActor(root)
    }

    private fun backButton(): TextButton {
        val button = TextButton("BACK", skin.settingsButtonStyle)
        button.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onBack()
                }
            },
        )
        return button
    }

    /** Re-apply the (already-updated global) [com.orbitalfrontier.render.UiScale] to this live viewport. */
    private fun applyUiScaleLive() {
        viewport.applyUiScale()
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    /**
     * UC39: re-apply the (already-updated global) [com.orbitalfrontier.render.TextScale] to this screen's
     * skin font and invalidate the layout so the resized text re-flows immediately (the buttons/labels
     * share the one skin font, so a single re-apply covers them all).
     */
    private fun applyTextScaleLive() {
        skin.applyTextScale()
        root.invalidateHierarchy()
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "SettingsScreen shown")
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
        const val BTN_GAP = 8f
        const val BTN_WIDTH = 240f
        const val BTN_HEIGHT = 64f
        const val PANEL_WIDTH = 360f
        const val PANEL_HEIGHT = 360f
    }
}
