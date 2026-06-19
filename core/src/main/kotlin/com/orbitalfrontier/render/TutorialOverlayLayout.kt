package com.orbitalfrontier.render

import com.orbitalfrontier.screen.controls.ActionCluster

/**
 * Pure, libGDX-free geometry for the first-run tutorial hint band (UC36).
 *
 * Mirrors the established pure `*Layout` pattern ([NotificationLayout]/[HudLayout]/[MinimapLayout]) —
 * world-unit rectangles, no engine types — so the placement is JVM-testable and a guard can assert the
 * hint band never collides with the other reserved HUD regions without a GL context.
 *
 * The hint band sits in the **bottom-centre**, in the clear strip just ABOVE the two bottom-corner
 * controls (the movement joystick and the action arc) and well below the top-centre UC35 toast band —
 * so the onboarding copy is read near the controls it points at without ever overrunning a toast, the
 * top-right minimap, or either corner control. To stay handedness-agnostic it reserves the worst-case
 * (widest/tallest) corner-control footprint on BOTH sides, which — because that reservation
 * ([ActionCluster.LAYOUT_WIDTH], wider than the minimap's right reservation) also clears the top-right
 * minimap that hangs down the right edge — keeps the band clear on every side at the 960×540 floor.
 */
object TutorialOverlayLayout {
    /** World-space height of the hint band (copy line + the SKIP / SKIP ALL button row). */
    const val BAND_HEIGHT = 120f

    /** Vertical gap between the top of the bottom control band and the bottom of the hint band. */
    const val BAND_GAP = 12f

    /** Horizontal breathing room kept between the hint band and each bottom-corner control. */
    const val SIDE_GAP = 12f

    /**
     * The world-space margin the bottom-corner controls are anchored at — a documented mirror of
     * `PlayScreen.MARGIN` — used to derive the worst-case corner-control footprint. Kept local (not a
     * cross-import of the screen) so this layout stays a pure, dependency-light value, exactly like
     * [NotificationLayout.CONTROL_MARGIN].
     */
    private const val CONTROL_MARGIN = 24f

    /** Mirror of `PlayScreen.JOYSTICK_SIZE` — the movement stick footprint, for the worst-case reserve. */
    private const val JOYSTICK_SIZE = 220f

    /**
     * The hint-band rectangle for the given viewport, in world units (same space as the other pure
     * layouts, so overlap can be asserted directly against them). Centred horizontally between the
     * worst-case corner-control reservations and floored just above the bottom control band. When the
     * viewport is too narrow for any clear centre strip the width clamps to 0 (the caller then simply
     * draws nothing meaningful) rather than overrunning a control.
     */
    fun bandRect(
        vpWidth: Float,
        vpHeight: Float,
    ): MinimapLayout.Rect {
        // Worst-case corner-control footprint, handedness-agnostic (the wider/taller of stick vs. arc).
        val cornerWidth = maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_WIDTH)
        val cornerHeight = maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)
        val reserve = cornerWidth + SIDE_GAP

        val controlTop = CONTROL_MARGIN + cornerHeight
        val y = controlTop + BAND_GAP
        val x = reserve
        val width = (vpWidth - 2f * reserve).coerceAtLeast(0f)
        return MinimapLayout.Rect(x, y, width, BAND_HEIGHT)
    }
}
