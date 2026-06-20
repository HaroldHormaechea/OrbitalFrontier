package com.orbitalfrontier.render

import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.settings.ControlsLayout
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.ScreenSide

/**
 * Pure, libGDX-free **single source of truth** for the placement of every DRAWN on-screen flight control
 * (UC56). It returns world-unit rectangles ([MinimapLayout.Rect]) for the bottom-corner joystick, the
 * bottom-corner action-arc footprint, the top-left HUD readout block, the top-left Settings ball and the
 * top-right minimap — handedness-aware — so that:
 *
 *  - [com.orbitalfrontier.screen.PlayScreen.layoutControls] positions its live Scene2D actors from THESE
 *    rects (no per-actor literal maths), and
 *  - the UC56 overlap guard asserts pairwise non-overlap across the SAME rects (no mirrored literals).
 *
 * There is deliberately **no PAUSE rect** — UC56 removed the on-screen pause control (pause is reached via
 * the device Back key), so nothing pause-related is drawn or reserved here.
 *
 * Engine-free (no libGDX types) so it stays JVM-testable (ADR 0001). It reuses the established pure
 * geometry sources rather than mirroring them: [HudLayout.blockRect] for the readout block,
 * [MinimapLayout.panelRect] + [MinimapRenderer]'s real panel constants for the minimap, and
 * [ActionCluster.LAYOUT_WIDTH]/[ActionCluster.LAYOUT_HEIGHT] for the arc footprint. Coordinates are world
 * units (the Scene2D stage space = screen pixels / [UiScale.factor]); minimum supported viewport 960×540.
 */
object HudControlLayout {
    /** World-space margin every screen-edge control is inset by (mirror of `PlayScreen.MARGIN`, now sourced here). */
    const val MARGIN = 24f

    /** Movement-joystick footprint (mirror of `PlayScreen.JOYSTICK_SIZE`, now sourced here). */
    const val JOYSTICK_SIZE = 220f

    /** The top-left Settings ball is a circle of this diameter (UC56). */
    const val SETTINGS_BALL_SIZE = 72f

    /** Inset of the Settings ball from the screen's left and top edges (UC56 — gives ball x[24,96] at the 540 floor). */
    const val SETTINGS_BALL_MARGIN = 24f

    /**
     * Width of the Settings ball's caption box (UC56). The caption ("SETTINGS") is rendered as a
     * fixed-width, centre-aligned, wrapping label anchored at the ball's left edge, so its drawn x-span is
     * exactly the ball's x-span — it can never clip the viewport's left edge nor overflow right into the
     * readout block. Equal to [SETTINGS_BALL_SIZE], so the whole control's footprint x-range == the ball's.
     */
    const val SETTINGS_BALL_CAPTION_WIDTH = SETTINGS_BALL_SIZE

    /** World-space band reserved below the ball for the caption (gap + ~one line); part of the drawn footprint. */
    const val SETTINGS_BALL_CAPTION_BAND = 28f

    /** Every DRAWN flight control's world-unit rectangle for one viewport + handedness (no pause). */
    data class Layout(
        val joystick: MinimapLayout.Rect,
        val actionArc: MinimapLayout.Rect,
        val readoutBlock: MinimapLayout.Rect,
        /** The Settings ball ICON rect (tap footprint / positioning anchor). */
        val settingsBall: MinimapLayout.Rect,
        /** The Settings ball's FULL drawn footprint (icon + caption) — what overlap checks must use. */
        val settingsBallFootprint: MinimapLayout.Rect,
        val minimap: MinimapLayout.Rect,
    )

    /**
     * World-space top of the bottom control band: [MARGIN] above the floor plus the taller of the joystick
     * and the action-arc footprint (handedness-agnostic worst case). The minimap reserves its bottom
     * clearance against this. Mirrors `PlayScreen.bottomControlBand()`.
     */
    fun bottomControlBand(): Float = MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

    /**
     * The Settings ball rectangle (UC56): a [SETTINGS_BALL_SIZE]² square anchored [SETTINGS_BALL_MARGIN]
     * from the top-left corner. At the 960×540 floor this is x[24,96] y[444,516] — left of (edge-touching)
     * the inset readout block ([HudLayout.BLOCK_X] = 96), inside the HUD block's vertical band.
     */
    fun settingsBallRect(vpHeight: Float): MinimapLayout.Rect =
        MinimapLayout.Rect(
            SETTINGS_BALL_MARGIN,
            vpHeight - SETTINGS_BALL_MARGIN - SETTINGS_BALL_SIZE,
            SETTINGS_BALL_SIZE,
            SETTINGS_BALL_SIZE,
        )

    /**
     * The Settings ball's FULL drawn footprint (UC56) — the union of the icon and the caption below it.
     * Because the caption is bounded to the ball's x-span (see [SETTINGS_BALL_CAPTION_WIDTH]), the footprint
     * is the icon's x-range extended downward by [SETTINGS_BALL_CAPTION_BAND]. Its right edge is
     * `SETTINGS_BALL_MARGIN + SETTINGS_BALL_CAPTION_WIDTH` (= 96 at the default margin), which the readout
     * block's [HudLayout.BLOCK_X] inset edge-touches and therefore clears. The overlap guard asserts the
     * readout block (and neighbours) against THIS rect — the real drawn footprint, not just the 72px icon.
     */
    fun settingsBallFootprint(vpHeight: Float): MinimapLayout.Rect =
        MinimapLayout.Rect(
            SETTINGS_BALL_MARGIN,
            vpHeight - SETTINGS_BALL_MARGIN - SETTINGS_BALL_SIZE - SETTINGS_BALL_CAPTION_BAND,
            SETTINGS_BALL_CAPTION_WIDTH,
            SETTINGS_BALL_SIZE + SETTINGS_BALL_CAPTION_BAND,
        )

    /** The minimap panel rectangle — pure delegation to [MinimapLayout.panelRect] with the real panel constants. */
    fun minimapRect(
        vpWidth: Float,
        vpHeight: Float,
    ): MinimapLayout.Rect =
        MinimapLayout.panelRect(
            vpWidth = vpWidth,
            vpHeight = vpHeight,
            reservedBottom = bottomControlBand(),
            margin = MinimapRenderer.DEFAULT_MARGIN,
            maxSize = MinimapRenderer.DEFAULT_SIZE,
            minSize = MinimapRenderer.MIN_SIZE,
            gap = MinimapRenderer.CONTROL_GAP,
        )

    /** Compute every drawn control's rect for [vpWidth]×[vpHeight] world units under [handedness]. */
    fun compute(
        vpWidth: Float,
        vpHeight: Float,
        handedness: Handedness,
    ): Layout {
        val controls = ControlsLayout.forHandedness(handedness)
        val joystick =
            MinimapLayout.Rect(
                sideX(controls.movementStickSide, vpWidth, JOYSTICK_SIZE),
                MARGIN,
                JOYSTICK_SIZE,
                JOYSTICK_SIZE,
            )
        val actionArc =
            MinimapLayout.Rect(
                sideX(controls.actionClusterSide, vpWidth, ActionCluster.LAYOUT_WIDTH),
                MARGIN,
                ActionCluster.LAYOUT_WIDTH,
                ActionCluster.LAYOUT_HEIGHT,
            )
        return Layout(
            joystick = joystick,
            actionArc = actionArc,
            readoutBlock = HudLayout.blockRect(vpWidth, vpHeight),
            settingsBall = settingsBallRect(vpHeight),
            settingsBallFootprint = settingsBallFootprint(vpHeight),
            minimap = minimapRect(vpWidth, vpHeight),
        )
    }

    /** Left-edge X for a [side]-anchored widget of [widgetWidth] in a [vpWidth]-wide viewport (mirror of `PlayScreen.sideX`). */
    fun sideX(
        side: ScreenSide,
        vpWidth: Float,
        widgetWidth: Float,
    ): Float = if (side == ScreenSide.LEFT) MARGIN else vpWidth - MARGIN - widgetWidth
}
