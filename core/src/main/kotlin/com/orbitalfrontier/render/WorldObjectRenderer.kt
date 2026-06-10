package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.world.Contact
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Transponder

/**
 * The one reusable in-world renderer for **every** POI (ADR 0015): it iterates the whole `sector.pois`
 * list and draws each POI's base [WorldGlyph] (resolved by [WorldGlyphs.forPoi]) in world space, using the
 * follow camera's projection (mirrors [ShipRenderer]).
 *
 * Because resolution is the compiler-exhaustive [WorldGlyphs.forPoi] and drawing is this single loop,
 * "a POI with no in-world graphic" is impossible by construction — the bug this fixes (stations had a
 * minimap marker but no world renderer, so drew as nothing) cannot recur. Stations get their sprite glyph
 * through this path; the bespoke [GateRenderer]/[AsteroidFieldRenderer] still draw only their gameplay rings.
 *
 * UC27: the glyph is now a design-system atlas sprite (AC#4), drawn with a [SpriteBatch] centred on the
 * POI at its world-unit size (no rotation — these objects are non-directional). The shared [GameAssets]
 * atlas is **borrowed** (never disposed here); this renderer owns only its own batch. Region lookups are
 * memoised in [GameAssets], so the per-POI hot path stays allocation-free (60 FPS, ADR 0006).
 *
 * **Visibility** mirrors the minimap (coding-guidelines § O, the [Contact] seam): a POI is skipped only
 * when it is a non-broadcasting [Contact] (a [com.orbitalfrontier.world.HiddenContact]) whose id is not
 * yet revealed. Gates/stations ([Transponder]) and asteroid fields (plain [Poi]) always draw; a revealed
 * hidden contact draws its sprite. Render reads state only — no simulation here.
 */
class WorldObjectRenderer(
    private val assets: GameAssets,
) : Disposable {
    private val batch = SpriteBatch()

    fun render(
        camera: Camera,
        pois: List<Poi>,
        revealedContacts: Set<PoiId>,
    ) {
        if (pois.isEmpty()) return
        batch.projectionMatrix = camera.combined
        batch.begin()
        for (poi in pois) {
            if (!isVisible(poi, revealedContacts)) continue
            drawGlyph(poi.position.x, poi.position.y, WorldGlyphs.forPoi(poi))
        }
        batch.end()
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
        val region = assets.region(glyph.regionName)
        val s = glyph.sizeWorldUnits
        // Centre the sprite on the POI position at its world size (full extent = 2 × half-extent),
        // preserving the authored centre pivot so positions/collision/camera are unchanged (AC#4).
        batch.draw(region, x - s, y - s, s * 2f, s * 2f)
    }

    override fun dispose() {
        batch.dispose()
    }
}
