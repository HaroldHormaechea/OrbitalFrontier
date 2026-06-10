package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Minimal heads-up display: current speed, heading and fuel, updated every frame (AC#9; UC07 AC#3).
 *
 * Heading is converted radians→degrees and normalized to [0, 360). Uses libGDX's built-in
 * [BitmapFont] (no asset pipeline yet) drawn in screen space. A reusable [StringBuilder] and
 * integer formatting avoid per-frame String/`String.format` allocation, protecting the 60 FPS
 * budget (AC#14, coding-guidelines § performance). The fuel line turns red and appends "LOW" while
 * the tank is below the low-fuel threshold — the player-facing cue that speed is being limited.
 *
 * [uiScale] (ADR 0015) magnifies the whole overlay: the built-in font is scaled by it once at
 * construction and every base layout constant (margins, line height) is multiplied by it at its use
 * site — the constants stay authored at their base (×1) values so [UiScale.factor] remains the single
 * knob. The font is bitmap, so [BitmapFont.getData].setScale bilinearly stretches it (slightly blurry);
 * acceptable as a placeholder until a real scaled font/atlas exists.
 */
class HudRenderer(
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val batch = SpriteBatch()
    private val font =
        BitmapFont().apply {
            data.setScale(uiScale)
            color = TEXT_COLOR
        }
    private val line = StringBuilder(24)
    private val projection = Matrix4()

    fun render(
        speed: Float,
        headingRadians: Float,
        fuelLevel: Float,
        fuelCapacity: Float,
        lowFuel: Boolean,
        viewportWidth: Float,
        viewportHeight: Float,
        inCombat: Boolean = false,
    ) {
        // Base layout constants scaled at the use site — UiScale.factor stays the single knob.
        val margin = MARGIN * uiScale
        val lineHeight = LINE_HEIGHT * uiScale

        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        batch.projectionMatrix = projection
        batch.begin()

        line.setLength(0)
        line.append("SPEED ").append(speed.roundToInt())
        font.draw(batch, line, margin, viewportHeight - margin)

        line.setLength(0)
        line.append("HDG ").append(normalizeDegrees(headingRadians)).append(DEGREE)
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight)

        line.setLength(0)
        line.append("FUEL ").append(fuelLevel.roundToInt()).append('/').append(fuelCapacity.roundToInt())
        if (lowFuel) line.append("  LOW")
        // UC27: design-system tokens — amber "warning" caution while low, reset to the steel readout text
        // colour afterwards so other lines stay neutral next frame (AC#8).
        font.color = if (lowFuel) Palette.WARNING else TEXT_COLOR
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * 2f)
        font.color = TEXT_COLOR

        // UC13: an "IN COMBAT" cue while an encounter is live, drawn alongside the per-section ship
        // schematic ([com.orbitalfrontier.render.ShipSchematicRenderer]); the schematic carries the detail.
        // UC27: the design-system "danger" signal colour (AC#8).
        if (inCombat) {
            line.setLength(0)
            line.append("IN COMBAT")
            font.color = Palette.DANGER
            font.draw(batch, line, margin, viewportHeight - margin - lineHeight * 3f)
            font.color = TEXT_COLOR
        }

        batch.end()
    }

    private fun normalizeDegrees(radians: Float): Int {
        var degrees = (radians * 180f / PI.toFloat()).roundToInt() % 360
        if (degrees < 0) degrees += 360
        return degrees
    }

    override fun dispose() {
        batch.dispose()
        font.dispose()
    }

    private companion object {
        const val MARGIN = 16f
        const val LINE_HEIGHT = 22f

        // Degree sign; the placeholder built-in font may render it as a blank — acceptable for now.
        const val DEGREE = '°'

        // UC27: high-emphasis steel readout colour for normal HUD text (AC#8); status lines override
        // with the amber "warning" / red "danger" signal tokens at their use site.
        val TEXT_COLOR = Palette.STEEL_050
    }
}
