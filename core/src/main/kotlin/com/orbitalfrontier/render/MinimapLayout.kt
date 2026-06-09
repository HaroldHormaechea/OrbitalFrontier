package com.orbitalfrontier.render

/**
 * Pure, libGDX-free geometry for the HUD minimap panel (UC22).
 *
 * Everything here is expressed in **world units** (the Scene2D stage's coordinate space, i.e.
 * screen pixels / [UiScale.factor]) so the same numbers the on-screen controls are laid out in
 * ([com.orbitalfrontier.screen.PlayScreen.layoutControls]) describe the panel too — there is no
 * px↔world seam inside the layout maths. [MinimapRenderer] divides its pixel viewport by the UI scale
 * to call in, then multiplies the returned [Rect] back up for the actual pixel draw.
 *
 * Kept engine-free (no libGDX types) so it stays JVM-testable (ADR 0001) and the overlap-free
 * placement can be asserted directly in a unit test.
 *
 * **Minimum supported size:** 1080px landscape ≈ 540 world units (with [UiScale.factor] = 2). At that
 * floor the fitted panel is still clear of the reserved control band by [panelRect]'s `gap`; smaller
 * viewports are not a supported target.
 */
object MinimapLayout {
    /** An axis-aligned rectangle in world units; [x]/[y] is the bottom-left corner. */
    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) {
        /** True when this rectangle shares any area with [other] (edge-touching is not an overlap). */
        fun overlaps(other: Rect): Boolean =
            x < other.x + other.width &&
                other.x < x + width &&
                y < other.y + other.height &&
                other.y < y + height
    }

    /**
     * The minimap panel as a top-right-anchored square that fits the space left above the controls.
     *
     * The square's side is the height available between the top margin and the reserved bottom control
     * band (less a [gap] of breathing room), clamped to `[minSize, maxSize]`:
     *
     * ```
     * size = (vpHeight - margin - reservedBottom - gap).coerceIn(minSize, maxSize)
     * ```
     *
     * It is then anchored into the top-right corner (`x = vpWidth - margin - size`,
     * `y = vpHeight - margin - size`). [reservedBottom] is the world-space height occupied by the
     * bottom controls (measured from y = 0); as long as it is the *worst-case* right-edge control top,
     * the returned rect is overlap-free with those controls by construction — the panel's bottom edge
     * sits exactly `gap` above the reserved band whenever the size is not clamped.
     */
    fun panelRect(
        vpWidth: Float,
        vpHeight: Float,
        reservedBottom: Float,
        margin: Float,
        maxSize: Float,
        minSize: Float,
        gap: Float,
    ): Rect {
        val size = (vpHeight - margin - reservedBottom - gap).coerceIn(minSize, maxSize)
        val x = vpWidth - margin - size
        val y = vpHeight - margin - size
        return Rect(x, y, size, size)
    }
}
