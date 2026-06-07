package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.world.JumpGate

/**
 * Draws the current sector's jump gates as programmatic placeholder shapes in world space, using
 * the follow camera's projection (mirrors [ShipRenderer]/[StarfieldRenderer]) — UC03 AC#3 visual.
 *
 * Each gate is a filled diamond at its position plus an outlined ring at its authored trigger radius,
 * so the player can see where the trigger circle is. Placeholder art until real gate sprites exist;
 * it only reads [JumpGate] data (no simulation here — render reads state, per coding-guidelines).
 */
class GateRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        gates: List<JumpGate>,
    ) {
        if (gates.isEmpty()) return
        shapeRenderer.projectionMatrix = camera.combined

        // Trigger-radius rings (outlines).
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = RING_COLOR
        for (gate in gates) {
            shapeRenderer.circle(gate.position.x, gate.position.y, gate.triggerRadius, RING_SEGMENTS)
        }
        shapeRenderer.end()

        // Gate markers (filled diamonds at the gate centre).
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = GATE_COLOR
        for (gate in gates) {
            val x = gate.position.x
            val y = gate.position.y
            shapeRenderer.triangle(x, y + MARKER_SIZE, x - MARKER_SIZE, y, x + MARKER_SIZE, y)
            shapeRenderer.triangle(x, y - MARKER_SIZE, x - MARKER_SIZE, y, x + MARKER_SIZE, y)
        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val MARKER_SIZE = 28f
        const val RING_SEGMENTS = 48
        val GATE_COLOR = Color(0.4f, 0.85f, 1f, 1f)
        val RING_COLOR = Color(0.3f, 0.6f, 0.85f, 1f)
    }
}
