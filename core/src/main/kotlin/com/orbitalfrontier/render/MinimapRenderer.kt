package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.world.Contact
import com.orbitalfrontier.world.ContactKind
import com.orbitalfrontier.world.Named
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Transponder

/**
 * A small screen-space HUD minimap of the current sector's transponder-broadcasting POIs (jump
 * gates and stations) plus the ship's marker (UC03 AC#6; UC05 AC#1).
 *
 * Drawn in screen space (like [StarfieldRenderer]/[HudRenderer]) as a square panel anchored in the
 * top-right corner (UC22 — the conventional minimap location, clear of the bottom action controls).
 * Its size fits the space above the controls via [MinimapLayout.panelRect] (world units), so it never
 * overlaps the action cluster/joystick in any supported size or orientation. World positions
 * (sector centre = origin) are scaled into the panel by the
 * sector's `contentExtent`, so the content area fills the minimap regardless of sector size; markers
 * outside the extent are clamped to the panel edge so the ship is always visible (the sector is
 * unbounded, AC#2). Reads state only — no simulation here (coding-guidelines § simulation vs render).
 *
 * The minimap renders against the [Contact] capability, not concrete POI types (the Open/Closed
 * seam, coding-guidelines § O): it filters the sector's POIs to those that are contacts and draws each
 * by its [ContactKind]. A [Transponder] (gate/station) is always drawn; a hidden contact
 * (UC10) is drawn only once its id is in [revealedContacts]. A new contact kind shows up by extending
 * this marker switch, with no change to the world model.
 *
 * UC27 (AC#5): the panel + border stay [ShapeRenderer] primitives (re-themed to the design-system palette,
 * AC#8) and are drawn **first**; the markers are then the design-system `mm-*` sprites (gate/station/contact
 * + the ship's own `mm-player`) drawn in a **separate** [SpriteBatch] pass. The shared [GameAssets] atlas is
 * **borrowed** (never disposed here); the marker regions are resolved once at construction.
 */
class MinimapRenderer(
    private val assets: GameAssets,
    private val sizePx: Float = DEFAULT_SIZE,
    private val marginPx: Float = DEFAULT_MARGIN,
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val shapeRenderer = ShapeRenderer()
    private val projection = Matrix4()

    // UC24 name labels: a screen-space text pass over the markers. The built-in BitmapFont is scaled by
    // uiScale (like HudRenderer) but at a smaller base — labels should read as secondary annotations on
    // the small HUD minimap, not compete with the HUD readouts. GlyphLayout measures each name once per
    // draw so the label can be centred over its marker; no per-frame String/StringBuilder is allocated
    // (the name is read straight off the POI), protecting the 60 FPS budget (AC#4, AC perf).
    // UC27: the same batch also draws the mm-* marker sprites (one begin/end for markers then labels).
    private val batch = SpriteBatch()
    private val labelFont = BitmapFont().apply { data.setScale(uiScale * LABEL_FONT_SCALE) }
    private val glyphLayout = GlyphLayout()

    // UC27: marker sprites resolved once (borrowed atlas). mm-player is the ship's own marker.
    private val gateRegion: TextureRegion = assets.region(AtlasRegions.MM_GATE)
    private val stationRegion: TextureRegion = assets.region(AtlasRegions.MM_STATION)
    private val contactRegion: TextureRegion = assets.region(AtlasRegions.MM_CONTACT)
    private val playerRegion: TextureRegion = assets.region(AtlasRegions.MM_PLAYER)

    /**
     * The minimap panel rectangle in **world units** for the given world-unit viewport and reserved
     * bottom-control band — the single geometry source shared by this renderer's [render] draw and
     * [com.orbitalfrontier.screen.PlayScreen]'s invisible minimap tap-target actor (UC23), so the tap
     * target lands exactly on the drawn panel. Wraps the renderer's own size/margin and the
     * fit-to-corner [MIN_SIZE]/[CONTROL_GAP] bounds; pure delegation to [MinimapLayout.panelRect].
     */
    fun panelRect(
        vpWidth: Float,
        vpHeight: Float,
        reservedBottom: Float,
    ): MinimapLayout.Rect =
        MinimapLayout.panelRect(
            vpWidth = vpWidth,
            vpHeight = vpHeight,
            reservedBottom = reservedBottom,
            margin = marginPx,
            maxSize = sizePx,
            minSize = MIN_SIZE,
            gap = CONTROL_GAP,
        )

    fun render(
        pois: List<Poi>,
        shipPosition: Vec2,
        contentExtent: Float,
        revealedContacts: Set<PoiId>,
        viewportWidth: Float,
        viewportHeight: Float,
        reservedBottom: Float,
    ) {
        // Fit-to-corner placement is computed in WORLD units by the pure, libGDX-free MinimapLayout
        // (the same space PlayScreen.layoutControls lays the controls out in — no px↔world seam), then
        // scaled back up by UiScale.factor (ADR 0015) for the actual screen-space draw. reservedBottom
        // is the world height of the worst-case bottom controls, so the panel can never overlap them.
        val rect =
            panelRect(
                vpWidth = viewportWidth / uiScale,
                vpHeight = viewportHeight / uiScale,
                reservedBottom = reservedBottom,
            )
        val size = rect.width * uiScale
        val originX = rect.x * uiScale
        val originY = rect.y * uiScale
        val padding = PADDING * uiScale

        val centerX = originX + size / 2f
        val centerY = originY + size / 2f
        // Map a world radius of contentExtent onto half the panel (minus padding for the markers); the
        // markers and clampToPanel derive from the fitted size, so content tracks the panel unchanged.
        val half = size / 2f - padding
        val scale = if (contentExtent > 0f) half / contentExtent else 0f
        val marker = MARKER_DIAMETER * uiScale
        val markerHalf = marker / 2f

        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.projectionMatrix = projection

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        // UC27: panel + border first (ShapeRenderer, re-themed to the palette), then the mm-* marker
        // sprites in a separate SpriteBatch pass on top.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = PANEL_COLOR
        shapeRenderer.rect(originX, originY, size, size)
        shapeRenderer.end()

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = BORDER_COLOR
        shapeRenderer.rect(originX, originY, size, size)
        shapeRenderer.end()

        // Marker sprites + name labels share one batch pass. A Transponder (gate/station) is always
        // visible; a hidden contact (UC10) is drawn only once its id is in revealedContacts.
        batch.projectionMatrix = projection
        batch.begin()
        batch.color = Color.WHITE
        for (poi in pois) {
            if (poi !is Contact) continue
            if (poi !is Transponder && poi.id !in revealedContacts) continue
            val region =
                when (poi.contactKind) {
                    ContactKind.GATE -> gateRegion
                    ContactKind.STATION -> stationRegion
                    ContactKind.SHIP -> contactRegion
                }
            val (x, y) = clampToPanel(centerX, centerY, half, poi.position, scale)
            batch.draw(region, x - markerHalf, y - markerHalf, marker, marker)
        }
        // The ship's own marker (mm-player).
        val (sx, sy) = clampToPanel(centerX, centerY, half, shipPosition, scale)
        batch.draw(playerRegion, sx - markerHalf, sy - markerHalf, marker, marker)

        // Name labels (UC24): re-walk the same markers and draw each labelled POI's name centred just
        // above its marker, so the label tracks the (clamped) marker position. MapLabels.shouldLabel
        // gates this to stations on the cluttered minimap.
        labelFont.color = LABEL_COLOR
        for (poi in pois) {
            if (!MapLabels.shouldLabel(poi, revealedContacts, MapLabels.Surface.MINIMAP)) continue
            val (lx, ly) = clampToPanel(centerX, centerY, half, poi.position, scale)
            glyphLayout.setText(labelFont, (poi as Named).displayName)
            val labelY = ly + markerHalf + LABEL_GAP * uiScale + glyphLayout.height
            labelFont.draw(batch, glyphLayout, lx - glyphLayout.width / 2f, labelY)
        }
        batch.end()

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
        batch.dispose()
        labelFont.dispose()
    }

    private companion object {
        const val DEFAULT_SIZE = 180f
        const val DEFAULT_MARGIN = 24f

        // Fit-to-corner bounds (world units, UC22). The panel side is the height free above the bottom
        // controls, clamped to [MIN_SIZE, DEFAULT_SIZE] and kept CONTROL_GAP clear of those controls.
        const val MIN_SIZE = 120f
        const val CONTROL_GAP = 16f
        const val PADDING = 12f

        // UC27: a single marker sprite size for every mm-* marker (base world-unit diameter, ×uiScale at
        // the use site), sized so the sprites stay legible at minimap scale (AC#5).
        const val MARKER_DIAMETER = 13f

        // UC24 labels: a smaller base than the HUD font (which is ×uiScale) so minimap names read as
        // secondary annotations; LABEL_GAP is the world-unit clearance between marker and label baseline.
        const val LABEL_FONT_SCALE = 0.6f
        const val LABEL_GAP = 3f

        // UC27 palette (AC#8): a translucent void surface panel with a cold-tech cyan hairline border.
        val PANEL_COLOR: Color = Color(Palette.VOID_800).apply { a = 0.55f }
        val BORDER_COLOR: Color = Color(Palette.CYAN_600).apply { a = 0.9f }

        // Label text: high-emphasis steel, legible over the translucent panel without glaring (AC#4).
        val LABEL_COLOR: Color = Palette.STEEL_050
    }
}
