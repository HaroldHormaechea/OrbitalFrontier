package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Touchpad
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.render.AtlasRegions
import com.orbitalfrontier.render.GameAssets
import com.orbitalfrontier.render.GameFont
import com.orbitalfrontier.render.GameFontLoader
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.TextScale

/**
 * The finished design-system UI skin for every Scene2D widget in the game (UC29). It is the single
 * styling source for buttons, panels, labels and scroll panes across all screens (MainMenu, StationHub,
 * Trade, Shipyard, Outfit, Hire, MissionBoard, Play, StationWalkaround); the "placeholder" naming and the
 * old flat-fill programmer-art are retired (it is the direct rename of `PlaceholderControlsSkin`).
 *
 * **Button / panel chrome is built at runtime from the [Palette] as bevelled, bordered nine-patches**
 * (ADR 0018). Each button state — `up`, `down`, `disabled`, `over`, `focused` — has its own
 * palette-driven drawable, so pressed / disabled / focused states are styled (AC#4), not just the
 * default; the `panel` nine-patch gives every screen root a finished bevelled surface (AC#1). Because the
 * chrome is generated on the GL thread (never on the JVM test backend), this skin is only ever
 * constructed by a live screen — exactly as before.
 *
 * UC27 (unchanged): when a shared [GameAssets] atlas is supplied the touchpad (joystick base/knob) and the
 * action-arc glyph buttons are drawn from the **design-system sprites** (AC#2/#3 of UC27); when it is
 * absent (`null`) they fall back to the original generated solid shapes, so the screens that only use this
 * skin for labels / buttons (and JVM contexts without a GL atlas) keep working unchanged. The action-arc
 * and joystick geometry is untouched by UC29.
 *
 * UC28: the label/button text is the bundled game font ([GameFont], a [BitmapFont] loaded by
 * [GameFontLoader]). The Scene2D screens render through a ×[com.orbitalfrontier.render.UiScale.factor]
 * viewport (ADR 0015), so the font is downscaled by [GameFont.NORM] here (NO extra uiScale — the viewport
 * already magnifies).
 *
 * UC39 (AC#2): the font is additionally scaled by the global [TextScale.factor] — the accessibility
 * text-size knob, applied **on top of** the UI scale so the player can enlarge UI text independently of the
 * chrome. The scale is captured at construction; a live change re-applies via [applyTextScale] (the host
 * settings surface calls it and re-flows its layout). The in-flight HUD/world-space text is deliberately
 * NOT affected (it follows UiScale only); see [TextScale].
 *
 * **Texture ownership.** The atlas is **borrowed** — its region drawables are never disposed here. This
 * skin owns and disposes only the generated [Texture]s it creates (the nine-patch chrome + the fallback
 * shapes) and its [font]. So [dispose] releases the font + retained generated textures, never the shared
 * atlas (UC27 pitfall: single atlas owner, no double-dispose).
 */
class OrbitalUiSkin(
    private val gameAssets: GameAssets? = null,
) : Disposable {
    private val textures = ArrayList<Texture>()

    val font: BitmapFont = GameFontLoader.load().apply { data.setScale(GameFont.NORM * TextScale.factor) }

    /**
     * UC39: re-apply the global [TextScale.factor] to the skin [font] after a live text-size change, so the
     * already-built widgets pick up the new size without rebuilding the skin. The host calls this (then
     * re-flows its layout / invalidates the stage) right after [TextScale.set]. Idempotent.
     */
    fun applyTextScale() {
        font.data.setScale(GameFont.NORM * TextScale.factor)
    }

    val touchpadStyle: Touchpad.TouchpadStyle =
        Touchpad.TouchpadStyle().apply {
            if (gameAssets != null) {
                // Borrowed atlas regions — not added to `textures`, so never disposed here (UC27 AC#3).
                background = TextureRegionDrawable(gameAssets.region(AtlasRegions.JOYSTICK_BASE))
                knob = TextureRegionDrawable(gameAssets.region(AtlasRegions.JOYSTICK_KNOB))
            } else {
                background = circle(CONTROL_SIZE, Color(1f, 1f, 1f, 0.12f))
                knob = circle(KNOB_SIZE, Color(0.8f, 0.85f, 1f, 0.55f))
            }
        }

    /**
     * The finished button style (UC29 AC#1/#4): a palette-driven nine-patch per interaction state.
     *  - `up` — STEEL_600 fill, STEEL_400 hairline border + a top/left bevel highlight;
     *  - `down` — STEEL_500 fill with an AMBER accent edge so a held button reads as active;
     *  - `disabled` — muted VOID_700 fill, STEEL_300 border (drives MainMenu's greyed CONTINUE, AC#4);
     *  - `over` / `focused` — STEEL_600 fill with an AMBER_500 border highlight (desktop/hover + keyboard).
     * Plus per-state font colours: strong text up, amber when pressed, muted steel when disabled.
     */
    val buttonStyle: TextButton.TextButtonStyle =
        TextButton.TextButtonStyle().apply {
            font = this@OrbitalUiSkin.font
            fontColor = Palette.TEXT_STRONG
            downFontColor = Palette.AMBER_300
            disabledFontColor = Palette.STEEL_300
            up = bevelPatch(Palette.STEEL_600, Palette.STEEL_400, accentEdge = null)
            down = bevelPatch(Palette.STEEL_500, Palette.STEEL_400, accentEdge = Palette.AMBER_500)
            disabled = bevelPatch(Palette.VOID_700, Palette.STEEL_300, accentEdge = null)
            over = bevelPatch(Palette.STEEL_600, Palette.AMBER_500, accentEdge = null)
            focused = bevelPatch(Palette.STEEL_600, Palette.AMBER_500, accentEdge = null)
        }

    /**
     * Source-compatibility alias for the historical name (`PlaceholderControlsSkin.settingsButtonStyle`).
     * Every TextButton call site keeps working unchanged; it is the same finished [buttonStyle].
     */
    val settingsButtonStyle: TextButton.TextButtonStyle get() = buttonStyle

    /**
     * Bevelled panel background for screen roots (AC#1): a VOID_800 fill with a STEEL_400 border nine-patch.
     * **Zero pad insets** — the drawable contributes no layout padding, so it cannot perturb the
     * width-budget screens or the UC20 station-menu grid (it only paints; the screens own their own pad).
     */
    val panel: Drawable =
        bevelPatch(Palette.VOID_800, Palette.STEEL_400, accentEdge = null).apply {
            leftWidth = 0f
            rightWidth = 0f
            topHeight = 0f
            bottomHeight = 0f
        }

    /** Plain body-text label style (the dock prompt, station-hub readouts, list rows). */
    val labelStyle: Label.LabelStyle =
        Label.LabelStyle(this@OrbitalUiSkin.font, Palette.TEXT_BODY)

    /** Title/heading label style (AC#1): the brand AMBER accent, for screen titles and section headers. */
    val titleLabelStyle: Label.LabelStyle =
        Label.LabelStyle(this@OrbitalUiSkin.font, Palette.AMBER_400)

    /**
     * UC56: a small-caption label style for the text under a [BallButton] (e.g. the "SETTINGS" caption below
     * the in-flight Settings ball). It shares the same body font as [labelStyle]; the smaller visual size is
     * applied by the widget via the label's own font scale ([BallButton]), so no second font texture is
     * baked. Same colour as [labelStyle] so captions read as ordinary HUD chrome.
     */
    val smallLabelStyle: Label.LabelStyle =
        Label.LabelStyle(this@OrbitalUiSkin.font, Palette.TEXT_BODY)

    /**
     * Palette-driven scroll-pane style (AC#1). No screen mounts a [ScrollPane] today, so this is a
     * **currently-unused API** kept so the finished skin covers every Scene2D widget kind the design
     * system specifies; it is ready for the first scrollable list without re-theming.
     */
    val scrollPaneStyle: ScrollPane.ScrollPaneStyle =
        ScrollPane.ScrollPaneStyle().apply {
            background = panel
            hScroll = bevelPatch(Palette.STEEL_600, Palette.STEEL_400, accentEdge = null)
            hScrollKnob = bevelPatch(Palette.STEEL_300, Palette.STEEL_400, accentEdge = null)
            vScroll = bevelPatch(Palette.STEEL_600, Palette.STEEL_400, accentEdge = null)
            vScrollKnob = bevelPatch(Palette.STEEL_300, Palette.STEEL_400, accentEdge = null)
        }

    /** The action-arc glyph buttons (UC26). Each maps to an `action-*` atlas region when art is available. */
    enum class ActionGlyph { FIRE, DOCK, MINE, SCAN, RADIO, POINT_AND_GO }

    /**
     * A circular [ImageButton] style for one arc action. With the atlas (UC27 AC#2) it draws the delivered
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

    /**
     * UC56: a circular [ImageButton] style for the top-left in-flight **Settings ball**. There is no atlas
     * region for it, so it is always the generated glyph — a translucent steel disc with a "sliders" mark
     * (three horizontal bars + knobs, the conventional settings/tune icon) painted on top. Pressed (down) is
     * the same mark on a brighter disc so a held tap reads as active. The generated textures are owned by
     * this skin and released in [dispose]. ASCII/art-free, so it works on every (incl. headless) path.
     */
    fun settingsBallStyle(): ImageButton.ImageButtonStyle =
        ImageButton.ImageButtonStyle().apply {
            imageUp = settingsBall(SETTINGS_BALL_PX, Color(0.62f, 0.68f, 0.82f, 0.34f), Color(0.92f, 0.95f, 1f, 0.85f))
            imageDown = settingsBall(SETTINGS_BALL_PX, Color(0.62f, 0.68f, 0.82f, 0.62f), Color(1f, 1f, 1f, 0.98f))
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

    /**
     * Build one bevelled, bordered nine-patch drawable in the [fill] colour with a [border] hairline and a
     * top/left bevel highlight, optionally adding an [accentEdge] strip along the bottom (the pressed-state
     * amber). The patch's stretchable centre is a single texel column/row, so the corners (border + bevel +
     * accent) never distort however large the button/panel is drawn — a small generated texture stretched
     * cleanly (ADR 0018; same runtime-pixmap approach the skin already used for its fallback shapes). The
     * texture is owned by this skin and released in [dispose].
     */
    private fun bevelPatch(
        fill: Color,
        border: Color,
        accentEdge: Color?,
    ): NinePatchDrawable {
        val size = PATCH_SIZE
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        try {
            // Base fill.
            pixmap.setColor(fill)
            pixmap.fill()
            // Hairline border on every edge.
            pixmap.setColor(border)
            for (t in 0 until BORDER_PX) {
                pixmap.drawRectangle(t, t, size - 2 * t, size - 2 * t)
            }
            // Bevel highlight just inside the top + left edges (a lighter steel), reading as a raised face.
            pixmap.setColor(BEVEL_HIGHLIGHT)
            val inner = BORDER_PX
            pixmap.drawLine(inner, inner, size - 1 - inner, inner) // top
            pixmap.drawLine(inner, inner, inner, size - 1 - inner) // left
            // Optional accent edge along the bottom (pressed state).
            if (accentEdge != null) {
                pixmap.setColor(accentEdge)
                for (t in 0 until ACCENT_PX) {
                    pixmap.drawLine(0, size - 1 - t, size - 1, size - 1 - t)
                }
            }
            val texture = Texture(pixmap)
            textures.add(texture)
            // Splits at PATCH_CORNER preserve the bevel/border/accent corners; the 1-texel centre stretches.
            return NinePatchDrawable(NinePatch(texture, PATCH_CORNER, PATCH_CORNER, PATCH_CORNER, PATCH_CORNER))
        } finally {
            pixmap.dispose()
        }
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

    /**
     * UC56: the generated Settings-ball glyph — a translucent steel disc with a "sliders" mark on top (three
     * horizontal bars, each with a small knob at a staggered x), the conventional settings/tune icon. Drawn
     * with the same runtime-pixmap approach as [circleWithGlyph]; the texture is owned by this skin.
     */
    private fun settingsBall(
        diameter: Int,
        discColor: Color,
        markColor: Color,
    ): Drawable {
        val pixmap = Pixmap(diameter, diameter, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(discColor)
            pixmap.fillCircle(diameter / 2, diameter / 2, diameter / 2 - 1)

            pixmap.setColor(markColor)
            val c = diameter / 2
            val half = (diameter * 0.26f).toInt() // half-length of each slider bar
            val bar = (diameter * 0.04f).toInt().coerceAtLeast(2) // bar half-thickness
            val knob = (diameter * 0.07f).toInt().coerceAtLeast(2) // knob radius
            val rowGap = (diameter * 0.18f).toInt()
            // Three staggered slider rows (top knob right, middle knob left, bottom knob right).
            val rows = listOf(c - rowGap to (c + half - knob), c to (c - half + knob), c + rowGap to (c + half - knob))
            for ((y, knobX) in rows) {
                pixmap.fillRectangle(c - half, y - bar, 2 * half, 2 * bar)
                pixmap.fillCircle(knobX, y, knob)
            }
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

    /**
     * UC31: attach a stage-wide listener that plays the shared UI-tap cue on a button tap. ChangeEvents
     * bubble from the originating widget to the stage root, so one listener here covers every button on
     * the screen. It fires ONLY when the event's target is a [Button] (TextButton/ImageButton), so a
     * ScrollPane scroll or other ChangeEvent doesn't machine-gun the cue. The actual sound is played
     * through the static [uiTapSound] hook the app wires to [com.orbitalfrontier.platform.AudioService],
     * so screens stay decoupled from the audio port. Install on UI/menu screens; the play screen is
     * intentionally excluded so its gameplay buttons keep their own cues (dock/fire/…).
     */
    fun installTapSound(stage: Stage) {
        stage.root.addListener(
            object : ChangeListener() {
                override fun changed(
                    event: ChangeEvent,
                    actor: Actor,
                ) {
                    if (event.target is Button) uiTapSound?.invoke()
                }
            },
        )
    }

    companion object {
        /**
         * UC31: global UI-tap audio hook. The app sets this once (to play `Sfx.UI_TAP` through the audio
         * service) and every screen's [installTapSound] invokes it; null (the default) makes UI taps
         * silent, which is the correct behaviour in headless/JVM contexts that never wire audio.
         */
        var uiTapSound: (() -> Unit)? = null

        const val CONTROL_SIZE = 220
        const val KNOB_SIZE = 96
        const val ACTION_SIZE = 120

        // UC56: pixel resolution of the generated Settings-ball glyph texture. Sized so the disc + sliders
        // mark stay crisp when the 72-world-unit ball is drawn through the ×2 UI viewport (ADR 0015).
        const val SETTINGS_BALL_PX = 144

        // Nine-patch chrome metrics (UC29 AC#5 — pinned as named constants so QA can guard them). The
        // patch is a small generated texture; PATCH_CORNER < PATCH_SIZE / 2 leaves a 1-texel stretchable
        // centre, so the bevel/border/accent corners are preserved at every drawn size. Sized small and
        // conservative: the ×2 UI viewport (ADR 0015) magnifies these on screen, so a thin authored border
        // stays crisp and a panel adds no perceptible inset at the smallest supported screen.
        const val PATCH_SIZE = 24
        const val PATCH_CORNER = 8
        const val BORDER_PX = 2
        const val ACCENT_PX = 3

        /** Bevel highlight (a lighter steel than any fill), painted inside the top/left edges. */
        val BEVEL_HIGHLIGHT: Color = Palette.STEEL_200

        // Atlas action-glyph tints: rest slightly dimmed, pressed at full brightness so a held button
        // reads as brighter (UC27 AC#2) without needing a second sprite.
        val GLYPH_UP_TINT: Color = Color(0.78f, 0.78f, 0.78f, 1f)
        val GLYPH_DOWN_TINT: Color = Color.WHITE
    }
}
