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
 * makes "an object with no in-world renderer" impossible by construction — the bug this guards against
 * (stations had no world renderer and so drew as nothing) cannot recur silently.
 *
 * Each glyph names an atlas region ([AtlasRegions]) so the delivered design-system art backs every POI.
 * World-unit half-extents come from [WorldSpriteSizes] (the per-type sizing single source of truth, ADR
 * 0019) — gate / station / asteroid-field / hidden-contact — keeping positions/collision/camera identical
 * regardless of the drawn sprite.
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
     * A station's glyph: the shared [STATION_GLYPH] sprite, stamped with this station's display name as its
     * [WorldGlyph.label] (the ADR-0015 station-label contract). The only per-call allocation in [forPoi].
     */
    private fun stationGlyph(station: Station): WorldGlyph = STATION_GLYPH.copy(label = station.displayName)

    // Cached, constant glyphs per fixed POI kind — reused every frame (no allocation in the hot path).
    // Sizes (half-extents in world units) reproduce the markers the per-type renderers used to draw, so
    // gameplay geometry is preserved as those renderers slim to ring-only overlays (Gate/AsteroidField).

    /** Jump gate: the design-system gate sprite, at its per-type world half-extent ([WorldSpriteSizes.GATE]). */
    private val GATE_GLYPH = WorldGlyph(AtlasRegions.JUMP_GATE, sizeWorldUnits = WorldSpriteSizes.GATE)

    /** Asteroid field: the design-system asteroid-field sprite ([WorldSpriteSizes.ASTEROID_FIELD]). */
    private val ASTEROID_GLYPH = WorldGlyph(AtlasRegions.ASTEROID_FIELD, sizeWorldUnits = WorldSpriteSizes.ASTEROID_FIELD)

    /** Station: the design-system station sprite ([WorldSpriteSizes.STATION]). Shared template; [stationGlyph] adds the label. */
    private val STATION_GLYPH = WorldGlyph(AtlasRegions.STATION, sizeWorldUnits = WorldSpriteSizes.STATION)

    /**
     * Hidden contact (once revealed): the design-system revealed-contact sprite
     * ([WorldSpriteSizes.HIDDEN_CONTACT]). Per product decision, a revealed hidden contact DOES draw
     * in-world — only *unrevealed* ones are skipped (by [WorldObjectRenderer]'s visibility predicate),
     * not skipped here.
     */
    private val HIDDEN_CONTACT_GLYPH = WorldGlyph(AtlasRegions.CONTACT_HIDDEN, sizeWorldUnits = WorldSpriteSizes.HIDDEN_CONTACT)
}
