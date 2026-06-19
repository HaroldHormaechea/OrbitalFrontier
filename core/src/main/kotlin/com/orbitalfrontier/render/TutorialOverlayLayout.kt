package com.orbitalfrontier.render

import com.orbitalfrontier.notify.NotificationPolicy
import com.orbitalfrontier.screen.controls.ActionCluster

/**
 * Pure, libGDX-free geometry for the first-run tutorial hint band (UC36).
 *
 * Mirrors the established pure `*Layout` pattern ([NotificationLayout]/[HudLayout]/[MinimapLayout]) —
 * world-unit rectangles, no engine types — so the placement is JVM-testable and a guard can assert the
 * hint band never collides with the other reserved HUD regions without a GL context.
 *
 * The band is **centred horizontally** (equal corner reservation on both sides) and clears the
 * bottom-corner controls (joystick + UC26 action arc) and the top-right minimap at every supported
 * viewport. Its vertical slot composes with the top-centre UC35 toast band, which stacks DOWNWARD up to
 * [NotificationPolicy.maxVisible] rows:
 *  - **Preferred — just above the bottom controls, BELOW the toast band.** Used at 720p and up, where the
 *    toast band's lowest row sits well above the controls, leaving a clean lower-centre slot.
 *  - **Floor fallback — just above the whole toast band.** At the 960×540 floor the toast band reaches
 *    *below* the bottom-controls' top edge (the arc-dodged lowest toast occupies the lower centre), so no
 *    slot exists between the controls and the toasts. A screen-centred band that also stays above the
 *    control band (the UC36 layout contract) must therefore sit ABOVE the toast stack; the band lifts to
 *    edge-touch the highest toast's top. (An earlier version placed a fixed slot just above the controls
 *    and overlapped all three toasts at the floor — that was the bug; this toast-aware placement is the
 *    fix, encoded in the UC36 layout guard test.)
 *
 * The band height is bounded by [BAND_HEIGHT] so the floor fallback fits in the top inset above the toast
 * band ([NotificationLayout.TOP_INSET]) with a small screen-top margin.
 *
 * Known floor-only cosmetic: at 960×540 the fallback slot is the only toast-clear, above-controls,
 * screen-centred region, and it shares the top-centre with the pause button (UC32). On every larger
 * supported viewport the band uses the clean below-controls slot, clear of the pause button. This is the
 * GL-bound "no visual overlap" concern verified on a live emulator; the geometry guard here only asserts
 * the reserved-region (controls/minimap/toast) contract.
 */
object TutorialOverlayLayout {
    /**
     * World-space height of the hint band (copy line + the SKIP / SKIP ALL button row). Bounded so the
     * floor fallback ([NotificationLayout.TOP_INSET] tall) fits above the toast band with a screen-top
     * margin: `BAND_HEIGHT <= TOP_INSET` (96) minus the margin.
     */
    const val BAND_HEIGHT = 88f

    /** Vertical gap between the bottom control band and the band in the preferred (below-controls) slot. */
    const val BAND_GAP = 12f

    /** Horizontal breathing room kept between the hint band and each bottom-corner control. */
    const val SIDE_GAP = 12f

    /**
     * The world-space margin the bottom-corner controls (and the toast band) are anchored at — a
     * documented mirror of `PlayScreen.MARGIN`. Kept local (not a cross-import of the screen) so this
     * layout stays a pure, dependency-light value, exactly like [NotificationLayout.CONTROL_MARGIN].
     */
    private const val CONTROL_MARGIN = 24f

    /** Mirror of `PlayScreen.JOYSTICK_SIZE` — the movement stick footprint, for the worst-case reserve. */
    private const val JOYSTICK_SIZE = 220f

    /**
     * The hint-band rectangle for the given viewport, in world units (same space as the other pure
     * layouts, so overlap can be asserted directly between rectangles). Centred horizontally between the
     * worst-case corner-control reservations; placed just above the bottom controls when that slot clears
     * the toast band, else lifted to just above the toast band (see the class doc). When the viewport is
     * too narrow for any clear centre strip the width clamps to 0 (the caller then draws nothing
     * meaningful) rather than overrunning a control.
     */
    fun bandRect(
        vpWidth: Float,
        vpHeight: Float,
    ): MinimapLayout.Rect {
        // Worst-case corner-control footprint, handedness-agnostic (the wider/taller of stick vs. arc).
        val cornerWidth = maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_WIDTH)
        val cornerHeight = maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

        // The reserve accounts for the control's own MARGIN from the screen edge (the corner control's
        // inner edge sits at CONTROL_MARGIN + cornerWidth), so the band's edge never clips the arc even
        // when the band shares the controls' vertical span.
        val reserve = CONTROL_MARGIN + cornerWidth + SIDE_GAP
        val x = reserve
        val width = (vpWidth - 2f * reserve).coerceAtLeast(0f)

        // Visible toast-band extent, read from the SAME pure geometry the renderer uses (single source) at
        // the default policy's max-visible count — PlayScreen builds its NotificationQueue with the default
        // policy, so the default maxVisible is the value in play. The highest toast's top and the lowest
        // toast's bottom bound the band's vertical slot.
        val lowestVisibleIndex = (NotificationPolicy().maxVisible - 1).coerceAtLeast(0)
        val topToast = NotificationLayout.toastRect(vpWidth, vpHeight, 0)
        val lowToast = NotificationLayout.toastRect(vpWidth, vpHeight, lowestVisibleIndex)
        val toastBandTop = topToast.y + topToast.height
        val toastBandBottom = lowToast.y

        // Preferred slot: just above the bottom controls, fully below the toast band. Use it only when it
        // clears the toasts (720p+); otherwise lift the band to edge-touch the top of the toast band — the
        // only screen-centred, above-controls slot that clears the toasts at the 960×540 floor.
        val controlTop = CONTROL_MARGIN + cornerHeight
        val belowY = controlTop + BAND_GAP
        val y = if (belowY + BAND_HEIGHT <= toastBandBottom) belowY else toastBandTop

        return MinimapLayout.Rect(x, y, width, BAND_HEIGHT)
    }
}
