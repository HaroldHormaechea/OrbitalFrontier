package com.orbitalfrontier.faction

import com.orbitalfrontier.settings.ColorVisionMode

/**
 * Pure, colour-vision-aware resolver for a [Faction]'s display tint (UC39 AC#1, the "coordinate so faction
 * colours remain distinguishable under the colourblind palette" pitfall from UC14).
 *
 * [Faction.color] carries the authored standard tint (packed RGBA8888). Under
 * [ColorVisionMode.COLORBLIND_SAFE] this returns an **Okabe-Ito** variant per faction instead, chosen so
 * the catalogued factions stay **mutually distinguishable** for red-green-deficient players:
 *  - [Factions.LEAGUE] → Okabe-Ito blue `#0072B2`
 *  - [Factions.INDEPENDENTS] → Okabe-Ito orange `#E69F00`
 *
 * Under [ColorVisionMode.STANDARD] (or for a faction with no override) it returns the authored
 * [Faction.color] unchanged (which may be `null` — an un-tinted faction).
 *
 * **Not-yet-rendered.** As of UC39 [Faction.color] still has **no render site** (it was authored `[TUNE]`
 * for a future faction-coloured UI; UC14). This resolver is the colourblind-correct hook that the eventual
 * faction-colour render site MUST call — it is deliberately added now (and unit-tested) so the colourblind
 * mapping exists and is verified, but it is NOT wired into any draw yet. Engine-free and pure (packed Int
 * colours, no libGDX types) so the whole faction package stays JVM-testable (UC14 AC#5 / ADR 0001).
 */
object FactionColors {
    /** Okabe-Ito blue (`#0072B2`), packed RGBA8888 — the colourblind-safe tint for [Factions.LEAGUE]. */
    const val LEAGUE_COLORBLIND: Int = 0x0072B2FF.toInt()

    /** Okabe-Ito orange (`#E69F00`), packed RGBA8888 — the colourblind-safe tint for [Factions.INDEPENDENTS]. */
    const val INDEPENDENTS_COLORBLIND: Int = 0xE69F00FF.toInt()

    private val COLORBLIND_OVERRIDES: Map<FactionId, Int> =
        mapOf(
            Factions.LEAGUE.id to LEAGUE_COLORBLIND,
            Factions.INDEPENDENTS.id to INDEPENDENTS_COLORBLIND,
        )

    /**
     * The display tint for [faction] under [mode] (packed RGBA8888), or `null` when the faction has no
     * tint and no colourblind override. In [ColorVisionMode.COLORBLIND_SAFE] a catalogued faction returns
     * its Okabe-Ito override; otherwise the authored [Faction.color] is returned unchanged.
     */
    fun resolve(
        faction: Faction,
        mode: ColorVisionMode,
    ): Int? =
        if (mode == ColorVisionMode.COLORBLIND_SAFE) {
            COLORBLIND_OVERRIDES[faction.id] ?: faction.color
        } else {
            faction.color
        }
}
