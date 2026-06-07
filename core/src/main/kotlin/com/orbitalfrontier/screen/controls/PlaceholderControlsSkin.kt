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
