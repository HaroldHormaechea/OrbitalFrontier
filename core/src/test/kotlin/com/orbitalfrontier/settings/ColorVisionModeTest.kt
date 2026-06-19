package com.orbitalfrontier.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-value tests for [ColorVisionMode] (UC39 AC#1) — the persisted colour-vision palette preference.
 *
 * It is an engine-free enum (like [Handedness]) so it round-trips through the settings store by NAME and
 * the render-layer palette is restored from it at startup. These tests pin the default, the toggle, and —
 * the load-bearing safety property — [ColorVisionMode.parse]'s graceful degradation of a null/unknown
 * stored value to [ColorVisionMode.DEFAULT], so a corrupt or future-written save row can never crash the
 * palette restore.
 */
class ColorVisionModeTest {
    @Test
    fun `the default mode is the standard palette`() {
        assertEquals("a fresh save uses the original design-system palette", ColorVisionMode.STANDARD, ColorVisionMode.DEFAULT)
    }

    @Test
    fun `toggled flips standard to colourblind-safe and back`() {
        assertEquals(ColorVisionMode.COLORBLIND_SAFE, ColorVisionMode.STANDARD.toggled())
        assertEquals(ColorVisionMode.COLORBLIND_SAFE.toggled(), ColorVisionMode.STANDARD)
        // Toggling twice is the identity — the settings control round-trips cleanly.
        assertEquals(ColorVisionMode.STANDARD, ColorVisionMode.STANDARD.toggled().toggled())
        assertNotEquals("the two modes are distinct", ColorVisionMode.STANDARD, ColorVisionMode.COLORBLIND_SAFE)
    }

    @Test
    fun `parse resolves every known mode name exactly`() {
        for (mode in ColorVisionMode.entries) {
            assertEquals("parse round-trips the stored NAME", mode, ColorVisionMode.parse(mode.name))
        }
    }

    @Test
    fun `parse degrades a null stored value to the default`() {
        assertEquals("a missing column reads back as the standard palette", ColorVisionMode.DEFAULT, ColorVisionMode.parse(null))
    }

    @Test
    fun `parse degrades an unknown or malformed stored value to the default`() {
        assertEquals(ColorVisionMode.DEFAULT, ColorVisionMode.parse("NOT_A_REAL_MODE"))
        assertEquals(ColorVisionMode.DEFAULT, ColorVisionMode.parse(""))
        // parse matches the enum NAME exactly (it is not case-insensitive), so a lower-cased value degrades.
        assertEquals(ColorVisionMode.DEFAULT, ColorVisionMode.parse("standard"))
    }
}
