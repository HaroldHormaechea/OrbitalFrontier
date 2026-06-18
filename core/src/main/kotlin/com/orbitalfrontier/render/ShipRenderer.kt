package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.ship.ShipKinematics

/**
 * Draws the player ship as the design-system `ship-player` sprite — centred on the hull position and
 * rotated about its centre to the hull heading — in world space, using the follow camera's projection.
 * It only reads [ShipKinematics] (render reads state — no simulation here).
 *
 * The ship's world half-extent comes from [WorldSpriteSizes.SHIP] (the per-type sizing single source of
 * truth, ADR 0019), overridable via [sizeWorldUnits] for tests. The shared [GameAssets] atlas is
 * **borrowed** (never disposed here); this renderer owns only its own [SpriteBatch]. The ship region is
 * resolved once at construction.
 *
 * **Heading alignment ([ROTATION_OFFSET_DEGREES]).** [ShipKinematics.headingRadians] uses the math
 * convention (0 rad = +x / right). The delivered ship art is authored **nose-up** (+y), so the sprite must
 * be rotated by −90° on top of the heading to make its nose track the heading. This offset is the single
 * knob to correct alignment after the mandatory visual-gate inspection (AC#11) — a sprite re-authored
 * nose-right would only need this constant changed to 0, with no structural change.
 */
class ShipRenderer(
    private val assets: GameAssets,
    private val sizeWorldUnits: Float = WorldSpriteSizes.SHIP,
) : Disposable {
    private val batch = SpriteBatch()
    private val region: TextureRegion = assets.region(AtlasRegions.SHIP_PLAYER)

    fun render(
        camera: Camera,
        kinematics: ShipKinematics,
    ) {
        val px = kinematics.position.x
        val py = kinematics.position.y
        val size = sizeWorldUnits * 2f
        val rotationDeg = kinematics.headingRadians * MathUtils.radiansToDegrees + ROTATION_OFFSET_DEGREES

        batch.projectionMatrix = camera.combined
        batch.begin()
        // x,y = bottom-left; origin (centre) at sizeWorldUnits so it rotates about the hull position.
        batch.draw(
            region,
            px - sizeWorldUnits,
            py - sizeWorldUnits,
            sizeWorldUnits,
            sizeWorldUnits,
            size,
            size,
            1f,
            1f,
            rotationDeg,
        )
        batch.end()
    }

    override fun dispose() {
        batch.dispose()
    }

    private companion object {
        /**
         * Degrees added to the heading so the nose-up-authored sprite tracks the math-convention heading.
         * The one knob for post-visual-gate heading correction (AC#11). −90° = sprite authored nose-up.
         */
        const val ROTATION_OFFSET_DEGREES = -90f
    }
}
