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
 * A small screen-space HUD minimap of the current sector's transponder-broadcasting POIs (jump
 * gates and stations) plus the ship's marker (UC03 AC#6; UC05 AC#1).
 *
 * Drawn in screen space (like [StarfieldRenderer]/[HudRenderer]) as a square panel in the
 * bottom-right corner. World positions (sector centre = origin) are scaled into the panel by the
 * sector's `contentExtent`, so the content area fills the minimap regardless of sector size; markers
 * outside the extent are clamped to the panel edge so the ship is always visible (the sector is
 * unbounded, AC#2). Reads state only — no simulation here (coding-guidelines § simulation vs render).
 *
 * The minimap renders against the [Contact] capability, not concrete POI types (the Open/Closed
 * seam, coding-guidelines § O): it filters the sector's POIs to those that are contacts and draws each
 * by its [ContactKind] — a station as a filled square, a gate as the existing dot, a scanned hidden
 * contact as a small triangle. A [Transponder] (gate/station) is always drawn; a hidden contact
 * (UC10) is drawn only once its id is in [revealedContacts]. A new contact kind shows up by extending
 * this marker switch, with no change to the world model.
 */
class MinimapRenderer(
    private val sizePx: Float = DEFAULT_SIZE,
    private val marginPx: Float = DEFAULT_MARGIN,
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
        val originX = viewportWidth - marginPx - sizePx
        val originY = marginPx
        val centerX = originX + sizePx / 2f
        val centerY = originY + sizePx / 2f
        // Map a world radius of contentExtent onto half the panel (minus padding for the markers).
        val half = sizePx / 2f - PADDING
        val scale = if (contentExtent > 0f) half / contentExtent else 0f

        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.projectionMatrix = projection

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        // Translucent backing panel.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = PANEL_COLOR
        shapeRenderer.rect(originX, originY, sizePx, sizePx)
        shapeRenderer.end()

        // Contact markers (one per visible contact, styled by contact kind) + ship marker. A
        // Transponder (gate/station) is always visible; a hidden contact (UC10) is drawn only once its
        // id is in revealedContacts.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (poi in pois) {
            if (poi !is Contact) continue
            if (poi !is Transponder && poi.id !in revealedContacts) continue
            val (x, y) = clampToPanel(centerX, centerY, half, poi.position, scale)
            when (poi.contactKind) {
                ContactKind.GATE -> {
                    shapeRenderer.color = GATE_COLOR
                    shapeRenderer.circle(x, y, GATE_MARKER_RADIUS)
                }
                ContactKind.STATION -> {
                    shapeRenderer.color = STATION_COLOR
                    // Filled square centred on the marker position, distinct from the gate dot.
                    val r = STATION_MARKER_RADIUS
                    shapeRenderer.rect(x - r, y - r, r * 2f, r * 2f)
                }
                ContactKind.SHIP -> {
                    shapeRenderer.color = CONTACT_COLOR
                    // An upward triangle centred on the marker, distinct from the gate dot/station square.
                    val r = CONTACT_MARKER_RADIUS
                    shapeRenderer.triangle(x - r, y - r, x + r, y - r, x, y + r)
                }
            }
        }
        shapeRenderer.color = SHIP_COLOR
        val (sx, sy) = clampToPanel(centerX, centerY, half, shipPosition, scale)
        shapeRenderer.circle(sx, sy, SHIP_MARKER_RADIUS)
        shapeRenderer.end()

        // Panel border.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = BORDER_COLOR
        shapeRenderer.rect(originX, originY, sizePx, sizePx)
        shapeRenderer.end()

        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    /** Map a world position to a panel pixel, clamping it to stay within the panel's drawable area. */
    private fun clampToPanel(
        centerX: Float,
        centerY: Float,
        half: Float,
        worldPosition: Vec2,
        scale: Float,
    ): Pair<Float, Float> {
        val dx = (worldPosition.x * scale).coerceIn(-half, half)
        val dy = (worldPosition.y * scale).coerceIn(-half, half)
        return (centerX + dx) to (centerY + dy)
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val DEFAULT_SIZE = 180f
        const val DEFAULT_MARGIN = 24f
        const val PADDING = 12f
        const val GATE_MARKER_RADIUS = 4f
        const val STATION_MARKER_RADIUS = 4f
        const val CONTACT_MARKER_RADIUS = 4f
        const val SHIP_MARKER_RADIUS = 3f
        val PANEL_COLOR = Color(0.05f, 0.07f, 0.12f, 0.55f)
        val BORDER_COLOR = Color(0.4f, 0.5f, 0.65f, 0.9f)
        val GATE_COLOR = Color(0.4f, 0.85f, 1f, 1f)
        val STATION_COLOR = Color(0.5f, 1f, 0.6f, 1f)

        // Revealed hidden contacts (UC10): a hostile-leaning red, distinct from gates/stations/ship.
        val CONTACT_COLOR = Color(1f, 0.4f, 0.4f, 1f)
        val SHIP_COLOR = Color(1f, 0.85f, 0.4f, 1f)
    }
}
