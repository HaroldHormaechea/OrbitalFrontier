package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.world.JumpGate

/**
 * Draws each jump gate's **trigger-radius ring** in world space, using the follow camera's projection
 * (mirrors [ShipRenderer]/[StarfieldRenderer]) — UC03 AC#3 visual.
 *
 * Since ADR 0015 the gate's **marker** (the filled diamond) is drawn by the shared [WorldObjectRenderer]
 * from the gate's base [WorldGlyph], so every POI has a guaranteed in-world graphic; this renderer is now
 * the additive ring overlay on top, showing where the authored trigger circle is. Placeholder art until
 * real gate sprites exist; it only reads [JumpGate] data (no simulation here — render reads state, per
 * coding-guidelines).
 */
class GateRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        gates: List<JumpGate>,
    ) {
        if (gates.isEmpty()) return
        shapeRenderer.projectionMatrix = camera.combined

        // Trigger-radius rings (outlines). The gate marker itself is drawn by WorldObjectRenderer.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = RING_COLOR
        for (gate in gates) {
            shapeRenderer.circle(gate.position.x, gate.position.y, gate.triggerRadius, RING_SEGMENTS)
        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val RING_SEGMENTS = 48
        val RING_COLOR = Color(0.3f, 0.6f, 0.85f, 1f)
    }
}
