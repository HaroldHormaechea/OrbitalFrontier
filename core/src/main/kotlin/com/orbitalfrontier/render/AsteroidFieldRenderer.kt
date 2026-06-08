package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.world.AsteroidField

/**
 * Draws the current sector's asteroid fields as programmatic placeholder shapes in world space,
 * using the follow camera's projection (mirrors [GateRenderer]) — UC06 visual.
 *
 * Each field is a cluster of filled rocks at its position plus an outlined ring at its authored
 * mining radius, so the player can see where the mining circle is (the analogue of a gate's trigger
 * ring). Placeholder art until real asteroid sprites exist; it only reads [AsteroidField] data — no
 * simulation here (render reads state, per coding-guidelines). Asteroid fields are **not**
 * transponders, so they are deliberately absent from the minimap — only this in-world renderer
 * shows them (proximity detection is UC10).
 */
class AsteroidFieldRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        fields: List<AsteroidField>,
    ) {
        if (fields.isEmpty()) return
        shapeRenderer.projectionMatrix = camera.combined

        // Mining-radius rings (outlines).
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = RING_COLOR
        for (field in fields) {
            shapeRenderer.circle(field.position.x, field.position.y, field.miningRadius, RING_SEGMENTS)
        }
        shapeRenderer.end()

        // Field markers: a small cluster of filled rocks around the field centre.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = ROCK_COLOR
        for (field in fields) {
            val cx = field.position.x
            val cy = field.position.y
            shapeRenderer.circle(cx, cy, ROCK_SIZE, ROCK_SEGMENTS)
            shapeRenderer.circle(cx - ROCK_OFFSET, cy + ROCK_OFFSET, ROCK_SIZE * 0.6f, ROCK_SEGMENTS)
            shapeRenderer.circle(cx + ROCK_OFFSET, cy - ROCK_OFFSET * 0.5f, ROCK_SIZE * 0.7f, ROCK_SEGMENTS)
        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val ROCK_SIZE = 26f
        const val ROCK_OFFSET = 34f
        const val ROCK_SEGMENTS = 16
        const val RING_SEGMENTS = 48
        val ROCK_COLOR = Color(0.55f, 0.5f, 0.42f, 1f)
        val RING_COLOR = Color(0.6f, 0.5f, 0.3f, 1f)
    }
}
