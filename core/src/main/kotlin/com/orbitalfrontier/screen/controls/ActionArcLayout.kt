package com.orbitalfrontier.screen.controls

import com.orbitalfrontier.settings.ScreenSide
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A plain rectangle in world-space UI coordinates (origin bottom-left, libGDX convention): [x]/[y] are
 * the lower-left corner, [width]/[height] the extent. Deliberately libGDX-free so the geometry below is
 * a pure value computation, unit-testable on the JVM without a Scene2D stage (ADR 0001).
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

    /** Centre point on the X axis. */
    val centerX: Float get() = x + width / 2f

    /** Centre point on the Y axis. */
    val centerY: Float get() = y + height / 2f

    /**
     * True when this rectangle shares any interior area with [other]. Edge-touching (one's right equals
     * the other's left, etc.) is NOT an overlap — a shared edge keeps the hit-rects separable.
     */
    fun overlaps(other: UiRect): Boolean = x < other.right && right > other.x && y < other.top && top > other.y
}

/**
 * Pure, libGDX-free geometry for the bottom-corner semicircular action arc (UC26). Mirrors the role
 * [com.orbitalfrontier.settings.ControlsLayout] plays for the flat controls: the screen layer reads the
 * computed button rectangles and positions Scene2D actors at them, so the whole layout is unit-testable
 * on the JVM independent of libGDX.
 *
 * The arc pivots on a bottom corner (bottom-right for [ScreenSide.RIGHT], mirrored to bottom-left for
 * [ScreenSide.LEFT]). Each button centre sits at the same [radius] from the pivot so the set follows the
 * natural sweep of a thumb. The first button (index 0 — **FIRE**) is pinned to a fixed span endpoint at a
 * constant angle for every visible count, so FIRE never moves as contextual buttons appear or disappear
 * (UC26 AC#7); the remaining contextual buttons are distributed evenly across the span and reflow as the
 * count changes.
 *
 * Coordinate frame is the caller's choice: pass the screen viewport + margin for absolute screen-space
 * rects (the layout-guard test does this), or pass the cluster footprint as the viewport with a zero
 * margin for rects local to the cluster's own bounding box (the [ActionCluster] does this). The two are
 * identical up to a translation, because the pivot is derived the same way in both frames.
 */
object ActionArcLayout {
    /**
     * The computed arc: [buttons] are the per-button D×D rectangles in the same order they were
     * requested (index 0 = FIRE), and [bounds] is the fixed footprint of the full sweep — the
     * `(radius + buttonDiameter)` square anchored at the pivot corner. [bounds] depends only on the
     * pivot/radius/diameter, never on [buttons].size, so the layout reservation stays stable as the
     * visible count changes.
     */
    data class Arc(
        val buttons: List<UiRect>,
        val bounds: UiRect,
    )

    /**
     * Compute the arc for [count] visible buttons.
     *
     * @param side which bottom corner the arc pivots on (RIGHT = bottom-right, LEFT = mirrored bottom-left)
     * @param viewportWidth / [viewportHeight] the coordinate frame extent (screen viewport, or footprint)
     * @param margin clearance from the frame edges to the pivot's outer button (0 in the footprint frame)
     * @param radius distance from the pivot to every button centre
     * @param buttonDiameter the D of each circular button
     * @param spanStartDegrees angle (standard math convention, CCW from +X) of FIRE — the pinned endpoint
     * @param spanEndDegrees angle of the far end of the sweep
     * @param count number of visible buttons (>= 1; FIRE is always present)
     */
    fun compute(
        side: ScreenSide,
        viewportWidth: Float,
        viewportHeight: Float,
        margin: Float,
        radius: Float,
        buttonDiameter: Float,
        spanStartDegrees: Float,
        spanEndDegrees: Float,
        count: Int,
    ): Arc {
        require(count >= 1) { "an arc always has at least the FIRE button" }
        val half = buttonDiameter / 2f

        // Pivot at the bottom corner: outer button just clears the margin on both axes. The pivot X is
        // mirrored for LEFT; the pivot Y always floors at the bottom margin.
        val pivotX = if (side == ScreenSide.LEFT) margin + half else viewportWidth - margin - half
        val pivotY = margin + half

        val buttons =
            (0 until count).map { i ->
                // FIRE (i == 0) is pinned at spanStart for every count; the rest spread evenly to spanEnd.
                val t = if (count == 1) 0f else i.toFloat() / (count - 1).toFloat()
                val angleDeg = spanStartDegrees + t * (spanEndDegrees - spanStartDegrees)
                // Mirror about the vertical axis (the 90° "straight up" direction) for left-handed play,
                // so FIRE stays "up" on both sides and the contextual buttons sweep toward the inner edge.
                val effectiveDeg = if (side == ScreenSide.LEFT) 180f - angleDeg else angleDeg
                val rad = (effectiveDeg * PI / 180.0).toFloat()
                val cx = pivotX + radius * cos(rad)
                val cy = pivotY + radius * sin(rad)
                UiRect(cx - half, cy - half, buttonDiameter, buttonDiameter)
            }

        // Footprint = the (radius + diameter) square anchored at the pivot corner, independent of count.
        val footprint = radius + buttonDiameter
        val boundsX = if (side == ScreenSide.LEFT) pivotX - half else pivotX - radius - half
        val bounds = UiRect(boundsX, pivotY - half, footprint, footprint)

        return Arc(buttons, bounds)
    }
}
