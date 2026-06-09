package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.world.Contact
import com.orbitalfrontier.world.ContactKind
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Transponder

/**
 * The click-to-zoom map overlay (UC23): a full-height, horizontally-centred map panel drawn over a
 * dim full-screen backdrop, opened by tapping the HUD minimap and dismissed by any tap.
 *
 * Mirrors [MinimapRenderer] — screen-space [ShapeRenderer] geometry, world positions projected by the
 * pure, libGDX-free [MapOverlayLayout] (world units) and scaled back up by [UiScale.factor] for the
 * pixel draw — but on a much larger panel and showing more sector area ([MapOverlayLayout.extentShown],
 * AC#4). The draw order is: a full-screen dim backdrop at [MapOverlayLayout.BACKDROP_ALPHA] (so the
 * scene stays faintly visible, AC#3) -> the panel background + border -> the contact markers at full
 * alpha.
 *
 * Markers use the **same** per-[ContactKind] switch and the **same** visibility filter as the minimap
 * (a [Transponder] always draws; a plain [Contact] only once its id is in [revealedContacts]), so the
 * overlay honours UC10 and never reveals hidden contacts the player has not scanned. State is read
 * only — no simulation here (coding-guidelines § simulation vs render).
 */
class MapOverlayRenderer(
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val shapeRenderer = ShapeRenderer()
    private val projection = Matrix4()

    fun render(
        pois: List<Poi>,
        shipPosition: Vec2,
        contentExtent: Float,
        revealedContacts: Set<PoiId>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        // Geometry is computed in WORLD units by the pure MapOverlayLayout (the same space the controls
        // are laid out in — no px<->world seam), then scaled back up by UiScale.factor for the draw.
        val rect =
            MapOverlayLayout.overlayRect(
                vpWidth = viewportWidth / uiScale,
                vpHeight = viewportHeight / uiScale,
            )
        val originX = rect.x * uiScale
        val originY = rect.y * uiScale
        val panelWidth = rect.width * uiScale
        val panelHeight = rect.height * uiScale

        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.projectionMatrix = projection

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        // Full-screen dim backdrop (AC#3): the gameplay behind it stays faintly visible at ~0.8 alpha.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = BACKDROP_COLOR
        shapeRenderer.rect(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.end()

        // Panel background.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = PANEL_COLOR
        shapeRenderer.rect(originX, originY, panelWidth, panelHeight)
        shapeRenderer.end()

        // Contact markers (one per visible contact, styled by contact kind) + ship marker, drawn at
        // full alpha so they stay legible over the backdrop. Same filter as the minimap: a Transponder
        // (gate/station) is always drawn; a hidden contact (UC10) only once its id is in revealedContacts
        // — the overlay never reveals unscanned contacts.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (poi in pois) {
            if (poi !is Contact) continue
            if (poi !is Transponder && poi.id !in revealedContacts) continue
            val p = MapOverlayLayout.project(rect, contentExtent, poi.position)
            val x = p.x * uiScale
            val y = p.y * uiScale
            when (poi.contactKind) {
                ContactKind.GATE -> {
                    shapeRenderer.color = GATE_COLOR
                    shapeRenderer.circle(x, y, GATE_MARKER_RADIUS * uiScale)
                }
                ContactKind.STATION -> {
                    shapeRenderer.color = STATION_COLOR
                    val r = STATION_MARKER_RADIUS * uiScale
                    shapeRenderer.rect(x - r, y - r, r * 2f, r * 2f)
                }
                ContactKind.SHIP -> {
                    shapeRenderer.color = CONTACT_COLOR
                    val r = CONTACT_MARKER_RADIUS * uiScale
                    shapeRenderer.triangle(x - r, y - r, x + r, y - r, x, y + r)
                }
            }
        }
        shapeRenderer.color = SHIP_COLOR
        val sp = MapOverlayLayout.project(rect, contentExtent, shipPosition)
        shapeRenderer.circle(sp.x * uiScale, sp.y * uiScale, SHIP_MARKER_RADIUS * uiScale)
        shapeRenderer.end()

        // === UC24 SEAM ===
        // Marker labels (text) belong here, after the marker loop and before the border: UC24 will draw
        // each contact's name beside its marker. Intentionally left empty for UC23 — no text in this UC.

        // Panel border.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = BORDER_COLOR
        shapeRenderer.rect(originX, originY, panelWidth, panelHeight)
        shapeRenderer.end()

        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        // Larger markers than the minimap's, sized for the full-height panel (world units, scaled up).
        const val GATE_MARKER_RADIUS = 6f
        const val STATION_MARKER_RADIUS = 6f
        const val CONTACT_MARKER_RADIUS = 6f
        const val SHIP_MARKER_RADIUS = 5f

        // Dim full-screen backdrop at the AC#3 opacity, so the gameplay behind stays faintly visible.
        val BACKDROP_COLOR = Color(0.02f, 0.03f, 0.06f, MapOverlayLayout.BACKDROP_ALPHA)

        // Panel is more opaque than the minimap's translucent backing so the zoomed map reads clearly.
        val PANEL_COLOR = Color(0.05f, 0.07f, 0.12f, 0.92f)
        val BORDER_COLOR = Color(0.4f, 0.5f, 0.65f, 0.9f)

        // Marker palette mirrors the minimap (gate dot / station square / revealed-contact triangle /
        // ship dot), all at full alpha so they stay legible over the backdrop.
        val GATE_COLOR = Color(0.4f, 0.85f, 1f, 1f)
        val STATION_COLOR = Color(0.5f, 1f, 0.6f, 1f)
        val CONTACT_COLOR = Color(1f, 0.4f, 0.4f, 1f)
        val SHIP_COLOR = Color(1f, 0.85f, 0.4f, 1f)
    }
}
