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
import com.orbitalfrontier.render.AtlasRegions
import com.orbitalfrontier.render.GameAssets
import com.orbitalfrontier.render.Palette

/**
 * Scene2D styles for the on-screen controls.
 *
 * UC27: when a shared [GameAssets] atlas is supplied the touchpad (joystick base/knob) and the action-arc
 * glyph buttons are drawn from the **design-system sprites** (AC#2/#3); when it is absent (`null`) they fall
 * back to the original generated solid shapes, so the screens that only use this skin for labels / the
 * settings button (and JVM contexts without a GL atlas) keep working unchanged. The non-sprite styles
 * (settings button, labels) adopt the design-system [Palette] either way (AC#8). The built-in [BitmapFont]
 * is retained (custom fonts deferred).
 *
 * **Texture ownership.** The atlas is **borrowed** — its region drawables are never disposed here. This skin
 * owns and disposes only the generated [Texture]s it creates (the fallback shapes + the settings-button
 * rects) and its [font]. So [dispose] releases the font + retained generated textures, never the shared
 * atlas (UC27 pitfall: single atlas owner, no double-dispose).
 */
class PlaceholderControlsSkin(
    private val gameAssets: GameAssets? = null,
) : Disposable {
    private val textures = ArrayList<Texture>()

    val font: BitmapFont = BitmapFont()

    val touchpadStyle: Touchpad.TouchpadStyle =
        Touchpad.TouchpadStyle().apply {
            if (gameAssets != null) {
                // Borrowed atlas regions — not added to `textures`, so never disposed here (AC#3).
                background = TextureRegionDrawable(gameAssets.region(AtlasRegions.JOYSTICK_BASE))
                knob = TextureRegionDrawable(gameAssets.region(AtlasRegions.JOYSTICK_KNOB))
            } else {
                background = circle(CONTROL_SIZE, Color(1f, 1f, 1f, 0.12f))
                knob = circle(KNOB_SIZE, Color(0.8f, 0.85f, 1f, 0.55f))
            }
        }

    val actionButtonStyle: ImageButton.ImageButtonStyle =
        ImageButton.ImageButtonStyle().apply {
            imageUp = circle(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.30f))
            imageDown = circle(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.6f))
        }

    val settingsButtonStyle: TextButton.TextButtonStyle =
        TextButton.TextButtonStyle().apply {
            font = this@PlaceholderControlsSkin.font
            fontColor = Palette.TEXT_STRONG
            // Design-system steel surfaces (kept slightly translucent so they read over the scene).
            up = rect(Color(Palette.STEEL_600).apply { a = 0.85f })
            down = rect(Color(Palette.STEEL_500).apply { a = 0.95f })
        }

    /** Plain text style for placeholder labels (the in-range dock prompt + station-hub text). */
    val labelStyle: Label.LabelStyle =
        Label.LabelStyle(this@PlaceholderControlsSkin.font, Palette.TEXT_STRONG)

    /** The action-arc glyph buttons (UC26). Each maps to an `action-*` atlas region when art is available. */
    enum class ActionGlyph { FIRE, DOCK, MINE, SCAN, RADIO, POINT_AND_GO }

    /**
     * A circular [ImageButton] style for one arc action. With the atlas (AC#2) it draws the delivered
     * `action-*` glyph sprite; pressed (down) is a **brighter tint of the same region** so a held FIRE/MINE
     * reads as active, while the actual FIRE logic in [ActionCluster] is untouched. Without the atlas it
     * falls back to the original generated glyph. Atlas drawables are borrowed (disposed with the atlas,
     * not here); generated ones are owned by this skin.
     */
    fun actionGlyphStyle(glyph: ActionGlyph): ImageButton.ImageButtonStyle =
        if (gameAssets != null) {
            val region = gameAssets.region(regionFor(glyph))
            ImageButton.ImageButtonStyle().apply {
                imageUp = TextureRegionDrawable(region).tint(GLYPH_UP_TINT)
                imageDown = TextureRegionDrawable(region).tint(GLYPH_DOWN_TINT)
            }
        } else {
            ImageButton.ImageButtonStyle().apply {
                imageUp = circleWithGlyph(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.30f), Color(1f, 1f, 1f, 0.7f), glyph)
                imageDown = circleWithGlyph(ACTION_SIZE, Color(0.7f, 0.75f, 0.9f, 0.6f), Color(1f, 1f, 1f, 0.95f), glyph)
            }
        }

    private fun regionFor(glyph: ActionGlyph): String =
        when (glyph) {
            ActionGlyph.FIRE -> AtlasRegions.ACTION_FIRE
            ActionGlyph.DOCK -> AtlasRegions.ACTION_DOCK
            ActionGlyph.MINE -> AtlasRegions.ACTION_MINE
            ActionGlyph.SCAN -> AtlasRegions.ACTION_SCAN
            ActionGlyph.RADIO -> AtlasRegions.ACTION_RADIO
            ActionGlyph.POINT_AND_GO -> AtlasRegions.ACTION_POINT_AND_GO
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
     * The fallback generated glyph (used only when no atlas is supplied): the shared translucent circle
     * with a simple per-action shape painted on top so the buttons stay tellable apart on a JVM/no-art path.
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

        // Atlas action-glyph tints: rest slightly dimmed, pressed at full brightness so a held button
        // reads as brighter (AC#2) without needing a second sprite.
        val GLYPH_UP_TINT: Color = Color(0.78f, 0.78f, 0.78f, 1f)
        val GLYPH_DOWN_TINT: Color = Color.WHITE
    }
}
