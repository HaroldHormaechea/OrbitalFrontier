package com.orbitalfrontier.render

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM invariant guarding the *minify-don't-magnify* property that makes UC28 text crisp (AC#2).
 *
 * The true visual verification (no bilinear-stretch blur on a real device/DPI bucket) is a GL/emulator
 * concern outside `:core:test` and is flagged to the team lead as a non-JVM acceptance item. What IS
 * testable here is the scale **math** that underpins it: every consumer renders the [GameFont.BAKE_CAP_PX]
 * master glyphs *downscaled*, never upscaled. If a future [UiScale.factor] bump (or a NORM/cap edit) ever
 * pushed the largest on-screen font size above the baked master size, the glyphs would be magnified and
 * blur would return — this test fails the build first.
 *
 * The largest scale any consumer applies is the HUD's `GameFont.NORM × UiScale.factor` (the minimap and
 * overlay multiply by an extra <1 LABEL_FONT_SCALE, so they are strictly smaller; the controls skin uses
 * `GameFont.NORM` alone). That HUD regime is pinned as [MAX_REGIME_SCALE] below.
 */
class Uc28FontScaleInvariantTest {
    @Test
    fun `NORM equals the legacy-over-cap ratio (the downscale is anchored to the bake size)`() {
        assertTrue(
            "GameFont.NORM must equal LEGACY_BASE_PX / BAKE_CAP_PX so the switch is size-neutral",
            kotlin.math.abs(GameFont.NORM - GameFont.LEGACY_BASE_PX / GameFont.BAKE_CAP_PX) < 1e-6f,
        )
    }

    @Test
    fun `the largest on-screen font size never exceeds the baked master size`() {
        // BAKE_CAP_PX >= LEGACY_BASE_PX * UiScale.factor * maxRegimeScale  ⇔  glyphs are always minified.
        val largestOnScreenPx = GameFont.LEGACY_BASE_PX * UiScale.factor * MAX_REGIME_SCALE
        assertTrue(
            "AC#2: the largest on-screen font size (${largestOnScreenPx}px = LEGACY_BASE_PX " +
                "${GameFont.LEGACY_BASE_PX} × UiScale.factor ${UiScale.factor} × maxRegime $MAX_REGIME_SCALE) " +
                "must stay <= the baked master ${GameFont.BAKE_CAP_PX}px, or text would be UPSCALED and blur " +
                "would return. Re-bake the font at a higher cap before raising UiScale.factor.",
            largestOnScreenPx <= GameFont.BAKE_CAP_PX,
        )
    }

    @Test
    fun `the effective downscale factor stays at or below 1 (never a magnification)`() {
        val largestScale = GameFont.NORM * UiScale.factor * MAX_REGIME_SCALE
        assertTrue(
            "AC#2: the largest BitmapFont data scale ($largestScale) must be <= 1f so the master glyphs " +
                "are downscaled, not stretched.",
            largestScale <= 1f,
        )
    }

    private companion object {
        /**
         * The largest per-consumer regime multiplier applied on top of `GameFont.NORM × UiScale.factor`.
         * The HUD (and the action-arc/label styles via the skin) use no extra factor → 1.0; the minimap
         * (0.6) and overlay (0.8) label fonts multiply by a value <1, so they are strictly smaller and the
         * HUD bounds the worst case. Pinned here as the upper bound the invariant must hold against.
         */
        private const val MAX_REGIME_SCALE = 1.0f
    }
}
