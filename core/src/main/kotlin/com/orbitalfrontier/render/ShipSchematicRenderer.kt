package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.combat.SectionDamage
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection

/**
 * The HUD **ship schematic** (UC13 AC#3): a small stack of per-section health bars — one per
 * [ShipSection] — shown only while a combat encounter is live, so the player sees which components are
 * damaged (engine/turret/weapon disabled, hull failing). Each bar fills to the section's current-HP
 * fraction and shifts colour green→amber→red as it drops; a fully-destroyed section reads as a dark
 * empty bar.
 *
 * Drawn in screen space with a [ShapeRenderer] (mirrors [HudRenderer]'s screen-space text). Placeholder
 * programmatic art — no labels yet (the built-in font is the HudRenderer's; the bar order is the fixed
 * [ShipSection] declaration order). It only **reads** the damage + derived max HP (render reads state).
 *
 * [uiScale] (ADR 0015) magnifies the schematic: every base layout constant (margin, bar size, gaps) is
 * multiplied by it at its use site, and the top offset matches HudRenderer's scaled three text lines so
 * the schematic still sits just below them. Constants stay authored at base (×1) — [UiScale.factor] is
 * the single knob.
 */
class ShipSchematicRenderer(
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val shapeRenderer = ShapeRenderer()
    private val projection = Matrix4()

    /**
     * Draw the schematic at the screen's left edge, below the HUD text lines. [sectionDamage] is the
     * ship's current damage (absent = pristine); [maxSectionHp] its derived max HP per section.
     */
    fun render(
        sectionDamage: SectionDamage,
        maxSectionHp: Map<ShipSection, Int>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.projectionMatrix = projection

        // Base layout constants scaled at the use site — UiScale.factor stays the single knob.
        val left = MARGIN * uiScale
        val barWidth = BAR_WIDTH * uiScale
        val barHeight = BAR_HEIGHT * uiScale
        val barGap = BAR_GAP * uiScale
        var top = viewportHeight - TOP_OFFSET * uiScale

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (section in ShipSection.entries) {
            val maxHp = maxSectionHp[section] ?: 0
            val fraction =
                if (maxHp <= 0) 0f else SectionDamages.currentHp(sectionDamage, section, maxHp).toFloat() / maxHp

            // Background track.
            shapeRenderer.color = TRACK_COLOR
            shapeRenderer.rect(left, top - barHeight, barWidth, barHeight)

            // Filled portion, coloured by remaining fraction.
            if (fraction > 0f) {
                shapeRenderer.color = colorFor(fraction)
                shapeRenderer.rect(left, top - barHeight, barWidth * fraction, barHeight)
            }
            top -= barHeight + barGap
        }
        shapeRenderer.end()
    }

    private fun colorFor(fraction: Float): Color =
        when {
            fraction > HEALTHY_THRESHOLD -> HEALTHY_COLOR
            fraction > WARN_THRESHOLD -> WARN_COLOR
            else -> CRITICAL_COLOR
        }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private companion object {
        const val MARGIN = 16f

        // Below the three HUD text lines (speed/heading/fuel) drawn from the top.
        const val TOP_OFFSET = 96f
        const val BAR_WIDTH = 120f
        const val BAR_HEIGHT = 12f
        const val BAR_GAP = 4f
        const val HEALTHY_THRESHOLD = 0.6f
        const val WARN_THRESHOLD = 0.3f
        val TRACK_COLOR = Color(0.15f, 0.15f, 0.18f, 0.8f)
        val HEALTHY_COLOR = Color(0.4f, 0.85f, 0.45f, 1f)
        val WARN_COLOR = Color(0.9f, 0.8f, 0.3f, 1f)
        val CRITICAL_COLOR = Color(0.9f, 0.35f, 0.3f, 1f)
    }
}
