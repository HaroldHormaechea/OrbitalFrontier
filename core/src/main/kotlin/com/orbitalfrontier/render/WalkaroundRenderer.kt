package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.common.Rect
import com.orbitalfrontier.walkaround.Avatar
import com.orbitalfrontier.walkaround.StationInterior

/**
 * Programmer-art renderer for the on-foot station interior (UC19), mirroring [ShipRenderer]: it only
 * reads pure [StationInterior] / [Avatar] state and draws it in world space using the screen's
 * injected camera. No simulation here.
 *
 * Deliberately low-fidelity (AC: programmer-art is acceptable): filled boxes for the walkable
 * landing/corridor/room with outlined walls, a box for the ship, a box for the shopkeeper, and the
 * avatar as a ball with a small dot showing its facing direction (AC#3).
 */
class WalkaroundRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()

    fun render(
        camera: Camera,
        interior: StationInterior,
        avatar: Avatar,
        avatarRadius: Float,
    ) {
        shapeRenderer.projectionMatrix = camera.combined

        // Filled pass: walkable floor, ship + shopkeeper boxes, avatar ball + facing dot.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        shapeRenderer.color = FLOOR_COLOR
        for (area in interior.walkableAreas) {
            fillRect(area)
        }

        shapeRenderer.color = SHIP_COLOR
        fillBox(interior.shipPosition.x, interior.shipPosition.y, SHIP_SIZE)

        shapeRenderer.color = SHOPKEEPER_COLOR
        fillBox(interior.shopkeeperPosition.x, interior.shopkeeperPosition.y, SHOPKEEPER_SIZE)

        shapeRenderer.color = AVATAR_COLOR
        shapeRenderer.circle(avatar.position.x, avatar.position.y, avatarRadius, CIRCLE_SEGMENTS)

        // Facing dot sits just inside the rim along the facing direction (AC#3/#4).
        val dotX = avatar.position.x + avatar.facing.x * avatarRadius * FACING_DOT_OFFSET
        val dotY = avatar.position.y + avatar.facing.y * avatarRadius * FACING_DOT_OFFSET
        shapeRenderer.color = FACING_DOT_COLOR
        shapeRenderer.circle(dotX, dotY, avatarRadius * FACING_DOT_SCALE, CIRCLE_SEGMENTS)

        shapeRenderer.end()

        // Line pass: outline each walkable area so the outer walls read as walls (AC#8 boundaries).
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = WALL_COLOR
        for (area in interior.walkableAreas) {
            outlineRect(area)
        }
        shapeRenderer.end()
    }

    private fun fillRect(rect: Rect) {
        shapeRenderer.rect(rect.minX, rect.minY, rect.width, rect.height)
    }

    private fun outlineRect(rect: Rect) {
        shapeRenderer.rect(rect.minX, rect.minY, rect.width, rect.height)
    }

    private fun fillBox(
        centerX: Float,
        centerY: Float,
        size: Float,
    ) {
        shapeRenderer.rect(centerX - size / 2f, centerY - size / 2f, size, size)
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val SHIP_SIZE = 60f
        const val SHOPKEEPER_SIZE = 28f
        const val CIRCLE_SEGMENTS = 24
        const val FACING_DOT_OFFSET = 0.6f
        const val FACING_DOT_SCALE = 0.3f
        val FLOOR_COLOR = Color(0.18f, 0.20f, 0.26f, 1f)
        val WALL_COLOR = Color(0.55f, 0.60f, 0.75f, 1f)
        val SHIP_COLOR = Color(0.70f, 0.78f, 0.95f, 1f)
        val SHOPKEEPER_COLOR = Color(0.95f, 0.80f, 0.45f, 1f)
        val AVATAR_COLOR = Color(0.45f, 0.85f, 0.65f, 1f)
        val FACING_DOT_COLOR = Color(0.05f, 0.10f, 0.08f, 1f)
    }
}
