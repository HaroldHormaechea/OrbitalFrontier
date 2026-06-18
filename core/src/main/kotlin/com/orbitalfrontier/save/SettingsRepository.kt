package com.orbitalfrontier.save

import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.Handedness

/**
 * Persistence boundary for player settings (handedness, AC#8; audio preferences, UC31 AC#3).
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
}
