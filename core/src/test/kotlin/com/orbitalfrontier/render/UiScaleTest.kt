package com.orbitalfrontier.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [UiScale] (ADR 0015) — the single global UI/HUD-scale knob.
 *
 * [UiScale.factor] is the one source of truth that multiplies the on-screen size of the UI/HUD layer
 * (×2 on high-density phone screens). It is pure and engine-free; these tests pin its value and the
 * stability the rest of the rendering code relies on (a single, constant, positive factor — never
 * read into simulation math, so determinism/replay is unaffected).
 */
class UiScaleTest {
    @Test
    fun factorIsTwo() {
        assertEquals("UI scale factor must be ×2 (ADR 0015)", 2f, UiScale.factor, 0f)
    }

    @Test
    fun factorIsPositive() {
        assertTrue("UI scale factor must be positive", UiScale.factor > 0f)
    }

    @Test
    fun factorIsStableAcrossReads() {
        // It is a single source of truth (a getter over a const) — every read returns the same value.
        assertEquals(UiScale.factor, UiScale.factor, 0f)
    }
}
