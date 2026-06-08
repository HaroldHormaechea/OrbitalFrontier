package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.world.Contact
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Transponder

/**
 * The one reusable in-world renderer for **every** POI (ADR 0015): it iterates the whole
 * `sector.pois` list and draws each POI's base [WorldGlyph] (resolved by [WorldGlyphs.forPoi]) in world
 * space, using the follow camera's projection (mirrors [ShipRenderer]/[GateRenderer]).
 *
 * Because resolution is the compiler-exhaustive [WorldGlyphs.forPoi] and drawing is this single loop,
 * "a POI with no in-world graphic" is impossible by construction — the bug this fixes (stations had a
 * minimap marker but no world renderer, so drew as nothing) cannot recur. Stations get their box glyph
 * through this path; the bespoke [GateRenderer]/[AsteroidFieldRenderer] now draw only their rings.
 *
 * **Visibility** mirrors the minimap (coding-guidelines § O, the [Contact] seam): a POI is skipped only
 * when it is a non-broadcasting [Contact] (a [com.orbitalfrontier.world.HiddenContact]) whose id is not
 * yet revealed. Gates/stations ([Transponder]) and asteroid fields (plain [Poi]) always draw; a revealed
 * hidden contact draws its placeholder box (per product decision — every object has a graphic).
 *
 * Render reads state only — no simulation here (coding-guidelines § simulation vs render). All current
 * glyph shapes are filled primitives, so a single [ShapeRenderer.ShapeType.Filled] batch covers them;
 * colours are set per glyph via the float overload (no per-frame `Color` allocation).
 */
class WorldObjectRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        pois: List<Poi>,
        revealedContacts: Set<PoiId>,
    ) {
        if (pois.isEmpty()) return
        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (poi in pois) {
            if (!isVisible(poi, revealedContacts)) continue
            drawGlyph(poi.position.x, poi.position.y, WorldGlyphs.forPoi(poi))
        }
        shapeRenderer.end()
    }

    /**
     * A POI draws unless it is a hidden (non-[Transponder]) [Contact] not yet in [revealedContacts].
     * Broadcasting contacts (gates/stations) and plain POIs (asteroid fields) always draw.
     */
    private fun isVisible(
        poi: Poi,
        revealedContacts: Set<PoiId>,
    ): Boolean = !(poi is Contact && poi !is Transponder && poi.id !in revealedContacts)

    private fun drawGlyph(
        x: Float,
        y: Float,
        glyph: WorldGlyph,
    ) {
        shapeRenderer.setColor(glyph.red, glyph.green, glyph.blue, glyph.alpha)
        val s = glyph.sizeWorldUnits
        when (glyph.shape) {
            GlyphShape.DIAMOND -> {
                // Two triangles meeting at the centre — reproduces GateRenderer's old gate marker.
                shapeRenderer.triangle(x, y + s, x - s, y, x + s, y)
                shapeRenderer.triangle(x, y - s, x - s, y, x + s, y)
            }
            GlyphShape.BOX -> {
                // Filled square centred on the position (station / revealed hidden-contact placeholder).
                shapeRenderer.rect(x - s, y - s, s * 2f, s * 2f)
            }
            GlyphShape.ROCK_CLUSTER -> {
                // Three filled rocks — reproduces AsteroidFieldRenderer's old cluster around the centre.
                shapeRenderer.circle(x, y, s, ROCK_SEGMENTS)
                shapeRenderer.circle(x - ROCK_OFFSET, y + ROCK_OFFSET, s * 0.6f, ROCK_SEGMENTS)
                shapeRenderer.circle(x + ROCK_OFFSET, y - ROCK_OFFSET * 0.5f, s * 0.7f, ROCK_SEGMENTS)
            }
        }
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val ROCK_SEGMENTS = 16
        const val ROCK_OFFSET = 34f
    }
}
