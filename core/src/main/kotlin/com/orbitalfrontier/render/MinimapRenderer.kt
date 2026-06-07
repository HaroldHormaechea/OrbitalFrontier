package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.world.JumpGate

/**
 * A small screen-space HUD minimap of the current sector's known POIs (the jump gates) plus the
 * ship's marker (UC03 AC#6).
 *
 * Drawn in screen space (like [StarfieldRenderer]/[HudRenderer]) as a square panel in the
 * bottom-right corner. World positions (sector centre = origin) are scaled into the panel by the
 * sector's `contentExtent`, so the content area fills the minimap regardless of sector size; markers
 * outside the extent are clamped to the panel edge so the ship is always visible (the sector is
 * unbounded, AC#2). Reads state only — no simulation here (coding-guidelines § simulation vs render).
 */
class MinimapRenderer(
    private val sizePx: Float = DEFAULT_SIZE,
    private val marginPx: Float = DEFAULT_MARGIN,
) : Disposable {
    private val shapeRenderer = ShapeRenderer()
    private val projection = Matrix4()

    fun render(
        gates: List<JumpGate>,
        shipPosition: Vec2,
        contentExtent: Float,
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

        // Gate markers + ship marker.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = GATE_COLOR
        for (gate in gates) {
            val (x, y) = clampToPanel(centerX, centerY, half, gate.position, scale)
            shapeRenderer.circle(x, y, GATE_MARKER_RADIUS)
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
        const val SHIP_MARKER_RADIUS = 3f
        val PANEL_COLOR = Color(0.05f, 0.07f, 0.12f, 0.55f)
        val BORDER_COLOR = Color(0.4f, 0.5f, 0.65f, 0.9f)
        val GATE_COLOR = Color(0.4f, 0.85f, 1f, 1f)
        val SHIP_COLOR = Color(1f, 0.85f, 0.4f, 1f)
    }
}
