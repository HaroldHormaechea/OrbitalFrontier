package com.orbitalfrontier.render

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [UiScale] (ADR 0015; made player-adjustable in UC37 / ADR 0025) — the single global
 * UI/HUD-scale knob.
 *
 * [UiScale.factor] is the one source of truth that multiplies the on-screen size of the UI/HUD layer
 * (×2 by default on high-density phone screens). UC37 promoted it from a compile-time constant to a
 * **clamped mutable global**: a DISPLAY-group settings control writes it (via [UiScale.set]) and the
 * persisted value is restored at startup. These tests pin the clamp range `[1.0 .. 3.0]`, the NaN/∞
 * collapse to the default, the set/coerce/reset surface, and the value the rest of the rendering code
 * relies on. It remains rendering-only — never read into simulation math — so determinism/replay is
 * unaffected.
 *
 * **Test discipline:** [factor] is now MUTABLE GLOBAL STATE shared by the whole suite, so every test
 * here restores the default via [UiScale.reset] in [tearDown] (and re-asserts the baseline in [setUp]),
 * otherwise the suite would become order-dependent and flaky.
 */
class UiScaleTest {
    @Before
    fun setUp() {
        // Start every test from the known default, independent of whatever ran before.
        UiScale.reset()
    }

    @After
    fun tearDown() {
        // MANDATORY: restore the global so other suites (UiScale-reading layout tests, font-scale
        // invariants, etc.) see the ×2 default they assume.
        UiScale.reset()
    }

    @Test
    fun `the default factor is two`() {
        assertEquals("UI scale factor defaults to ×2 (ADR 0015)", UiScale.DEFAULT_FACTOR, UiScale.factor, 0f)
        assertEquals(2f, UiScale.factor, 0f)
    }

    @Test
    fun `the factor is positive`() {
        assertTrue("UI scale factor must be positive", UiScale.factor > 0f)
    }

    @Test
    fun `the clamp range is one to three`() {
        assertEquals(1f, UiScale.MIN_FACTOR, 0f)
        assertEquals(3f, UiScale.MAX_FACTOR, 0f)
    }

    // --- set(): writes the global, coercing into range, and returns the stored value ----------------

    @Test
    fun `set stores an in-range value verbatim and returns it`() {
        val stored = UiScale.set(2.5f)
        assertEquals("set returns exactly what took effect", 2.5f, stored, 0f)
        assertEquals("the global reflects the write", 2.5f, UiScale.factor, 0f)
    }

    @Test
    fun `set clamps a below-range value up to the minimum`() {
        assertEquals(UiScale.MIN_FACTOR, UiScale.set(0.5f), 0f)
        assertEquals(UiScale.MIN_FACTOR, UiScale.factor, 0f)
    }

    @Test
    fun `set clamps an above-range value down to the maximum`() {
        assertEquals(UiScale.MAX_FACTOR, UiScale.set(5f), 0f)
        assertEquals(UiScale.MAX_FACTOR, UiScale.factor, 0f)
    }

    @Test
    fun `set collapses NaN and infinities to the default`() {
        assertEquals("NaN collapses to the default", UiScale.DEFAULT_FACTOR, UiScale.set(Float.NaN), 0f)
        assertEquals("+inf collapses to the default", UiScale.DEFAULT_FACTOR, UiScale.set(Float.POSITIVE_INFINITY), 0f)
        assertEquals("-inf collapses to the default", UiScale.DEFAULT_FACTOR, UiScale.set(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(UiScale.DEFAULT_FACTOR, UiScale.factor, 0f)
    }

    // --- coerce(): pure clamp, never mutates the global ---------------------------------------------

    @Test
    fun `coerce clamps without mutating the global`() {
        assertEquals(UiScale.MAX_FACTOR, UiScale.coerce(9f), 0f)
        assertEquals(UiScale.MIN_FACTOR, UiScale.coerce(0f), 0f)
        assertEquals(2.25f, UiScale.coerce(2.25f), 0f)
        assertEquals(UiScale.DEFAULT_FACTOR, UiScale.coerce(Float.NaN), 0f)
        // The pure helper must not have touched the global.
        assertEquals("coerce is pure — the global is still the default", UiScale.DEFAULT_FACTOR, UiScale.factor, 0f)
    }

    // --- reset(): back to the default --------------------------------------------------------------

    @Test
    fun `reset restores the default after a write`() {
        UiScale.set(3f)
        assertEquals(3f, UiScale.factor, 0f)
        UiScale.reset()
        assertEquals(UiScale.DEFAULT_FACTOR, UiScale.factor, 0f)
    }

    @Test
    fun `the factor is stable across reads`() {
        UiScale.set(1.5f)
        // A single source of truth — repeated reads with no intervening write return the same value.
        assertEquals(UiScale.factor, UiScale.factor, 0f)
    }
}
