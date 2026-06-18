package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.world.AsteroidField

/**
 * Draws each asteroid field's **mining-radius ring** in world space, using the follow camera's
 * projection (mirrors [GateRenderer]) — UC06 visual.
 *
 * Since ADR 0015 the field's **marker** (the rock-cluster sprite) is drawn by the shared
 * [WorldObjectRenderer] from the field's base [WorldGlyph], so every POI has a guaranteed in-world
 * graphic. This renderer is the **intentional additive overlay** on top: the outline of the mining
 * circle, showing the player where mining is in range (the analogue of a gate's trigger ring). The ring
 * is a deliberate gameplay-range affordance, not placeholder art — it traces the model's
 * [AsteroidField.miningRadius], which is independent of the marker sprite's visual size. It only reads
 * [AsteroidField] data — no simulation here (render reads state, per coding-guidelines).
 */
class AsteroidFieldRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        fields: List<AsteroidField>,
    ) {
        if (fields.isEmpty()) return
        shapeRenderer.projectionMatrix = camera.combined

        // Mining-radius rings (outlines). The rock-cluster marker is drawn by WorldObjectRenderer.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = RING_COLOR
        for (field in fields) {
            shapeRenderer.circle(field.position.x, field.position.y, field.miningRadius, RING_SEGMENTS)
        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val RING_SEGMENTS = 48
        val RING_COLOR = Color(0.6f, 0.5f, 0.3f, 1f)
    }
}
