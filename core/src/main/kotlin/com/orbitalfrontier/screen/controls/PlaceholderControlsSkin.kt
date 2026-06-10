package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Touchpad
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable

/**
 * Programmatically-generated Scene2D styles for the placeholder on-screen controls.
 *
 * There is no art/skin asset pipeline yet (use-case 01 is structure + behaviour), so the
 * touchpad, action buttons and settings button are drawn from generated solid shapes plus the
 * built-in [BitmapFont]. Owns every [Texture] and the font it creates and disposes them all.
 */
class PlaceholderControlsSkin : Disposable {
    private val textures = ArrayList<Texture>()

    val font: BitmapFont = BitmapFont()

    val touchpadStyle: Touchpad.TouchpadStyle =
        Touchpad.TouchpadStyle().apply {
            background = circle(CONTROL_SIZE, Color(1f, 1f, 1f, 0.12f))
            knob = circle(KNOB_SIZE, Color(0.8f, 0.85f, 1f, 0.55f))
        }

    val actionButtonStyle: ImageButton.ImageButtonStyle =
        ImageButton.ImageButtonStyle().apply {
            imageUp = circle(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.30f))
            imageDown = circle(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.6f))
        }

    val settingsButtonStyle: TextButton.TextButtonStyle =
        TextButton.TextButtonStyle().apply {
            font = this@PlaceholderControlsSkin.font
            fontColor = Color.WHITE
            up = rect(Color(0.2f, 0.22f, 0.3f, 0.7f))
            down = rect(Color(0.35f, 0.38f, 0.5f, 0.85f))
        }

    /** Plain white text style for placeholder labels (the in-range dock prompt + station-hub text). */
    val labelStyle: Label.LabelStyle =
        Label.LabelStyle(this@PlaceholderControlsSkin.font, Color.WHITE)

    /**
     * The generated placeholder glyph drawn inside each circular arc-action button (UC26). There is no
     * art pipeline yet (use-case clarification), so each action gets a cheap, distinct shape drawn over
     * the same translucent circle — enough to tell the buttons apart on-device.
     */
    enum class ActionGlyph { FIRE, DOCK, MINE, SCAN, RADIO, POINT_AND_GO }

    /**
     * A circular [ImageButton] style for one arc action: the shared translucent circle (matching
     * [actionButtonStyle]) with a generated per-action [glyph] drawn on top. The pressed (down) variant
     * brightens both the circle and the glyph so a held FIRE/MINE reads as active. Owns its textures
     * (disposed with the skin).
     */
    fun actionGlyphStyle(glyph: ActionGlyph): ImageButton.ImageButtonStyle =
        ImageButton.ImageButtonStyle().apply {
            imageUp = circleWithGlyph(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.30f), Color(1f, 1f, 1f, 0.7f), glyph)
            imageDown = circleWithGlyph(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.6f), Color(1f, 1f, 1f, 0.95f), glyph)
        }

    private fun circle(
        diameter: Int,
        color: Color,
    ): Drawable {
        val pixmap = Pixmap(diameter, diameter, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(color)
            pixmap.fillCircle(diameter / 2, diameter / 2, diameter / 2 - 1)
            return drawableFrom(pixmap)
        } finally {
            pixmap.dispose()
        }
    }

    /**
     * The shared translucent circle of [circle] with a generated [glyph] painted on top in [glyphColor].
     * Pure pixmap drawing (no art assets) — a distinct simple shape per action so the buttons are
     * tellable apart; the exact iconography is placeholder and can be replaced when an art pipeline lands.
     */
    private fun circleWithGlyph(
        diameter: Int,
        circleColor: Color,
        glyphColor: Color,
        glyph: ActionGlyph,
    ): Drawable {
        val pixmap = Pixmap(diameter, diameter, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(circleColor)
            pixmap.fillCircle(diameter / 2, diameter / 2, diameter / 2 - 1)
            pixmap.setColor(glyphColor)
            val c = diameter / 2
            val g = (diameter * 0.22f).toInt()
            val t = (diameter * 0.05f).toInt().coerceAtLeast(2) // glyph stroke half-thickness
            when (glyph) {
                // Upward triangle — a projectile / "fire" arrow.
                ActionGlyph.FIRE -> pixmap.fillTriangle(c, c - g, c - g, c + g, c + g, c + g)
                // Filled square — a docking bay.
                ActionGlyph.DOCK -> pixmap.fillRectangle(c - g, c - g, 2 * g, 2 * g)
                // Diamond (two triangles) — an ore facet.
                ActionGlyph.MINE -> {
                    pixmap.fillTriangle(c, c - g, c - g, c, c + g, c)
                    pixmap.fillTriangle(c - g, c, c + g, c, c, c + g)
                }
                // Concentric outline rings — a sensor sweep.
                ActionGlyph.SCAN -> {
                    pixmap.drawCircle(c, c, g)
                    pixmap.drawCircle(c, c, g - 1)
                    pixmap.drawCircle(c, c, g / 2)
                }
                // Dot with a ring — a broadcast.
                ActionGlyph.RADIO -> {
                    pixmap.fillCircle(c, c, g / 3)
                    pixmap.drawCircle(c, c, g)
                    pixmap.drawCircle(c, c, g - 1)
                }
                // Plus — a navigation crosshair.
                ActionGlyph.POINT_AND_GO -> {
                    pixmap.fillRectangle(c - g, c - t, 2 * g, 2 * t)
                    pixmap.fillRectangle(c - t, c - g, 2 * t, 2 * g)
                }
            }
            return drawableFrom(pixmap)
        } finally {
            pixmap.dispose()
        }
    }

    private fun rect(color: Color): Drawable {
        val pixmap = Pixmap(RECT_SIZE, RECT_SIZE, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(color)
            pixmap.fill()
            return drawableFrom(pixmap)
        } finally {
            pixmap.dispose()
        }
    }

    private fun drawableFrom(pixmap: Pixmap): Drawable {
        val texture = Texture(pixmap)
        textures.add(texture)
        return TextureRegionDrawable(TextureRegion(texture))
    }

    override fun dispose() {
        font.dispose()
        textures.forEach { it.dispose() }
        textures.clear()
    }

    private companion object {
        const val CONTROL_SIZE = 220
        const val KNOB_SIZE = 96
        const val ACTION_SIZE = 120
        const val RECT_SIZE = 16
    }
}
