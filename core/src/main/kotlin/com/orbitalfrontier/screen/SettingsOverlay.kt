package com.orbitalfrontier.screen

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning

/**
 * In-flight settings **modal** (UC37 panel; UC56 presentation): a full-screen dimming backdrop + a centred
 * column holding the shared, grouped [SettingsPanel] (in a scroll pane) and a single BACK button.
 *
 * UC56 changed this from a persistent top-left *corner panel* (which overlapped the HUD readout block and
 * the joystick during flight) into a proper modal surfaced on demand: the play screen's top-left Settings
 * ball freezes the sim and shows this modal; BACK returns to flight (auto-resume). The pause menu's SETTINGS
 * action reuses the same modal over the pause backdrop, where BACK returns to the pause menu. The screen
 * decides what BACK does by wiring [onBack]; this widget only owns the view.
 *
 * It wraps the SAME [SettingsPanel] builder the main-menu [SettingsScreen] uses, so the two surfaces can
 * never drift (one source of truth). All control logic (apply-live-then-persist, the per-field transactional
 * writes, the live-apply callbacks) lives in [SettingsPanel]; this class only wires the play-screen-specific
 * live targets:
 *  - [onHandednessChanged] re-lays-out the on-screen controls,
 *  - [onJoystickTuningChanged] updates the live joystick boundary,
 *  - [onUiScaleChanged] re-applies the UI scale to the play stage's viewport,
 *  - [onColorVisionModeChanged] / [onTextScaleChanged] / [onReducedMotionChanged] apply the UC39
 *    accessibility prefs live, and
 *  - [onReplayTutorial] restarts the first-run onboarding.
 *
 * The backdrop texture is generated + owned here (like [com.orbitalfrontier.screen.controls.PauseOverlay]);
 * [dispose] releases it. The [SaveExecutor]'s lifecycle is owned by the app, not this overlay.
 */
class SettingsOverlay(
    skin: OrbitalUiSkin,
    repository: SettingsRepository,
    saveExecutor: SaveExecutor,
    initialHandedness: Handedness,
    initialAudio: AudioSettings,
    initialJoystickTuning: JoystickTuning,
    initialUiScale: Float,
    initialColorVisionMode: ColorVisionMode,
    initialTextScale: Float,
    initialReducedMotion: Boolean,
    audio: AudioService,
    onHandednessChanged: (Handedness) -> Unit,
    onJoystickTuningChanged: (JoystickTuning) -> Unit,
    onUiScaleChanged: (Float) -> Unit,
    onColorVisionModeChanged: (ColorVisionMode) -> Unit,
    onTextScaleChanged: (Float) -> Unit,
    onReducedMotionChanged: (Boolean) -> Unit,
    onReplayTutorial: () -> Unit = {},
) : Disposable {
    /** Invoked when the modal's BACK button is tapped; the screen wires it (→ flight, or → pause menu). */
    var onBack: () -> Unit = {}

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
            onHandednessChanged = onHandednessChanged,
            onJoystickTuningChanged = onJoystickTuningChanged,
            onUiScaleChanged = onUiScaleChanged,
            onColorVisionModeChanged = onColorVisionModeChanged,
            onTextScaleChanged = onTextScaleChanged,
            onReducedMotionChanged = onReducedMotionChanged,
            onReplayTutorial = onReplayTutorial,
        )

    private val backdropTexture: Texture
    private val backdrop: Image
    private val column = Table()
    private val backButton = TextButton(BACK_LABEL, skin.settingsButtonStyle)

    /** The full-screen modal group (backdrop + centred panel column); positioned/sized by the screen. */
    val actor: Group = Group()

    init {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(0f, 0f, 0f, BACKDROP_ALPHA)
            pixmap.fill()
            backdropTexture = Texture(pixmap)
        } finally {
            pixmap.dispose()
        }
        backdrop = Image(TextureRegionDrawable(backdropTexture))
        // Swallow every tap so the modal backdrop blocks the flight controls underneath while open.
        backdrop.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) = Unit
            },
        )

        backButton.addListener(
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

        column.add(panel.scrollPane()).width(PANEL_WIDTH).height(PANEL_HEIGHT).padBottom(BUTTON_GAP)
        column.row()
        column.add(backButton).width(BUTTON_WIDTH).height(BUTTON_HEIGHT)

        actor.addActor(backdrop)
        actor.addActor(column)
    }

    /**
     * UC39: re-flow the panel column after a live text-size change (the shared skin font already carries the
     * new scale) so the resized labels re-measure within the fixed footprint. The host calls this after
     * [com.orbitalfrontier.render.TextScale.set] + the skin's `applyTextScale`.
     */
    fun reflow() {
        column.invalidateHierarchy()
    }

    /** Resize to fill the stage and re-centre the panel column. Called from the screen's layout pass. */
    fun resize(
        width: Float,
        height: Float,
    ) {
        actor.setBounds(0f, 0f, width, height)
        backdrop.setBounds(0f, 0f, width, height)
        column.pack()
        column.setPosition((width - column.width) / 2f, (height - column.height) / 2f)
    }

    override fun dispose() {
        backdropTexture.dispose()
    }

    private companion object {
        const val BACKDROP_ALPHA = 0.7f
        const val BACK_LABEL = "BACK"

        // Fixed footprint of the scrolled settings panel inside the modal (the grouped content scrolls
        // vertically within this box); the BACK button sits below it. Centred on the stage by [resize].
        const val PANEL_WIDTH = 260f
        const val PANEL_HEIGHT = 320f
        const val BUTTON_WIDTH = 260f
        const val BUTTON_HEIGHT = 56f
        const val BUTTON_GAP = 16f
    }
}
