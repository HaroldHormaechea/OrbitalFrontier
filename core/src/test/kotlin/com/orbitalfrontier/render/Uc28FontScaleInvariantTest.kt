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
 * The largest scale any consumer applies is now the **skin font**, which UC39 multiplies by the global
 * [TextScale.factor] (up to [TextScale.MAX_FACTOR]) on top of `GameFont.NORM × UiScale.factor`. The HUD /
 * world-space text deliberately follows [UiScale] only (no text-scale — a documented UC39 exclusion) and
 * the minimap / overlay labels multiply by an extra <1 LABEL_FONT_SCALE, so they are strictly smaller; the
 * text-scaled skin font therefore bounds the worst case. That regime — `TextScale.MAX_FACTOR` on top of
 * `NORM × UiScale.factor` — is pinned as [MAX_REGIME_SCALE] below. (The *absolute* worst corner, at
 * `UiScale.MAX × TextScale.MAX`, is allowed a mild graceful upscale and is guarded separately in
 * [TextScaleTest]; this invariant holds at the runtime-default [UiScale.factor].)
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
         * UC39 made the skin font text-scalable, so the worst case is now [TextScale.MAX_FACTOR] (the HUD
         * and minimap/overlay label fonts are all <= this — the HUD uses ×1 and the labels multiply by a
         * value <1). Folding it in keeps the minify-don't-magnify invariant honest now that text size is a
         * player knob: at the runtime-default UiScale (×2) the largest skin font is `NORM × 2 × 1.4 = 0.875`,
         * still a downscale of the baked master. Pinned here as the upper bound the invariant holds against.
         */
        private const val MAX_REGIME_SCALE = TextScale.MAX_FACTOR
    }
}
