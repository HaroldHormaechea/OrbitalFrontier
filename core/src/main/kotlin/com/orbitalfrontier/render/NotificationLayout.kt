package com.orbitalfrontier.render

import com.orbitalfrontier.screen.controls.ActionCluster

/**
 * Pure, libGDX-free geometry for the transient notification toasts (UC35 AC#4).
 *
 * Mirrors the established pure `*Layout` pattern ([HudLayout]/[MinimapLayout]) — world-unit rectangles,
 * no engine types — so the placement is JVM-testable and a guard can assert the toasts never collide with
 * the other reserved HUD regions without a GL context.
 *
 * The toasts occupy a **TOP-CENTRE band that stacks downward**, deliberately threaded through the clear gap
 * between the four things that own the screen edges:
 *  - the top-LEFT HUD readout block (its right edge `HudLayout.BLOCK_X + HudLayout.BLOCK_WIDTH`) — the band's
 *    left edge sits a [SIDE_GAP] to its right, so a toast never runs under the readouts. UC56 inset the block
 *    by [HudLayout.BLOCK_X] (to clear the new Settings ball), so the band tracks the block's real right edge
 *    rather than [HudLayout.BLOCK_WIDTH] alone;
 *  - the top-RIGHT minimap (UC22) — [RIGHT_RESERVED] reserves the minimap's worst-case width on the right;
 *  - the top-CENTRE UC32 pause button — [TOP_INSET] starts the first toast *below* it; and
 *  - the bottom-corner action arc (UC26) — which, at the smallest supported viewport, is tall enough
 *    ([ActionCluster.LAYOUT_HEIGHT]) that its top edge reaches *up into* the lower toast rows. The arc is
 *    handedness-dependent: a left-handed arc sits entirely left of the band, but a **right-handed arc juts
 *    up the right side and into the band**. So a toast row whose bottom edge dips below the arc's top
 *    ([arc top][ActionCluster.LAYOUT_HEIGHT] + the control margin) narrows its right edge to clear the
 *    worst-case right arc; higher rows sit fully above the arc and keep the full centre band. (Earlier this
 *    claimed the arc "lives far below, so the band clears it by construction" — that was FALSE at the
 *    960×540 floor with three rows visible, where toast[2] overlapped the right arc; this per-row clearance
 *    is the fix, encoded in the AC#4 layout test.)
 *
 * At the smallest supported viewport the centre gap narrows; rather than overrun a neighbour the toast
 * **width shrinks to the available band** (the documented small-screen fallback), staying centred within
 * whatever clear width remains.
 */
object NotificationLayout {
    /** Preferred world-space toast width; shrinks toward the available centre band on a narrow viewport. */
    const val TOAST_WIDTH = 360f

    /** World-space height of one toast row. */
    const val TOAST_HEIGHT = 40f

    /** Vertical gap between stacked toasts. */
    const val TOAST_GAP = 8f

    /**
     * World-space inset from the top edge to the first toast's TOP, clearing the UC32 pause button
     * (`PAUSE_BUTTON_HEIGHT` 56 + its top margin) so the toast band starts just below it.
     */
    const val TOP_INSET = 96f

    /** Horizontal breathing room kept between the band and the top-left HUD block / top-right minimap / arc. */
    const val SIDE_GAP = 12f

    /**
     * World-space width reserved on the RIGHT for the top-right minimap so a centred toast never runs under
     * it: `MinimapRenderer.DEFAULT_SIZE` (180) + `DEFAULT_MARGIN` (24) + slack. Kept as a local constant
     * (not a cross-import of the renderer's internals) so this layout stays a pure, dependency-light value.
     */
    const val RIGHT_RESERVED = 216f

    /**
     * The world-space margin the bottom-corner controls are anchored at — a documented mirror of
     * `PlayScreen.MARGIN` (the arc is positioned at this margin from the screen edges). Used only to derive
     * the worst-case action-arc footprint for the lower-row clearance below.
     */
    private const val CONTROL_MARGIN = 24f

    /**
     * The rectangle for the toast at [index] (0 = top of the stack) for the given viewport, in world units
     * (same space as [HudLayout]/[MinimapLayout], so overlap can be asserted directly against them).
     *
     * Rows stack downward from [TOP_INSET]. The band spans horizontally from just right of the HUD block to
     * just left of the minimap reservation; a row whose bottom edge dips below the action arc's top edge
     * additionally narrows its right edge to clear the worst-case right-handed arc (see the class doc). The
     * toast width is clamped to the resulting band so it never overruns any neighbour.
     */
    fun toastRect(
        vpWidth: Float,
        vpHeight: Float,
        index: Int,
    ): MinimapLayout.Rect {
        val top = vpHeight - TOP_INSET - index * (TOAST_HEIGHT + TOAST_GAP)
        val y = top - TOAST_HEIGHT

        val bandLeft = HudLayout.BLOCK_X + HudLayout.BLOCK_WIDTH + SIDE_GAP
        var bandRight = vpWidth - RIGHT_RESERVED - SIDE_GAP

        // Lower-row clearance for the bottom-corner action arc (UC26). The arc is anchored at the screen
        // corners and is LAYOUT_HEIGHT tall, so its top edge sits at `CONTROL_MARGIN + LAYOUT_HEIGHT`. A row
        // whose bottom edge ([y]) is below that dips into the arc's vertical span; the band is
        // handedness-agnostic, so such a row clears the worst-case RIGHT-handed arc by pulling its right edge
        // left of the arc's left edge (`vpWidth - CONTROL_MARGIN - LAYOUT_WIDTH`). The left-handed arc sits
        // left of [bandLeft] already, so no left-side adjustment is needed.
        val arcTop = CONTROL_MARGIN + ActionCluster.LAYOUT_HEIGHT
        if (y < arcTop) {
            val arcLeft = vpWidth - CONTROL_MARGIN - ActionCluster.LAYOUT_WIDTH
            bandRight = minOf(bandRight, arcLeft - SIDE_GAP)
        }

        val bandWidth = (bandRight - bandLeft).coerceAtLeast(0f)
        val width = TOAST_WIDTH.coerceAtMost(bandWidth)
        val x = bandLeft + (bandWidth - width) / 2f
        return MinimapLayout.Rect(x, y, width, TOAST_HEIGHT)
    }
}
