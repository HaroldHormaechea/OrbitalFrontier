package com.orbitalfrontier.screen.controls

/**
 * A plain rectangle in world-space UI coordinates (origin bottom-left, libGDX convention): [x]/[y] are
 * the lower-left corner, [width]/[height] the extent. Deliberately libGDX-free so the placement geometry
 * below is a pure value computation, unit-testable on the JVM without a Scene2D stage (ADR 0001).
 */
data class UiRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    /** Right edge (exclusive). */
    val right: Float get() = x + width

    /** Top edge (exclusive). */
    val top: Float get() = y + height

    /**
     * True when this rectangle shares any interior area with [other]. Edge-touching (one's right equals
     * the other's left, etc.) is NOT an overlap — the invariant the placement upholds is that the panel
     * is disjoint from both flight controls, and a shared edge keeps the hit-rects separable.
     */
    fun overlaps(other: UiRect): Boolean = x < other.right && right > other.x && y < other.top && top > other.y
}

/**
 * Computes where the debug point-and-go arm panel sits (UC25 bug fix). The earlier code anchored the
 * panel's Y from [com.orbitalfrontier.screen.PlayScreen]'s `bottomControlBand()` — the *top* of the
 * bottom control band — which pushed the toggle's hit-rect above the usable world area, so it drew
 * clipped at the top-centre but was never hittable. This helper instead floors the panel at the bottom
 * margin (like the joystick and action cluster) and centres it horizontally in the inner gap between the
 * two flight controls.
 *
 * Pure and libGDX-free: it takes the controls' live bounds as [UiRect]s and returns a [UiRect]. It is
 * handedness-agnostic — it reads whichever control is actually on the left/right from the passed bounds
 * rather than assuming a side — so it stays correct when the player flips handedness.
 */
object PointAndGoPanelPlacement {
    /**
     * Place the panel of size [panelWidth] x [panelHeight] on the bottom floor at [margin] above the
     * screen bottom, horizontally centred in the gap between [joystick] and [actionCluster]. The result
     * is clamped with `coerceIn` so it never leaves the [viewportWidth] x [viewportHeight] viewport.
     *
     * The gap is derived from the two controls' live bounds (left control's right edge → right control's
     * left edge), so for the supported viewport the centred panel is disjoint from both controls — the
     * invariant a tester relies on to actually hit the toggle.
     */
    fun place(
        joystick: UiRect,
        actionCluster: UiRect,
        panelWidth: Float,
        panelHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        margin: Float,
    ): UiRect {
        val leftControl = if (joystick.x <= actionCluster.x) joystick else actionCluster
        val rightControl = if (joystick.x <= actionCluster.x) actionCluster else joystick
        val gapCentre = (leftControl.right + rightControl.x) / 2f

        val maxX = (viewportWidth - margin - panelWidth).coerceAtLeast(margin)
        val maxY = (viewportHeight - margin - panelHeight).coerceAtLeast(margin)
        val x = (gapCentre - panelWidth / 2f).coerceIn(margin, maxX)
        val y = margin.coerceIn(margin, maxY)

        return UiRect(x, y, panelWidth, panelHeight)
    }
}
