package com.orbitalfrontier.render

/**
 * Pure, libGDX-free geometry for the transient notification toasts (UC35 AC#4).
 *
 * Mirrors the established pure `*Layout` pattern ([HudLayout]/[MinimapLayout]) — world-unit rectangles,
 * no engine types — so the placement is JVM-testable and a guard can assert the toasts never collide with
 * the other reserved HUD regions without a GL context.
 *
 * The toasts occupy a **TOP-CENTRE band that stacks downward**, deliberately threaded through the clear gap
 * between the three things that own the top of the screen:
 *  - the top-LEFT HUD readout block ([HudLayout.BLOCK_WIDTH]) — the band's left edge sits a [SIDE_GAP] to its
 *    right, so a toast never runs under the readouts;
 *  - the top-RIGHT minimap (UC22) — [RIGHT_RESERVED] reserves the minimap's worst-case width
 *    (`MinimapRenderer.DEFAULT_SIZE` 180 + its 24 margin, plus a little slack) on the right, so a toast never
 *    runs under the minimap at any supported width; and
 *  - the top-CENTRE UC32 pause button — [TOP_INSET] starts the first toast *below* it, so the pause control
 *    stays reachable.
 *
 * The bottom action arc (UC26) lives far below, so the band clears it by construction (AC#4). At the smallest
 * supported viewport the centre gap narrows; rather than overrun a neighbour the toast **width shrinks to the
 * available band** (the documented small-screen fallback), staying centred within whatever clear width remains.
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

    /** Horizontal breathing room kept between the band and the top-left HUD block / top-right minimap. */
    const val SIDE_GAP = 12f

    /**
     * World-space width reserved on the RIGHT for the top-right minimap so a centred toast never runs under
     * it: `MinimapRenderer.DEFAULT_SIZE` (180) + `DEFAULT_MARGIN` (24) + slack. Kept as a local constant
     * (not a cross-import of the renderer's internals) so this layout stays a pure, dependency-light value.
     */
    const val RIGHT_RESERVED = 216f

    /**
     * The rectangle for the toast at [index] (0 = top of the stack) for the given viewport, in world units
     * (same space as [HudLayout]/[MinimapLayout], so overlap can be asserted directly against them).
     *
     * The band spans horizontally from just right of the HUD block to just left of the minimap reservation;
     * the toast is centred in that band, its width clamped to the band so it never overruns either neighbour.
     * Rows stack downward from [TOP_INSET].
     */
    fun toastRect(
        vpWidth: Float,
        vpHeight: Float,
        index: Int,
    ): MinimapLayout.Rect {
        val bandLeft = HudLayout.BLOCK_WIDTH + SIDE_GAP
        val bandRight = vpWidth - RIGHT_RESERVED - SIDE_GAP
        val bandWidth = (bandRight - bandLeft).coerceAtLeast(0f)
        val width = TOAST_WIDTH.coerceAtMost(bandWidth)
        val x = bandLeft + (bandWidth - width) / 2f

        val top = vpHeight - TOP_INSET - index * (TOAST_HEIGHT + TOAST_GAP)
        val y = top - TOAST_HEIGHT
        return MinimapLayout.Rect(x, y, width, TOAST_HEIGHT)
    }
}
