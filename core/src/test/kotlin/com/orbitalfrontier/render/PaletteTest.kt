package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for the design-system [Palette] (UC27 AC#8) — the single source of truth for non-sprite colours.
 *
 * [com.badlogic.gdx.graphics.Color] is a plain value type (four floats); constructing it and calling
 * `Color.valueOf` parse hex with **no GL context**, so this runs safely on the JVM test thread (unlike a
 * `Texture`/`TextureAtlas`). Each token's RGBA channels are checked against the expected `hex / 255` value
 * computed independently of `Color.valueOf`, so the test pins the actual colour rather than re-deriving it.
 * The semantic aliases (the ones screens/renderers reach for) are asserted to point at the right base token.
 */
class PaletteTest {
    /** Asserts a parsed [Color] equals the RGBA implied by [hex] (RRGGBB, alpha = 1), within float epsilon. */
    private fun assertHex(
        hex: String,
        color: Color,
    ) {
        val r = hex.substring(0, 2).toInt(16) / 255f
        val g = hex.substring(2, 4).toInt(16) / 255f
        val b = hex.substring(4, 6).toInt(16) / 255f
        assertEquals("red of #$hex", r, color.r, EPS)
        assertEquals("green of #$hex", g, color.g, EPS)
        assertEquals("blue of #$hex", b, color.b, EPS)
        assertEquals("alpha of #$hex", 1f, color.a, EPS)
    }

    @Test
    fun voidAndSteelSurfacesMatchTokens() {
        assertHex("06080b", Palette.VOID_900)
        assertHex("0b0f14", Palette.VOID_800)
        assertHex("11161d", Palette.VOID_700)
        assertHex("19212a", Palette.STEEL_600)
        assertHex("232e39", Palette.STEEL_500)
        assertHex("34424f", Palette.STEEL_400)
        assertHex("d8e0e7", Palette.STEEL_050)
        assertHex("aab8c4", Palette.STEEL_100)
    }

    @Test
    fun amberSignalMatchesTokens() {
        assertHex("e07f12", Palette.AMBER_600)
        assertHex("ff9e2c", Palette.AMBER_500)
        assertHex("ffb24d", Palette.AMBER_400)
        assertHex("ffc878", Palette.AMBER_300)
    }

    @Test
    fun cyanSignalMatchesTokens() {
        assertHex("119aa6", Palette.CYAN_600)
        assertHex("1fcad6", Palette.CYAN_500)
        assertHex("4fe0ea", Palette.CYAN_400)
    }

    @Test
    fun statusColoursMatchTokens() {
        assertHex("f4c40e", Palette.HAZARD_500)
        assertHex("4fb477", Palette.SUCCESS)
        assertHex("ffb24d", Palette.WARNING)
        assertHex("e0563f", Palette.DANGER)
    }

    @Test
    fun semanticAliasesPointAtTheRightBaseToken() {
        // The aliases are what screens/renderers reach for — pin each to its intended base colour.
        assertSame("SURFACE_APP is the deepest void", Palette.VOID_900, Palette.SURFACE_APP)
        assertSame("SURFACE_BASE is the primary surface", Palette.VOID_800, Palette.SURFACE_BASE)
        assertSame("ACCENT is amber primary", Palette.AMBER_500, Palette.ACCENT)
        assertSame("ACCENT_SECONDARY is cyan secondary", Palette.CYAN_500, Palette.ACCENT_SECONDARY)
        assertSame("TEXT_STRONG is high-emphasis steel", Palette.STEEL_050, Palette.TEXT_STRONG)
        assertSame("TEXT_BODY is body steel", Palette.STEEL_100, Palette.TEXT_BODY)
        assertSame("BORDER is the hairline steel", Palette.STEEL_400, Palette.BORDER)
    }

    @Test
    fun primaryAccentIsAmberNotCyan() {
        // Guards AC#8's explicit "primary accent is amber, secondary is cyan" — a swap would be a bug.
        assertHex("ff9e2c", Palette.ACCENT)
        assertHex("1fcad6", Palette.ACCENT_SECONDARY)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
