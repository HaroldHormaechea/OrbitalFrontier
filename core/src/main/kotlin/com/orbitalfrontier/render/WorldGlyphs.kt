package com.orbitalfrontier.render

import com.orbitalfrontier.world.AsteroidField
import com.orbitalfrontier.world.HiddenContact
import com.orbitalfrontier.world.JumpGate
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.Station

/**
 * The pure resolver from a [Poi] to its in-world [WorldGlyph] (ADR 0015) — the structural guarantee
 * that no POI can render as nothing.
 *
 * [forPoi] is a **compiler-exhaustive `when`** over the sealed [Poi] hierarchy: adding a new POI subtype
 * will not compile until it is given a glyph here (coding-guidelines § O, Open/Closed). That is what
 * makes "an object with no in-world renderer" impossible by construction — the bug this fixes (stations
 * had no world renderer and so drew as nothing) cannot recur silently.
 *
 * **Hot-path discipline (60 FPS, ADR 0006 perf budget):** [forPoi] is called per-POI per-frame, so the
 * glyphs for the fixed POI kinds are **cached constants** — no per-frame allocation. The single exception
 * is [Station], whose glyph carries its varying [Station.displayName] as a label and is therefore built
 * per call; sectors hold only a handful of stations, so this is negligible.
 *
 * Engine-free: returns the pure [WorldGlyph] descriptor; [WorldObjectRenderer] turns it into draw calls.
 */
object WorldGlyphs {
    /**
     * Resolve [poi]'s in-world glyph. Exhaustive over the sealed [Poi] hierarchy — a new subtype forces
     * a compile error here until it is given a glyph.
     */
    fun forPoi(poi: Poi): WorldGlyph =
        when (poi) {
            is JumpGate -> GATE_GLYPH
            is Station -> stationGlyph(poi)
            is AsteroidField -> ASTEROID_GLYPH
            is HiddenContact -> HIDDEN_CONTACT_GLYPH
        }

    /**
     * A station's glyph: the shared [STATION_BOX] look, stamped with this station's display name as its
     * [WorldGlyph.label]. The only per-call allocation in [forPoi] (label varies per station).
     */
    private fun stationGlyph(station: Station): WorldGlyph = STATION_BOX.copy(label = station.displayName)

    // Cached, constant glyphs per fixed POI kind — reused every frame (no allocation in the hot path).
    // Colours/sizes reproduce the markers the per-type renderers used to draw, so the authored look is
    // preserved as those renderers slim to ring-only overlays (GateRenderer/AsteroidFieldRenderer).

    /** Jump gate: the cyan filled diamond GateRenderer used to draw (MARKER_SIZE = 28). */
    private val GATE_GLYPH =
        WorldGlyph(GlyphShape.DIAMOND, red = 0.4f, green = 0.85f, blue = 1f, alpha = 1f, sizeWorldUnits = 28f)

    /** Asteroid field: the tan rock cluster AsteroidFieldRenderer used to draw (base ROCK_SIZE = 26). */
    private val ASTEROID_GLYPH =
        WorldGlyph(GlyphShape.ROCK_CLUSTER, red = 0.55f, green = 0.5f, blue = 0.42f, alpha = 1f, sizeWorldUnits = 26f)

    /**
     * Station: a green filled box (the colour the minimap already uses for a STATION contact). New
     * in-world marker — stations previously had no world renderer, which is the bug this fixes. The
     * shared template; [stationGlyph] stamps the per-station label on a copy.
     */
    private val STATION_BOX =
        WorldGlyph(GlyphShape.BOX, red = 0.5f, green = 1f, blue = 0.6f, alpha = 1f, sizeWorldUnits = 22f)

    /**
     * Hidden contact (once revealed): a red placeholder box (the minimap's revealed-contact colour). Per
     * product decision, a revealed hidden contact DOES get an in-world box — only *unrevealed* ones are
     * skipped (by [WorldObjectRenderer]'s visibility predicate), not skipped here.
     */
    private val HIDDEN_CONTACT_GLYPH =
        WorldGlyph(GlyphShape.BOX, red = 1f, green = 0.4f, blue = 0.4f, alpha = 1f, sizeWorldUnits = 16f)
}
