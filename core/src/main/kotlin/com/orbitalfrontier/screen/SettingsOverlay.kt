package com.orbitalfrontier.screen

import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning

/**
 * In-flight settings panel (UC37): a thin host around the shared, grouped [SettingsPanel] — the SAME
 * builder the main-menu [SettingsScreen] uses, so the two surfaces can never drift (one source of truth).
 *
 * It exposes the grouped panel wrapped in a [ScrollPane] as [actor]; the play screen positions/sizes/hides
 * it as a single unit (unchanged from when it was one button — UC32 surfaces it as the pause Settings
 * sub-view). The ScrollPane keeps the now-taller grouped content reachable inside the same fixed footprint.
 *
 * All control logic (apply-live-then-persist, the per-field transactional writes, the live-apply callbacks)
 * lives in [SettingsPanel]; this class only wires the play-screen-specific live targets:
 *  - [onHandednessChanged] re-lays-out the on-screen controls,
 *  - [onJoystickTuningChanged] updates the live joystick boundary,
 *  - [onUiScaleChanged] re-applies the UI scale to the play stage's viewport, and
 *  - [onReplayTutorial] restarts the first-run onboarding.
 *
 * The [SaveExecutor]'s lifecycle is owned by the app, not this overlay, so the overlay holds no disposable
 * resources of its own.
 */
class SettingsOverlay(
    skin: OrbitalUiSkin,
    repository: SettingsRepository,
    saveExecutor: SaveExecutor,
    initialHandedness: Handedness,
    initialAudio: AudioSettings,
    initialJoystickTuning: JoystickTuning,
    initialUiScale: Float,
    audio: AudioService,
    onHandednessChanged: (Handedness) -> Unit,
    onJoystickTuningChanged: (JoystickTuning) -> Unit,
    onUiScaleChanged: (Float) -> Unit,
    onReplayTutorial: () -> Unit = {},
) {
    private val panel =
        SettingsPanel(
            skin = skin,
            repository = repository,
            saveExecutor = saveExecutor,
            initialHandedness = initialHandedness,
            initialAudio = initialAudio,
            initialJoystickTuning = initialJoystickTuning,
            initialUiScale = initialUiScale,
            audio = audio,
            onHandednessChanged = onHandednessChanged,
            onJoystickTuningChanged = onJoystickTuningChanged,
            onUiScaleChanged = onUiScaleChanged,
            onReplayTutorial = onReplayTutorial,
        )

    /** The grouped, scrollable controls. Positioned/sized/hidden by the play screen as one unit. */
    val actor: ScrollPane = panel.scrollPane()
}
