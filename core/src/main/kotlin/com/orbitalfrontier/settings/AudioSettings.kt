package com.orbitalfrontier.settings

/**
 * Persisted audio preferences (UC31 AC#3): a master mute plus independent SFX and music volumes.
 *
 * A pure value type beside [Handedness] / [ControlsLayout] — no engine types, so it is fully
 * JVM-testable and round-trips through the settings store unchanged (ADR 0001; UC31 AC#4). Volumes are
 * normalised gains in `0f..1f`; [coerced] clamps any out-of-range or non-finite value a corrupt save or
 * a future control might produce, so the device layer can apply the result without re-validating.
 */
data class AudioSettings(
    val masterMuted: Boolean,
    val sfxVolume: Float,
    val musicVolume: Float,
) {
    /** This value with both volumes clamped to `0f..1f` (NaN/∞ collapse to the channel default). */
    fun coerced(): AudioSettings {
        val sfx = clampVolume(sfxVolume, DEFAULT.sfxVolume)
        val music = clampVolume(musicVolume, DEFAULT.musicVolume)
        return if (sfx == sfxVolume && music == musicVolume) this else copy(sfxVolume = sfx, musicVolume = music)
    }

    companion object {
        /** Default audio: unmuted, SFX at full, music at half so it sits under the effects. */
        val DEFAULT = AudioSettings(masterMuted = false, sfxVolume = 1.0f, musicVolume = 0.5f)

        private fun clampVolume(
            value: Float,
            fallback: Float,
        ): Float =
            when {
                value.isNaN() -> fallback
                value < 0f -> 0f
                value > 1f -> 1f
                else -> value
            }
    }
}
