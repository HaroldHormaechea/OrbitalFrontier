package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.ProjectileOwner

/**
 * Draws the live combat encounter in world space (UC13) — each [com.orbitalfrontier.combat.Hostile] as the
 * design-system `ship-hostile` sprite (rotated to its heading, mirroring [ShipRenderer]) and each in-flight
 * [com.orbitalfrontier.combat.Projectile] as the `projectile` sprite tinted by owner (player vs hostile).
 *
 * UC27 (AC#4): replaces the old generated triangle/dot with atlas sprites. The shared [GameAssets] atlas is
 * **borrowed** (never disposed here); this renderer owns only its own [SpriteBatch]. It only **reads** the
 * [CombatState] (render reads state — no simulation here) and is a no-op when combat is inactive.
 *
 * The batch colour is set to an owner tint for the projectile pass and **reset to white** before the hostile
 * pass so the pre-coloured hostile sprite draws untinted. [ROTATION_OFFSET_DEGREES] mirrors [ShipRenderer]'s
 * nose-up authoring offset and is the knob for post-visual-gate heading correction (AC#11).
 */
class HostileRenderer(
    private val assets: GameAssets,
) : Disposable {
    private val batch = SpriteBatch()
    private val hostileRegion: TextureRegion = assets.region(AtlasRegions.SHIP_HOSTILE)
    private val projectileRegion: TextureRegion = assets.region(AtlasRegions.PROJECTILE)

    fun render(
        camera: Camera,
        combat: CombatState,
    ) {
        if (!combat.active) return
        batch.projectionMatrix = camera.combined
        batch.begin()

        // Projectiles: the shot sprite tinted by owner, centred at the projectile position.
        val shotSize = SHOT_RADIUS * 2f
        for (projectile in combat.projectiles) {
            batch.color = if (projectile.owner == ProjectileOwner.PLAYER) PLAYER_SHOT_TINT else HOSTILE_SHOT_TINT
            batch.draw(projectileRegion, projectile.position.x - SHOT_RADIUS, projectile.position.y - SHOT_RADIUS, shotSize, shotSize)
        }
        // Reset to white so the pre-coloured hostile sprite is not tinted by the last projectile colour.
        batch.color = Color.WHITE

        // Hostiles: the hostile-ship sprite, centred and rotated about its centre to its heading.
        val size = SIZE * 2f
        for (hostile in combat.hostiles) {
            val px = hostile.kinematics.position.x
            val py = hostile.kinematics.position.y
            val rotationDeg = hostile.kinematics.headingRadians * MathUtils.radiansToDegrees + ROTATION_OFFSET_DEGREES
            batch.draw(hostileRegion, px - SIZE, py - SIZE, SIZE, SIZE, size, size, 1f, 1f, rotationDeg)
        }
        batch.end()
    }

    override fun dispose() {
        batch.dispose()
    }

    private companion object {
        /** Half-extent of the hostile sprite in world units — unchanged from the old placeholder. */
        const val SIZE = 18f

        /** Half-extent of the projectile sprite — preserves the old shot radius (collision unchanged). */
        const val SHOT_RADIUS = 5f

        /** Nose-up authoring offset; the knob for post-visual-gate heading correction (AC#11). */
        const val ROTATION_OFFSET_DEGREES = -90f

        // Owner tints for the projectile sprite (design-system signals): cyan = friendly, amber = hostile.
        val PLAYER_SHOT_TINT: Color = Palette.CYAN_400
        val HOSTILE_SHOT_TINT: Color = Palette.AMBER_400
    }
}
