package com.orbitalfrontier.audio

import com.orbitalfrontier.settings.AudioSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [AudioSettings] value type (UC31 AC#3): its defaults and the [coerced]
 * volume-clamping that protects the device layer from a corrupt save or an out-of-range future control.
 */
class AudioSettingsTest {
    @Test
    fun `default audio is unmuted with full SFX and half music`() {
        val d = AudioSettings.DEFAULT
        assertFalse("default is unmuted", d.masterMuted)
        assertEquals("default SFX at full", 1.0f, d.sfxVolume, 0f)
        assertEquals("default music at half (so it sits under the effects)", 0.5f, d.musicVolume, 0f)
    }

    @Test
    fun `the default is already in range, so coerced is a no-op identity`() {
        // Already-valid values must not allocate a new copy; coerced() returns `this`.
        assertSame("an in-range value coerces to itself", AudioSettings.DEFAULT, AudioSettings.DEFAULT.coerced())
    }

    @Test
    fun `an in-range custom value is returned unchanged by coerced`() {
        val v = AudioSettings(masterMuted = true, sfxVolume = 0.3f, musicVolume = 0.7f)
        assertSame(v, v.coerced())
    }

    @Test
    fun `coerced clamps an over-1 volume down to 1`() {
        val coerced = AudioSettings(masterMuted = false, sfxVolume = 4.2f, musicVolume = 1.5f).coerced()
        assertEquals(1.0f, coerced.sfxVolume, 0f)
        assertEquals(1.0f, coerced.musicVolume, 0f)
    }

    @Test
    fun `coerced clamps a negative volume up to 0`() {
        val coerced = AudioSettings(masterMuted = true, sfxVolume = -0.1f, musicVolume = -100f).coerced()
        assertEquals(0.0f, coerced.sfxVolume, 0f)
        assertEquals(0.0f, coerced.musicVolume, 0f)
    }

    @Test
    fun `coerced collapses a NaN volume to that channel's default`() {
        val coerced = AudioSettings(masterMuted = false, sfxVolume = Float.NaN, musicVolume = Float.NaN).coerced()
        assertEquals("NaN SFX → default SFX", AudioSettings.DEFAULT.sfxVolume, coerced.sfxVolume, 0f)
        assertEquals("NaN music → default music", AudioSettings.DEFAULT.musicVolume, coerced.musicVolume, 0f)
    }

    @Test
    fun `coerced clamps an infinite volume to the range boundary`() {
        val coerced =
            AudioSettings(
                masterMuted = false,
                sfxVolume = Float.POSITIVE_INFINITY,
                musicVolume = Float.NEGATIVE_INFINITY,
            ).coerced()
        assertEquals("+inf SFX clamps to 1.0", 1.0f, coerced.sfxVolume, 0f)
        assertEquals("-inf music clamps to 0.0", 0.0f, coerced.musicVolume, 0f)
    }

    @Test
    fun `coerced preserves the mute flag and the valid channel while fixing the other`() {
        // Only the SFX channel is out of range; the mute flag and the in-range music channel survive.
        val coerced = AudioSettings(masterMuted = true, sfxVolume = 2.0f, musicVolume = 0.42f).coerced()
        assertTrue("mute flag preserved", coerced.masterMuted)
        assertEquals("out-of-range SFX clamped", 1.0f, coerced.sfxVolume, 0f)
        assertEquals("in-range music untouched", 0.42f, coerced.musicVolume, 0f)
    }

    @Test
    fun `the boundary values 0 and 1 are valid and left untouched`() {
        val v = AudioSettings(masterMuted = false, sfxVolume = 0.0f, musicVolume = 1.0f)
        val coerced = v.coerced()
        assertEquals(0.0f, coerced.sfxVolume, 0f)
        assertEquals(1.0f, coerced.musicVolume, 0f)
    }
}
