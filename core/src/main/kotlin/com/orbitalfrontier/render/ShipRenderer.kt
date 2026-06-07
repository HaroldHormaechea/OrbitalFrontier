package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.ship.ShipKinematics
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the player ship as a programmatic placeholder sprite — a filled triangle whose nose
 * points along the hull heading — in world space, using the follow camera's projection.
 *
 * A placeholder until real art exists; it only reads [ShipKinematics] (no simulation here).
 */
class ShipRenderer(
    private val sizeWorldUnits: Float = DEFAULT_SIZE,
) : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        kinematics: ShipKinematics,
    ) {
        val heading = kinematics.headingRadians
        val px = kinematics.position.x
        val py = kinematics.position.y

        val noseX = px + cos(heading) * sizeWorldUnits
        val noseY = py + sin(heading) * sizeWorldUnits
        val leftX = px + cos(heading + REAR_ANGLE) * sizeWorldUnits * REAR_SCALE
        val leftY = py + sin(heading + REAR_ANGLE) * sizeWorldUnits * REAR_SCALE
        val rightX = px + cos(heading - REAR_ANGLE) * sizeWorldUnits * REAR_SCALE
        val rightY = py + sin(heading - REAR_ANGLE) * sizeWorldUnits * REAR_SCALE

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = HULL_COLOR
        shapeRenderer.triangle(noseX, noseY, leftX, leftY, rightX, rightY)
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val DEFAULT_SIZE = 18f

        // Rear corners sit ~140° either side of the nose, slightly inboard, for an arrow shape.
        const val REAR_ANGLE = 2.443f // ~140° in radians
        const val REAR_SCALE = 0.8f
        val HULL_COLOR = Color(0.85f, 0.9f, 1f, 1f)
    }
}
