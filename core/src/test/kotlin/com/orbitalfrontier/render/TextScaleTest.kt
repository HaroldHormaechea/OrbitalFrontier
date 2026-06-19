package com.orbitalfrontier.render

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TextScale] (UC39 AC#2) — the global UI text-size knob, shaped exactly like [UiScale].
 *
 * [TextScale.factor] multiplies the Scene2D skin font on top of [UiScale], so a player can enlarge UI text
 * independently of the overall UI magnification. These pin the clamp range `[0.85 .. 1.4]`, the NaN/∞
 * collapse to the default, the set/coerce/reset surface, and — the documented "font-blur ceiling" — that
 * even at the simultaneous-max `UiScale.MAX × TextScale.MAX` corner the master glyphs are only mildly
 * upscaled (≤ 1.35×), so text softens gracefully rather than crashing or shredding.
 *
 * **Test discipline:** [factor] is MUTABLE GLOBAL STATE shared by the whole suite (the UiScaleTest
 * precedent), so every test restores the default via [TextScale.reset] in [tearDown] (and re-asserts the
 * baseline in [setUp]); otherwise the suite would become order-dependent and flaky.
 */
class TextScaleTest {
    @Before
    fun setUp() {
        TextScale.reset()
    }

    @After
    fun tearDown() {
        // MANDATORY: restore the global so other suites (skin/layout tests, the font-scale invariant) see
        // the ×1 default they assume.
        TextScale.reset()
    }

    @Test
    fun `the default factor is one`() {
        assertEquals("text scale defaults to ×1 (no extra magnification)", TextScale.DEFAULT_FACTOR, TextScale.factor, 0f)
        assertEquals(1.0f, TextScale.factor, 0f)
    }

    @Test
    fun `the clamp range is the tested accessibility bounds`() {
        assertEquals(0.85f, TextScale.MIN_FACTOR, 0f)
        assertEquals(1.4f, TextScale.MAX_FACTOR, 0f)
    }

    // --- set(): writes the global, coercing into range, and returns the stored value ----------------

    @Test
    fun `set stores an in-range value verbatim and returns it`() {
        val stored = TextScale.set(1.2f)
        assertEquals("set returns exactly what took effect", 1.2f, stored, 0f)
        assertEquals("the global reflects the write", 1.2f, TextScale.factor, 0f)
    }

    @Test
    fun `set clamps a below-range value up to the minimum`() {
        assertEquals(TextScale.MIN_FACTOR, TextScale.set(0.1f), 0f)
        assertEquals(TextScale.MIN_FACTOR, TextScale.factor, 0f)
    }

    @Test
    fun `set clamps an above-range value down to the maximum`() {
        assertEquals(TextScale.MAX_FACTOR, TextScale.set(5f), 0f)
        assertEquals(TextScale.MAX_FACTOR, TextScale.factor, 0f)
    }

    @Test
    fun `set collapses NaN and infinities to the default`() {
        assertEquals("NaN collapses to the default", TextScale.DEFAULT_FACTOR, TextScale.set(Float.NaN), 0f)
        assertEquals("+inf collapses to the default", TextScale.DEFAULT_FACTOR, TextScale.set(Float.POSITIVE_INFINITY), 0f)
        assertEquals("-inf collapses to the default", TextScale.DEFAULT_FACTOR, TextScale.set(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(TextScale.DEFAULT_FACTOR, TextScale.factor, 0f)
    }

    // --- coerce(): pure clamp, never mutates the global ---------------------------------------------

    @Test
    fun `coerce clamps without mutating the global`() {
        assertEquals(TextScale.MAX_FACTOR, TextScale.coerce(9f), 0f)
        assertEquals(TextScale.MIN_FACTOR, TextScale.coerce(0f), 0f)
        assertEquals(1.25f, TextScale.coerce(1.25f), 0f)
        assertEquals(TextScale.DEFAULT_FACTOR, TextScale.coerce(Float.NaN), 0f)
        assertEquals("coerce is pure — the global is still the default", TextScale.DEFAULT_FACTOR, TextScale.factor, 0f)
    }

    // --- reset(): back to the default --------------------------------------------------------------

    @Test
    fun `reset restores the default after a write`() {
        TextScale.set(1.4f)
        assertEquals(1.4f, TextScale.factor, 0f)
        TextScale.reset()
        assertEquals(TextScale.DEFAULT_FACTOR, TextScale.factor, 0f)
    }

    // --- Font-blur ceiling (UC39): the simultaneous-max corner stays a mild, graceful upscale --------

    @Test
    fun `the simultaneous-max UI-scale and text-scale corner upscales the master font no more than 1_35x`() {
        // The skin font's worst on-screen size is GameFont.NORM × UiScale.MAX × TextScale.MAX of the baked
        // master. Unlike the default-UiScale invariant (Uc28FontScaleInvariantTest), this absolute corner is
        // allowed to magnify slightly — the bundled font softens it with Linear filtering — but the upscale
        // must stay within the documented ≤1.31× ceiling (guarded a touch loose at 1.35×). If a future
        // NORM/cap/clamp edit pushed it past that, text would visibly shred and the font must be re-baked.
        val cornerScale = GameFont.NORM * UiScale.MAX_FACTOR * TextScale.MAX_FACTOR
        assertTrue(
            "UC39: NORM ${GameFont.NORM} × UiScale.MAX ${UiScale.MAX_FACTOR} × TextScale.MAX " +
                "${TextScale.MAX_FACTOR} = $cornerScale must stay <= 1.35 (a graceful mild upscale).",
            cornerScale <= 1.35f,
        )
        // Same property restated in pixels, against the baked master cap.
        val cornerPx = GameFont.LEGACY_BASE_PX * UiScale.MAX_FACTOR * TextScale.MAX_FACTOR
        assertTrue(
            "UC39: the worst-case on-screen size ${cornerPx}px must stay <= 1.35 × the baked master " +
                "${GameFont.BAKE_CAP_PX}px (${GameFont.BAKE_CAP_PX * 1.35f}px).",
            cornerPx <= GameFont.BAKE_CAP_PX * 1.35f,
        )
    }
}
