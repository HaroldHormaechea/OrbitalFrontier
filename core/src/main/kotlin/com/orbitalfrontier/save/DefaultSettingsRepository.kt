package com.orbitalfrontier.save

import com.orbitalfrontier.render.MotionPreference
import com.orbitalfrontier.render.TextScale
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning

/**
 * In-memory [SettingsRepository] used in **degraded mode** (UC56) — when the on-disk save could not be
 * opened (UC52 `UnsupportedNewer` / `Unreadable`), so there is no settings database.
 *
 * Settings share the single save DB (ADR 0002), so an unopenable save means no persistent settings store.
 * Before UC56 the main menu therefore left SETTINGS inert in degraded mode (a silent no-op), which read as
 * a broken button. This repository decouples the *global UI/accessibility preferences* from save state: it
 * seeds every preference from its `*.DEFAULT`, applies changes **in memory for the session**, and reads them
 * back — so the settings screen opens and works. The limitation is honest: changes are **session-only**
 * (lost on exit) until a New Game creates a fresh DB; nothing here throws, and there is no crash.
 *
 * [ensureInitialized] is a no-op (there is no row to seed); the tutorial-completed flag defaults to `false`
 * (the onboarding still runs by default), matching the persistent repository's degrade-to-default contract.
 * Pure Kotlin (no engine/SQL types) so it is fully JVM-testable (ADR 0001).
 */
class DefaultSettingsRepository : SettingsRepository {
    private var handedness: Handedness = Handedness.DEFAULT
    private var audio: AudioSettings = AudioSettings.DEFAULT
    private var tutorialCompleted: Boolean = false
    private var joystickTuning: JoystickTuning = JoystickTuning.DEFAULT
    private var uiScale: Float = UiScale.DEFAULT_FACTOR
    private var colorVisionMode: ColorVisionMode = ColorVisionMode.DEFAULT
    private var textScale: Float = TextScale.DEFAULT_FACTOR
    private var reducedMotion: Boolean = MotionPreference.DEFAULT_REDUCED

    override fun ensureInitialized() = Unit

    override fun loadHandedness(): Handedness = handedness

    override fun saveHandedness(handedness: Handedness) {
        this.handedness = handedness
    }

    override fun loadAudioSettings(): AudioSettings = audio.coerced()

    override fun saveAudioSettings(settings: AudioSettings) {
        this.audio = settings.coerced()
    }

    override fun loadTutorialCompleted(): Boolean = tutorialCompleted

    override fun saveTutorialCompleted(completed: Boolean) {
        this.tutorialCompleted = completed
    }

    override fun loadJoystickTuning(): JoystickTuning = joystickTuning.coerced()

    override fun saveJoystickTuning(tuning: JoystickTuning) {
        this.joystickTuning = tuning.coerced()
    }

    override fun loadUiScale(): Float = UiScale.coerce(uiScale)

    override fun saveUiScale(factor: Float) {
        this.uiScale = UiScale.coerce(factor)
    }

    override fun loadColorVisionMode(): ColorVisionMode = colorVisionMode

    override fun saveColorVisionMode(mode: ColorVisionMode) {
        this.colorVisionMode = mode
    }

    override fun loadTextScale(): Float = TextScale.coerce(textScale)

    override fun saveTextScale(factor: Float) {
        this.textScale = TextScale.coerce(factor)
    }

    override fun loadReducedMotion(): Boolean = reducedMotion

    override fun saveReducedMotion(reduced: Boolean) {
        this.reducedMotion = reduced
    }
}
