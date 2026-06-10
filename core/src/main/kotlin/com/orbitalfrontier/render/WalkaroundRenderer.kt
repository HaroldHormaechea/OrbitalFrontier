package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.common.Rect
import com.orbitalfrontier.walkaround.Avatar
import com.orbitalfrontier.walkaround.StationInterior

/**
 * Renderer for the on-foot station interior (UC19), mirroring [ShipRenderer]: it only reads pure
 * [StationInterior] / [Avatar] state and draws it in world space using the screen's injected camera.
 * No simulation here.
 *
 * UC27 (AC#7): the walkaround prototype now draws the design-system sprites — `floor-tile` filling each
 * walkable area, `wall-tile` framing its edges so the boundary reads as walls, `npc-shopkeeper` for the
 * shopkeeper, and `avatar-player` for the player avatar. The docked **ship** in the bay has no dedicated
 * interior sprite in the bundle, so it stays a programmer-art block on the [ShapeRenderer] (drawn between
 * the floor and the characters). The shared [GameAssets] atlas is **borrowed** (never disposed here); this
 * renderer owns its own batch + shape renderer and the regions are resolved once at construction.
 */
class WalkaroundRenderer(
    private val assets: GameAssets,
) : Disposable {
    private val batch = SpriteBatch()
    private val shapeRenderer = ShapeRenderer()

    private val floorRegion: TextureRegion = assets.region(AtlasRegions.FLOOR_TILE)
    private val wallRegion: TextureRegion = assets.region(AtlasRegions.WALL_TILE)
    private val shopkeeperRegion: TextureRegion = assets.region(AtlasRegions.NPC_SHOPKEEPER)
    private val avatarRegion: TextureRegion = assets.region(AtlasRegions.AVATAR_PLAYER)

    fun render(
        camera: Camera,
        interior: StationInterior,
        avatar: Avatar,
        avatarRadius: Float,
    ) {
        // Pass 1 (sprites): the floor under everything, then the wall frame around each walkable area.
        batch.projectionMatrix = camera.combined
        batch.begin()
        for (area in interior.walkableAreas) {
            // Stretch the floor tile to fill the area (programmer-art prototype; tiling deferred).
            batch.draw(floorRegion, area.minX, area.minY, area.width, area.height)
        }
        for (area in interior.walkableAreas) {
            drawWallFrame(area)
        }
        batch.end()

        // Pass 2 (shapes): the docked ship block (no interior sprite in the bundle).
        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = SHIP_COLOR
        fillBox(interior.shipPosition.x, interior.shipPosition.y, SHIP_SIZE)
        shapeRenderer.end()

        // Pass 3 (sprites): the characters on top — shopkeeper, then the player avatar.
        batch.begin()
        drawCentered(shopkeeperRegion, interior.shopkeeperPosition.x, interior.shopkeeperPosition.y, SHOPKEEPER_SIZE)
        // The avatar sprite is drawn centred at twice its collision radius (matches the old ball footprint).
        drawCentered(avatarRegion, avatar.position.x, avatar.position.y, avatarRadius * 2f)
        batch.end()
    }

    /** Draw [region] centred on ([cx], [cy]) at the given full [size] (width = height). */
    private fun drawCentered(
        region: TextureRegion,
        cx: Float,
        cy: Float,
        size: Float,
    ) {
        batch.draw(region, cx - size / 2f, cy - size / 2f, size, size)
    }

    /** Draw the wall tile as four edge strips around [area] so its boundary reads as walls (AC#7). */
    private fun drawWallFrame(area: Rect) {
        val t = WALL_THICKNESS
        // Bottom, top, left, right strips (stretched wall tile).
        batch.draw(wallRegion, area.minX, area.minY, area.width, t)
        batch.draw(wallRegion, area.minX, area.maxY - t, area.width, t)
        batch.draw(wallRegion, area.minX, area.minY, t, area.height)
        batch.draw(wallRegion, area.maxX - t, area.minY, t, area.height)
    }

    private fun fillBox(
        centerX: Float,
        centerY: Float,
        size: Float,
    ) {
        shapeRenderer.rect(centerX - size / 2f, centerY - size / 2f, size, size)
    }

    override fun dispose() {
        batch.dispose()
        shapeRenderer.dispose()
    }

    private companion object {
        const val SHIP_SIZE = 60f
        const val SHOPKEEPER_SIZE = 28f

        /** Wall-tile strip thickness (world units) framing each walkable area. */
        const val WALL_THICKNESS = 8f

        val SHIP_COLOR = Color(0.70f, 0.78f, 0.95f, 1f)
    }
}
