package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.combat.SectionDamage
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection

/**
 * The HUD **ship schematic** (UC13 AC#3): a small vertical stack of per-section module blocks — one per
 * [ShipSection] — shown only while a combat encounter is live, so the player sees which components are
 * damaged. UC27 (AC#6): each block is the design-system `module-healthy` / `module-warn` / `module-critical`
 * sprite, picked by the section's current-HP fraction (healthy → warn → critical as it drops; a destroyed
 * section reads as critical).
 *
 * Drawn in screen space with a [SpriteBatch] (mirrors [HudRenderer]'s screen-space draw). The shared
 * [GameAssets] atlas is **borrowed** (never disposed here); the three state regions are resolved once at
 * construction. It only **reads** the damage + derived max HP (render reads state).
 *
 * [uiScale] (ADR 0015) magnifies the schematic: every base layout constant (margin, block size, gaps) is
 * multiplied by it at its use site, and the top offset matches HudRenderer's scaled three text lines so the
 * schematic still sits just below them. Constants stay authored at base (×1) — [UiScale.factor] is the knob.
 */
class ShipSchematicRenderer(
    private val assets: GameAssets,
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val batch = SpriteBatch()
    private val projection = Matrix4()

    private val healthyRegion: TextureRegion = assets.region(AtlasRegions.MODULE_HEALTHY)
    private val warnRegion: TextureRegion = assets.region(AtlasRegions.MODULE_WARN)
    private val criticalRegion: TextureRegion = assets.region(AtlasRegions.MODULE_CRITICAL)

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
        batch.projectionMatrix = projection

        // Base layout constants scaled at the use site — UiScale.factor stays the single knob.
        val left = MARGIN * uiScale
        val blockWidth = BLOCK_WIDTH * uiScale
        val blockHeight = BLOCK_HEIGHT * uiScale
        val blockGap = BLOCK_GAP * uiScale
        var top = viewportHeight - TOP_OFFSET * uiScale

        batch.begin()
        batch.color = Color.WHITE
        for (section in ShipSection.entries) {
            val maxHp = maxSectionHp[section] ?: 0
            val fraction =
                if (maxHp <= 0) 0f else SectionDamages.currentHp(sectionDamage, section, maxHp).toFloat() / maxHp
            batch.draw(regionFor(fraction), left, top - blockHeight, blockWidth, blockHeight)
            top -= blockHeight + blockGap
        }
        batch.end()
    }

    private fun regionFor(fraction: Float): TextureRegion =
        when {
            fraction > HEALTHY_THRESHOLD -> healthyRegion
            fraction > WARN_THRESHOLD -> warnRegion
            else -> criticalRegion
        }

    override fun dispose() {
        batch.dispose()
    }

    private companion object {
        const val MARGIN = 16f

        // Below the three HUD text lines (speed/heading/fuel) drawn from the top.
        const val TOP_OFFSET = 96f
        const val BLOCK_WIDTH = 120f
        const val BLOCK_HEIGHT = 16f
        const val BLOCK_GAP = 4f
        const val HEALTHY_THRESHOLD = 0.6f
        const val WARN_THRESHOLD = 0.3f
    }
}
