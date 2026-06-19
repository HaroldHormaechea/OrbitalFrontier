package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable

/**
 * The device-only draw side of the combat HUD overlay (UC44 AC#1/#2/#3): the per-hostile health bars and
 * the auto-aim lock reticle in **world space**, plus the full-screen damage-taken flash in screen space.
 * Mirrors [HostileRenderer]/[MinimapRenderer] — it owns its own [ShapeRenderer] (borrows nothing) and is
 * a no-op when there is nothing to draw.
 *
 * Everything here is a [ShapeRenderer] primitive drawn from [renderFrame], **not** a scene2d `Stage`
 * actor (UC44 PIN #3 / AC#4): the always-on FIRE arc (UC26) and the expanded HUD (UC34) live on the
 * screen-space scene2d layer, so keeping these overlay marks off that layer guarantees they cannot drift
 * into or overlap the FIRE-arc/HUD widgets. The bars/reticle are world-space (they track hostiles); the
 * damage flash is a full-screen quad (zero layout footprint).
 *
 * Reads derived state only ([CombatHudState] + a [CombatFeedback] snapshot) — render reads state, never
 * the simulation (coding-guidelines § simulation vs render).
 */
class CombatHudRenderer : Disposable {
    private val shapeRenderer = ShapeRenderer()
    private val screenProjection = Matrix4()
    private val scratchColor = Color()

    /**
     * Draw the world-space overlay: a floating health bar above each hostile (fill tinted by hull
     * fraction, alpha by distance fade) and a pulsing lock reticle around the auto-targeted hostile.
     * No-op when [state] is empty. [feedback] supplies the reticle's hit pulse ([CombatFeedback.dealtFlash]).
     */
    fun render(
        camera: Camera,
        state: CombatHudState,
        feedback: CombatFeedback,
    ) {
        if (state.bars.isEmpty() && state.lockedTargetId == null) return

        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapeRenderer.projectionMatrix = camera.combined

        // Health bars: a dark backing rail then a hull-coloured fill, both faded by distance.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (bar in state.bars) {
            if (bar.fade <= 0f) continue
            val left = bar.position.x - BAR_WIDTH / 2f
            val bottom = bar.position.y + BAR_Y_OFFSET
            shapeRenderer.color = withAlpha(Palette.SURFACE_BASE, BAR_BACKING_ALPHA * bar.fade)
            shapeRenderer.rect(left, bottom, BAR_WIDTH, BAR_HEIGHT)
            shapeRenderer.color = withAlpha(hullColor(bar.hullFraction), bar.fade)
            shapeRenderer.rect(left, bottom, BAR_WIDTH * bar.hullFraction.coerceIn(0f, 1f), BAR_HEIGHT)
        }
        shapeRenderer.end()

        // Lock reticle: a ring around the auto-targeted hostile, pulsing outward with the dealt-hit flash.
        val locked = state.lockedTargetId?.let { id -> state.bars.firstOrNull { it.id == id } }
        if (locked != null) {
            val radius = RETICLE_RADIUS + feedback.dealtFlash * RETICLE_PULSE
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = withAlpha(Palette.ACCENT, RETICLE_ALPHA)
            shapeRenderer.circle(locked.position.x, locked.position.y, radius, RETICLE_SEGMENTS)
            shapeRenderer.end()
        }

        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    /**
     * Draw the full-screen damage-taken flash (UC44 AC#3): a translucent DANGER quad over the whole
     * viewport at alpha = [takenFlash]. A screen-space [ShapeRenderer] fill (no scene2d actor), so it has
     * zero layout footprint and cannot overlap the FIRE arc / HUD (PIN #3 / AC#4). No-op at zero alpha.
     */
    fun renderDamageFlash(
        takenFlash: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (takenFlash <= 0f) return
        Gdx.gl.glEnable(GL20.GL_BLEND)
        screenProjection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.projectionMatrix = screenProjection
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = withAlpha(Palette.DANGER, takenFlash * FLASH_MAX_ALPHA)
        shapeRenderer.rect(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    /** The hull-fraction colour ramp — SUCCESS (healthy) → WARNING → DANGER (critical), design-system. */
    private fun hullColor(fraction: Float): Color =
        when {
            fraction > HEALTHY_THRESHOLD -> Palette.SUCCESS
            fraction > WARN_THRESHOLD -> Palette.WARNING
            else -> Palette.DANGER
        }

    /** A copy of [base] at [alpha] (reusing the scratch colour — no per-call allocation in the draw loop). */
    private fun withAlpha(
        base: Color,
        alpha: Float,
    ): Color = scratchColor.set(base.r, base.g, base.b, alpha.coerceIn(0f, 1f))

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        // Health bar geometry (world units), sized to read above the hostile sprite without crowding.
        const val BAR_WIDTH = 44f
        const val BAR_HEIGHT = 6f
        const val BAR_Y_OFFSET = WorldSpriteSizes.HOSTILE + 8f
        const val BAR_BACKING_ALPHA = 0.7f

        // Hull-fraction thresholds for the colour ramp (mirrors ShipSchematicRenderer's healthy/warn split).
        const val HEALTHY_THRESHOLD = 0.6f
        const val WARN_THRESHOLD = 0.3f

        // Lock reticle (world units): a ring that pulses outward by RETICLE_PULSE at full dealt-hit flash.
        const val RETICLE_RADIUS = WorldSpriteSizes.HOSTILE + 14f
        const val RETICLE_PULSE = 8f
        const val RETICLE_ALPHA = 0.9f
        const val RETICLE_SEGMENTS = 24

        // Full-screen damage flash: cap the alpha so even a full-strength hit only tints, never blanks out.
        const val FLASH_MAX_ALPHA = 0.35f
    }
}
