package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.ProjectileOwner
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the live combat encounter in world space (UC13) — each [com.orbitalfrontier.combat.Hostile] as
 * a red arrow pointed along its heading (mirroring [ShipRenderer]'s placeholder triangle) and each
 * in-flight [com.orbitalfrontier.combat.Projectile] as a small dot tinted by owner (player vs hostile).
 *
 * Placeholder programmatic art until real sprites exist; it only **reads** the [CombatState] (render
 * reads state, per coding-guidelines — no simulation here). A no-op when combat is inactive.
 */
class HostileRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        combat: CombatState,
    ) {
        if (!combat.active) return
        shapeRenderer.projectionMatrix = camera.combined

        // Projectiles: small filled dots, tinted by owner.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (projectile in combat.projectiles) {
            shapeRenderer.color =
                if (projectile.owner == ProjectileOwner.PLAYER) PLAYER_SHOT_COLOR else HOSTILE_SHOT_COLOR
            shapeRenderer.circle(projectile.position.x, projectile.position.y, SHOT_RADIUS, SHOT_SEGMENTS)
        }

        // Hostiles: red arrows pointing along their heading.
        shapeRenderer.color = HOSTILE_COLOR
        for (hostile in combat.hostiles) {
            val heading = hostile.kinematics.headingRadians
            val px = hostile.kinematics.position.x
            val py = hostile.kinematics.position.y
            val noseX = px + cos(heading) * SIZE
            val noseY = py + sin(heading) * SIZE
            val leftX = px + cos(heading + REAR_ANGLE) * SIZE * REAR_SCALE
            val leftY = py + sin(heading + REAR_ANGLE) * SIZE * REAR_SCALE
            val rightX = px + cos(heading - REAR_ANGLE) * SIZE * REAR_SCALE
            val rightY = py + sin(heading - REAR_ANGLE) * SIZE * REAR_SCALE
            shapeRenderer.triangle(noseX, noseY, leftX, leftY, rightX, rightY)
        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val SIZE = 18f
        const val REAR_ANGLE = 2.443f
        const val REAR_SCALE = 0.8f
        const val SHOT_RADIUS = 5f
        const val SHOT_SEGMENTS = 8
        val HOSTILE_COLOR = Color(1f, 0.4f, 0.35f, 1f)
        val PLAYER_SHOT_COLOR = Color(0.7f, 0.9f, 1f, 1f)
        val HOSTILE_SHOT_COLOR = Color(1f, 0.7f, 0.3f, 1f)
    }
}
