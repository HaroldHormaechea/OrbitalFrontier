package com.orbitalfrontier.save

import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning

/**
 * Persistence boundary for player settings (handedness, AC#8; audio preferences, UC31 AC#3; the UC39
 * accessibility preferences — colourblind mode, text scale, reduced motion).
 *
 * Small, focused interface (ISP). `core` and the UI depend on this abstraction; the SQLDelight
 * implementation is injected. Reads degrade to a sensible default rather than throwing on a
 * missing/corrupt row; writes are transactional and corruption-safe (coding-guidelines §
 * "Error handling"). Each preference has its own targeted write so persisting one never clobbers
 * another (UC31 Risk 1).
 */
interface SettingsRepository {
    /**
     * Ensure the save metadata + the single settings row exist (seeds the `save_version` row and a
     * default settings row on first run). Idempotent; safe to call on every launch.
     */
    fun ensureInitialized()

    /** Current handedness, or [Handedness.DEFAULT] if none is stored yet or the row is unreadable. */
    fun loadHandedness(): Handedness

    /**
     * Persist [handedness] atomically without touching the audio columns. On failure the last good
     * value is left intact and the error is logged; this call does not throw (autosave-style graceful
     * degradation).
     */
    fun saveHandedness(handedness: Handedness)

    /**
     * Current audio settings, or [AudioSettings.DEFAULT] if none is stored yet or the row is
     * unreadable. The returned value is always [AudioSettings.coerced] (volumes clamped to `0f..1f`).
     */
    fun loadAudioSettings(): AudioSettings

    /**
     * Persist [settings] atomically (coerced first) without touching the handedness column. On
     * failure the last good value is left intact and the error is logged; this call does not throw.
     */
    fun saveAudioSettings(settings: AudioSettings)

    /**
     * Whether the first-run tutorial has already been shown (UC36 AC#3). `false` on a fresh save, a
     * migrated pre-UC36 save, or an unreadable/corrupt row — so the onboarding runs by default and only
     * a deliberately-persisted `true` suppresses it.
     */
    fun loadTutorialCompleted(): Boolean

    /**
     * Persist the first-run-tutorial [completed] flag atomically without touching the handedness or audio
     * columns (UC36 AC#3). On failure the last good value is left intact and the error is logged; this
     * call does not throw (autosave-style graceful degradation).
     */
    fun saveTutorialCompleted(completed: Boolean)

    /**
     * Current joystick tuning (UC37), or [JoystickTuning.DEFAULT] if none is stored yet or the row is
     * unreadable. The returned value is always [JoystickTuning.coerced] (sensitivity + deadzone clamped
     * to their valid ranges).
     */
    fun loadJoystickTuning(): JoystickTuning

    /**
     * Persist [tuning] atomically (coerced first) without touching any other settings column (UC37). On
     * failure the last good value is left intact and the error is logged; this call does not throw.
     */
    fun saveJoystickTuning(tuning: JoystickTuning)

    /**
     * Current UI-scale factor (UC37), or the [com.orbitalfrontier.render.UiScale.DEFAULT_FACTOR] if none
     * is stored yet or the row is unreadable. The returned value is always coerced into the valid range
     * ([com.orbitalfrontier.render.UiScale.MIN_FACTOR]..[com.orbitalfrontier.render.UiScale.MAX_FACTOR]).
     */
    fun loadUiScale(): Float

    /**
     * Persist the UI-scale [factor] atomically (coerced first) without touching any other settings column
     * (UC37). On failure the last good value is left intact and the error is logged; this call does not
     * throw (autosave-style graceful degradation).
     */
    fun saveUiScale(factor: Float)

    /**
     * Current colour-vision palette mode (UC39 AC#1), or [ColorVisionMode.DEFAULT] if none is stored yet
     * or the row is unreadable / holds an unknown value (parsed via [ColorVisionMode.parse], so a corrupt
     * value degrades to the standard palette rather than throwing).
     */
    fun loadColorVisionMode(): ColorVisionMode

    /**
     * Persist the colour-vision [mode] atomically without touching any other settings column (UC39). On
     * failure the last good value is left intact and the error is logged; this call does not throw.
     */
    fun saveColorVisionMode(mode: ColorVisionMode)

    /**
     * Current UI text-scale factor (UC39 AC#2), or [com.orbitalfrontier.render.TextScale.DEFAULT_FACTOR] if
     * none is stored yet or the row is unreadable. The returned value is always coerced into the valid range
     * ([com.orbitalfrontier.render.TextScale.MIN_FACTOR]..[com.orbitalfrontier.render.TextScale.MAX_FACTOR]).
     */
    fun loadTextScale(): Float

    /**
     * Persist the text-scale [factor] atomically (coerced first) without touching any other settings column
     * (UC39). On failure the last good value is left intact and the error is logged; this call does not throw.
     */
    fun saveTextScale(factor: Float)

    /**
     * Current reduced-motion preference (UC39 AC#3), or [com.orbitalfrontier.render.MotionPreference.DEFAULT_REDUCED]
     * (motion on) if none is stored yet or the row is unreadable.
     */
    fun loadReducedMotion(): Boolean

    /**
     * Persist the reduced-motion [reduced] flag atomically without touching any other settings column (UC39).
     * On failure the last good value is left intact and the error is logged; this call does not throw.
     */
    fun saveReducedMotion(reduced: Boolean)
}
