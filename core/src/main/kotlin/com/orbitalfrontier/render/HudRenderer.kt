package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
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
 */
class HudRenderer : Disposable {
    private val batch = SpriteBatch()
    private val font = BitmapFont()
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
    ) {
        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        batch.projectionMatrix = projection
        batch.begin()

        line.setLength(0)
        line.append("SPEED ").append(speed.roundToInt())
        font.draw(batch, line, MARGIN, viewportHeight - MARGIN)

        line.setLength(0)
        line.append("HDG ").append(normalizeDegrees(headingRadians)).append(DEGREE)
        font.draw(batch, line, MARGIN, viewportHeight - MARGIN - LINE_HEIGHT)

        line.setLength(0)
        line.append("FUEL ").append(fuelLevel.roundToInt()).append('/').append(fuelCapacity.roundToInt())
        if (lowFuel) line.append("  LOW")
        // Red cue while low; reset to white afterwards so other lines stay neutral next frame.
        font.color = if (lowFuel) Color.RED else Color.WHITE
        font.draw(batch, line, MARGIN, viewportHeight - MARGIN - LINE_HEIGHT * 2f)
        font.color = Color.WHITE

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
    }
}
