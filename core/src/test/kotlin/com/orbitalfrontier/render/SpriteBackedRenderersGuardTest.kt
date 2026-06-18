package com.orbitalfrontier.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static source-scan guard for UC27 AC#10's "renderers/skin reference atlas regions rather than generated
 * pixmaps" requirement, and for the [WorldGlyph] API change (generated `GlyphShape` + RGBA → atlas region).
 *
 * Like [com.orbitalfrontier.playthrough.NoBox2DGuardTest], this asserts on the **source text** rather than
 * instantiating GL-bound types (a renderer needs a GL context to construct). It checks that:
 *  - [WorldGlyph] is sprite-backed: it declares a `regionName` and no longer carries a generated
 *    `GlyphShape` or RGBA colour fields;
 *  - every in-world / HUD renderer resolves its art via `assets.region(...)` and does **not** generate
 *    per-sprite [com.badlogic.gdx.graphics.Pixmap]s;
 *  - [WorldGlyphs] maps each POI to an [AtlasRegions] constant;
 *  - the control skin draws the joystick and the six action-arc glyphs from atlas regions on its art path.
 *
 * It is intentionally textual and conservative: it proves the sprite path is wired, complementing the
 * behavioural [WorldGlyphsTest] / [AtlasRegionsTest] / [AtlasRegionGuardTest]. The control skin keeps a
 * documented `gameAssets == null` *fallback* that still uses Pixmaps for JVM/no-art contexts plus a
 * non-sprite settings-button rect, so the skin is exempted from the "no Pixmap" check by design.
 */
class SpriteBackedRenderersGuardTest {
    /** Renderers that must be fully sprite-backed (no generated pixmaps, art via `assets.region`). */
    private val spriteRenderers =
        listOf(
            "ShipRenderer.kt",
            "HostileRenderer.kt",
            "MinimapRenderer.kt",
            "ShipSchematicRenderer.kt",
            "WalkaroundRenderer.kt",
            "WorldObjectRenderer.kt",
        )

    @Test
    fun `WorldGlyph is sprite-backed by a region name`() {
        val src = renderSource("WorldGlyph.kt")
        assertTrue(
            "WorldGlyph must declare a regionName property (the atlas-region reference)",
            Regex("\\bval\\s+regionName\\b").containsMatchIn(src),
        )
    }

    @Test
    fun `WorldGlyph no longer carries a generated GlyphShape or RGBA colour fields`() {
        val src = renderSource("WorldGlyph.kt")
        // Strip block + line comments so the KDoc (which legitimately mentions the old shape/colour design)
        // does not trip the guard — only live declarations count.
        val code = stripComments(src)
        assertFalse(
            "WorldGlyph must not reference the removed GlyphShape type in code",
            code.contains("GlyphShape"),
        )
        for (field in listOf("val red", "val green", "val blue", "val alpha", "val shape")) {
            assertFalse(
                "WorldGlyph must not declare the removed generated-colour/shape field `$field`",
                Regex("\\b${Regex.escape(field)}\\b").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `sprite renderers resolve art via the shared atlas and generate no pixmaps`() {
        for (file in spriteRenderers) {
            val src = renderSource(file)
            assertTrue(
                "$file must resolve its art via assets.region(...) (sprite-backed, AC#10)",
                src.contains("assets.region("),
            )
            assertFalse(
                "$file must not construct generated Pixmaps — art comes from the atlas (AC#10)",
                stripComments(src).contains("Pixmap("),
            )
        }
    }

    @Test
    fun `WorldGlyphs maps every POI kind to an AtlasRegions constant`() {
        val src = stripComments(renderSource("WorldGlyphs.kt"))
        for (region in listOf("JUMP_GATE", "STATION", "ASTEROID_FIELD", "CONTACT_HIDDEN")) {
            assertTrue(
                "WorldGlyphs must build its glyph from AtlasRegions.$region",
                src.contains("AtlasRegions.$region"),
            )
        }
    }

    @Test
    fun `control skin draws the joystick and action glyphs from atlas regions`() {
        val src = controlsSkinSource("OrbitalUiSkin.kt")
        val code = stripComments(src)
        assertTrue(
            "skin must draw art via gameAssets.region(...) on its atlas path (AC#2/#3)",
            code.contains("gameAssets.region("),
        )
        // Joystick base + knob sprites (AC#3).
        for (region in listOf("JOYSTICK_BASE", "JOYSTICK_KNOB")) {
            assertTrue("skin must reference AtlasRegions.$region (AC#3)", code.contains("AtlasRegions.$region"))
        }
        // All six action-arc glyph sprites (AC#2) — FIRE included.
        for (region in listOf(
            "ACTION_FIRE",
            "ACTION_DOCK",
            "ACTION_MINE",
            "ACTION_SCAN",
            "ACTION_RADIO",
            "ACTION_POINT_AND_GO",
        )) {
            assertTrue("skin must map a glyph to AtlasRegions.$region (AC#2)", code.contains("AtlasRegions.$region"))
        }
    }

    // --- source location helpers (mirror NoBox2DGuardTest's tolerant candidate-root approach) ---

    private fun renderSource(fileName: String): String = locateMainSource("render/$fileName").readText()

    private fun controlsSkinSource(fileName: String): String = locateMainSource("screen/controls/$fileName").readText()

    private fun locateMainSource(relativeToPackageRoot: String): File {
        val relative = "src/main/kotlin/com/orbitalfrontier/$relativeToPackageRoot"
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("could not locate source; tried: ${candidates.map { it.absolutePath }}")
    }

    /** Remove `/* */` block comments and `//` line comments so KDoc prose can't trip a code-level guard. */
    private fun stripComments(source: String): String {
        val noBlock = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        return noBlock.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }
}
