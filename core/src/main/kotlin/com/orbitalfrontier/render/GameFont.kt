package com.orbitalfrontier.render

/**
 * The contract for the game's bundled bitmap typeface (`fonts/orbital.fnt` + its page PNG), UC28.
 *
 * Engine-free on purpose: plain constants with **no** libGDX dependency (mirrors [AtlasRegions]), so the
 * font's path, bake size and required glyph coverage can be referenced from pure `core` and asserted by
 * JVM tests without a GL context — the headless coverage guard parses the `.fnt` text and checks each
 * [REQUIRED_GLYPHS] codepoint resolves, exactly as the atlas guard parses the `.atlas` (ADR 0017).
 *
 * The asset is a pre-baked bitmap font: an OFL face (JetBrains Mono) rasterized **white on transparent**
 * at [BAKE_CAP_PX] so `font.color` tints survive (UC27 AC#8), baked once out-of-tree (gdx-freetype is a
 * bake-time tool only, never a `:core`/`:android` dependency — see ADR 0017). At runtime
 * [GameFontLoader] loads it with Linear filtering and each consumer downscales it by [NORM] (× its own
 * regime factor) so the master glyphs are minified, not magnified — crisp text instead of the old
 * built-in font's bilinear stretch (UC28 AC#2).
 */
object GameFont {
    /** Internal (packaged-asset) path to the `.fnt`; its page PNG sits beside it under `assets/fonts/`. */
    const val FONT_PATH = "fonts/orbital.fnt"

    /** The pixel size the master glyphs were baked at (the FreeType cap). The single source of [NORM]. */
    const val BAKE_CAP_PX = 48f

    /** The visual pixel size the legacy built-in [com.badlogic.gdx.graphics.g2d.BitmapFont] rendered at. */
    const val LEGACY_BASE_PX = 15f

    /**
     * Downscale that brings the [BAKE_CAP_PX] master back to the legacy on-screen size (`= 0.3125`), so
     * the switch is visually size-neutral. Consumers apply `NORM × <regime factor>` at their use site —
     * never baking the factor into a constant (the UiScale single-knob discipline, ADR 0015).
     */
    const val NORM = LEGACY_BASE_PX / BAKE_CAP_PX

    /**
     * Every codepoint the UI draws today: printable ASCII (`U+0020`..`U+007E`) plus the degree sign
     * (`°`, UC07 heading readout) and the rightwards arrow (`→`, mission-board rows). The bundled `.fnt`
     * must declare a `char` for each; the headless guard fails the build if any is missing (UC28 AC#3).
     */
    val REQUIRED_GLYPHS: List<Int> =
        buildList {
            for (codepoint in 0x20..0x7E) add(codepoint)
            add(0x00B0) // ° degree sign
            add(0x2192) // → rightwards arrow
        }
}
