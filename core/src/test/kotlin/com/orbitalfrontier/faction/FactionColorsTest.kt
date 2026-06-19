package com.orbitalfrontier.faction

import com.orbitalfrontier.settings.ColorVisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [FactionColors] (UC39 AC#1; the UC14 "keep faction colours distinguishable under the
 * colourblind palette" pitfall) — the pure, colour-vision-aware resolver for a [Faction]'s display tint.
 *
 * Under [ColorVisionMode.STANDARD] the authored [Faction.color] is returned unchanged (zero regression);
 * under [ColorVisionMode.COLORBLIND_SAFE] each catalogued faction returns its Okabe-Ito override, chosen so
 * the factions stay mutually distinguishable for red-green-deficient players. Packed-Int colours only — no
 * libGDX types — so the whole faction package stays JVM-testable.
 */
class FactionColorsTest {
    @Test
    fun `standard mode returns the authored faction tint unchanged`() {
        assertEquals(
            "League keeps its authored standard tint in standard mode",
            Factions.LEAGUE.color,
            FactionColors.resolve(Factions.LEAGUE, ColorVisionMode.STANDARD),
        )
        assertEquals(
            "Independents keep their authored standard tint in standard mode",
            Factions.INDEPENDENTS.color,
            FactionColors.resolve(Factions.INDEPENDENTS, ColorVisionMode.STANDARD),
        )
    }

    @Test
    fun `colourblind mode remaps each catalogued faction to its Okabe-Ito override`() {
        assertEquals(
            "League → Okabe-Ito blue under colourblind-safe",
            FactionColors.LEAGUE_COLORBLIND,
            FactionColors.resolve(Factions.LEAGUE, ColorVisionMode.COLORBLIND_SAFE),
        )
        assertEquals(
            "Independents → Okabe-Ito orange under colourblind-safe",
            FactionColors.INDEPENDENTS_COLORBLIND,
            FactionColors.resolve(Factions.INDEPENDENTS, ColorVisionMode.COLORBLIND_SAFE),
        )
    }

    @Test
    fun `the colourblind override is the published Okabe-Ito hex`() {
        // Pin the constants independently of FactionColors' own arithmetic (packed RGBA8888, alpha = FF).
        assertEquals("League colourblind tint is Okabe-Ito blue #0072B2", 0x0072B2FF.toInt(), FactionColors.LEAGUE_COLORBLIND)
        assertEquals(
            "Independents colourblind tint is Okabe-Ito orange #E69F00",
            0xE69F00FF.toInt(),
            FactionColors.INDEPENDENTS_COLORBLIND,
        )
    }

    @Test
    fun `the two factions stay mutually distinguishable in BOTH modes`() {
        // The load-bearing AC#1 property: whichever palette is active, League and Independents never collapse
        // to the same tint (the UC14 pitfall the colourblind remap must not introduce).
        for (mode in ColorVisionMode.entries) {
            assertNotEquals(
                "League and Independents must differ in $mode",
                FactionColors.resolve(Factions.LEAGUE, mode),
                FactionColors.resolve(Factions.INDEPENDENTS, mode),
            )
        }
    }

    @Test
    fun `colourblind remap actually changes both catalogued faction tints from their standard hue`() {
        // Each override is genuinely different from the authored tint — the remap is not a no-op.
        assertNotEquals(
            "League's colourblind tint differs from its standard tint",
            FactionColors.resolve(Factions.LEAGUE, ColorVisionMode.STANDARD),
            FactionColors.resolve(Factions.LEAGUE, ColorVisionMode.COLORBLIND_SAFE),
        )
        assertNotEquals(
            "Independents' colourblind tint differs from its standard tint",
            FactionColors.resolve(Factions.INDEPENDENTS, ColorVisionMode.STANDARD),
            FactionColors.resolve(Factions.INDEPENDENTS, ColorVisionMode.COLORBLIND_SAFE),
        )
    }

    @Test
    fun `an un-catalogued un-tinted faction resolves to null in both modes`() {
        // A faction with no authored colour and no override returns null (no tint), never a crash — in either
        // mode (the colourblind branch falls through to the authored null when there is no override).
        val untinted = Faction(id = FactionId("nomads"), displayName = "Nomads")
        assertNull(FactionColors.resolve(untinted, ColorVisionMode.STANDARD))
        assertNull(FactionColors.resolve(untinted, ColorVisionMode.COLORBLIND_SAFE))
    }
}
