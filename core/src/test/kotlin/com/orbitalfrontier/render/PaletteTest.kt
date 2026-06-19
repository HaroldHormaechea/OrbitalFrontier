package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
import com.orbitalfrontier.settings.ColorVisionMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Tests for the design-system [Palette] (UC27 AC#8) — the single source of truth for non-sprite colours.
 *
 * [com.badlogic.gdx.graphics.Color] is a plain value type (four floats); constructing it and calling
 * `Color.valueOf` parse hex with **no GL context**, so this runs safely on the JVM test thread (unlike a
 * `Texture`/`TextureAtlas`). Each token's RGBA channels are checked against the expected `hex / 255` value
 * computed independently of `Color.valueOf`, so the test pins the actual colour rather than re-deriving it.
 * The semantic aliases (the ones screens/renderers reach for) are asserted to point at the right base token.
 *
 * **Colour-vision mode (UC39 AC#1).** [Palette.mode] is now MUTABLE GLOBAL STATE (the standard ↔
 * colourblind-safe knob), so every test restores the default via [Palette.reset] in [tearDown] (and the
 * STANDARD-mode hex assertions above implicitly assume that baseline). The mode-aware suite below pins:
 * the state tokens flip to their Okabe-Ito variants under colourblind-safe; the neutrals/brand accents stay
 * constant; each mode returns a single stable cached instance (no per-frame alloc); and hostile/friendly
 * markers stay distinguishable in both modes.
 */
class PaletteTest {
    @Before
    fun setUp() {
        Palette.reset()
    }

    @After
    fun tearDown() {
        // MANDATORY: restore the global so other suites (and the STANDARD-hex assertions here) see the
        // default standard palette they assume.
        Palette.reset()
    }

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

    // --- UC39 AC#1: colour-vision-mode behaviour ----------------------------------------------------

    @Test
    fun `standard mode keeps the exact pre-UC39 state-token hex (zero regression)`() {
        Palette.setMode(ColorVisionMode.STANDARD)
        // The state tokens reproduce the UC27 hex byte-for-byte in standard mode.
        assertHex("f4c40e", Palette.HAZARD_500)
        assertHex("4fb477", Palette.SUCCESS)
        assertHex("ffb24d", Palette.WARNING)
        assertHex("e0563f", Palette.DANGER)
        // The map markers keep their migrated class-load literals (the pre-UC39 marker hues).
        assertChannels(0.5f, 1f, 0.6f, Palette.STATION_FRIENDLY)
        assertChannels(1f, 0.4f, 0.4f, Palette.CONTACT_HOSTILE)
    }

    @Test
    fun `colourblind-safe mode remaps the state tokens to the Okabe-Ito variants`() {
        Palette.setMode(ColorVisionMode.COLORBLIND_SAFE)
        // Each state token returns the cached Okabe-Ito instance (reference identity, not just equal hex).
        assertSame("HAZARD → Okabe-Ito yellow", Palette.OKABE_YELLOW, Palette.HAZARD_500)
        assertSame("SUCCESS → Okabe-Ito bluish-green", Palette.OKABE_BLUISH_GREEN, Palette.SUCCESS)
        assertSame("WARNING → Okabe-Ito orange", Palette.OKABE_ORANGE, Palette.WARNING)
        assertSame("DANGER → Okabe-Ito vermillion", Palette.OKABE_VERMILLION, Palette.DANGER)
        assertSame("friendly marker → Okabe-Ito bluish-green", Palette.OKABE_BLUISH_GREEN, Palette.STATION_FRIENDLY)
        assertSame("hostile marker → Okabe-Ito vermillion", Palette.OKABE_VERMILLION, Palette.CONTACT_HOSTILE)
    }

    @Test
    fun `the structural neutrals and brand accents are colour-vision-independent`() {
        // Neutrals + accents do not convey state, so they must be identical instances across BOTH modes.
        Palette.setMode(ColorVisionMode.STANDARD)
        val standard = listOf(Palette.VOID_900, Palette.STEEL_400, Palette.AMBER_500, Palette.CYAN_500, Palette.STEEL_050)
        Palette.setMode(ColorVisionMode.COLORBLIND_SAFE)
        val colourblind = listOf(Palette.VOID_900, Palette.STEEL_400, Palette.AMBER_500, Palette.CYAN_500, Palette.STEEL_050)
        for (i in standard.indices) {
            assertSame("neutral/accent #$i is unchanged by the colourblind palette", standard[i], colourblind[i])
        }
    }

    @Test
    fun `each mode returns a single stable cached instance (no per-frame allocation)`() {
        // Reference identity must be stable across repeated reads within a mode, so reference-identity
        // assertions elsewhere stay valid and there is no per-frame Color allocation.
        Palette.setMode(ColorVisionMode.STANDARD)
        assertSame("standard DANGER is a stable instance", Palette.DANGER, Palette.DANGER)
        assertSame("standard CONTACT_HOSTILE is a stable instance", Palette.CONTACT_HOSTILE, Palette.CONTACT_HOSTILE)
        Palette.setMode(ColorVisionMode.COLORBLIND_SAFE)
        assertSame("colourblind DANGER is a stable instance", Palette.DANGER, Palette.DANGER)
        assertSame("colourblind STATION_FRIENDLY is a stable instance", Palette.STATION_FRIENDLY, Palette.STATION_FRIENDLY)
    }

    @Test
    fun `hostile and friendly markers stay distinguishable in both modes`() {
        // The load-bearing "hostile vs friendly" AC#1 property — whichever palette is active, the two
        // markers never collapse to the same colour.
        for (mode in ColorVisionMode.entries) {
            Palette.setMode(mode)
            assertNotEquals(
                "hostile and friendly markers must differ in $mode",
                colorKey(Palette.CONTACT_HOSTILE),
                colorKey(Palette.STATION_FRIENDLY),
            )
        }
    }

    @Test
    fun `setMode switches the active mode and reset returns to the default`() {
        assertEquals("the default mode is standard", ColorVisionMode.STANDARD, Palette.mode)
        Palette.setMode(ColorVisionMode.COLORBLIND_SAFE)
        assertEquals("setMode switches the active mode", ColorVisionMode.COLORBLIND_SAFE, Palette.mode)
        Palette.reset()
        assertEquals("reset returns to the default", ColorVisionMode.DEFAULT, Palette.mode)
    }

    /** Asserts the RGB channels of [color] match the given fractions (alpha assumed 1), within epsilon. */
    private fun assertChannels(
        r: Float,
        g: Float,
        b: Float,
        color: Color,
    ) {
        assertEquals("red", r, color.r, EPS)
        assertEquals("green", g, color.g, EPS)
        assertEquals("blue", b, color.b, EPS)
    }

    /** A comparable RGBA key for a [Color], so two tints can be compared for (in)equality by value. */
    private fun colorKey(color: Color): Int = color.toIntBits()

    private companion object {
        const val EPS = 1e-3f
    }
}
