package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.orbitalfrontier.render.GameFont
import com.orbitalfrontier.render.HudControlLayout

/**
 * UC56: a reusable **ball control** — a single circular icon button with a small caption drawn just below
 * it — following the same icon-plus-label idiom as the [ActionCluster] arc buttons. Used for the in-flight
 * top-left Settings ball.
 *
 * The backing [actor] is a [Group] sized exactly to the [HudControlLayout.SETTINGS_BALL_SIZE]² ball (so the
 * tappable footprint is the ball itself); the caption is a decorative child placed *below* the group and is
 * [Touchable.disabled] so it never steals a tap from the button. The screen positions [actor] from
 * [HudControlLayout].
 *
 * **Caption footprint is bounded to the ball's x-span (UC56 fix).** The ball hugs the screen's top-left
 * corner, so a caption wider than the ball would clip the viewport's left edge and/or overflow right into
 * the inset HUD readout block. To make that impossible *by construction* (not by hand-tuned centring), the
 * caption is a FIXED-WIDTH, wrapping, centre-aligned label whose box width equals the ball width
 * ([HudControlLayout.SETTINGS_BALL_CAPTION_WIDTH] = the ball size): the text can never extend past the
 * ball's own x-span, so the whole control's drawn footprint is exactly the ball's x-range
 * ([HudControlLayout.settingsBallFootprint]) — which the readout block's [com.orbitalfrontier.render.HudLayout.BLOCK_X]
 * inset already clears. The font scale is authored as `GameFont.NORM × <factor>` (NOT a bare absolute, which
 * would *override* the skin font's NORM downscale and render huge), so the caption stays small UI text.
 *
 * Pure Scene2D view — it owns no disposable resources of its own (the [OrbitalUiSkin] generated textures are
 * owned + disposed by the skin); the [onTap] callback is invoked on click.
 */
class BallButton(
    skin: OrbitalUiSkin,
    caption: String,
    private val onTap: () -> Unit,
) {
    val actor: Group = Group()

    private val button = ImageButton(skin.settingsBallStyle())
    private val label =
        Label(caption, skin.smallLabelStyle).apply {
            setAlignment(Align.center)
            touchable = Touchable.disabled
            // `GameFont.NORM × factor` — relative to the skin font's NORM downscale, so this is a SMALL
            // caption (a bare absolute scale here would override NORM and render at full master size).
            setFontScale(GameFont.NORM * CAPTION_FONT_FACTOR)
            setWrap(true)
        }

    init {
        actor.setSize(SIZE, SIZE)
        actor.isTransform = false

        button.setBounds(0f, 0f, SIZE, SIZE)
        button.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onTap()
                }
            },
        )

        // Fixed-width, centre-aligned, wrapping caption box == the ball width, anchored at the ball's left
        // edge (local x = 0). The text is centred within that box and can never spill past it, so the
        // caption's x-span is exactly the ball's — clearing both the viewport's left edge and the readout
        // block. Placed just below the ball (negative y renders fine; Scene2D doesn't clip to parent bounds).
        label.width = CAPTION_WIDTH
        label.height = label.prefHeight
        label.setPosition(0f, -(label.height + CAPTION_GAP))

        actor.addActor(button)
        actor.addActor(label)
    }

    /** Show/hide the whole control (an invisible Scene2D actor also receives no touch). */
    var isVisible: Boolean
        get() = actor.isVisible
        set(value) {
            actor.isVisible = value
        }

    private companion object {
        const val SIZE = HudControlLayout.SETTINGS_BALL_SIZE
        const val CAPTION_WIDTH = HudControlLayout.SETTINGS_BALL_CAPTION_WIDTH

        // Fraction of the skin font's NORM size for the caption. NORM × 0.7 ≈ 0.219 absolute; at that size
        // "SETTINGS" fits on one line within the ball width, and anything wider simply wraps within the box.
        const val CAPTION_FONT_FACTOR = 0.7f
        const val CAPTION_GAP = 4f
    }
}
