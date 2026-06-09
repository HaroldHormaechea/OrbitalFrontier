package com.orbitalfrontier.render

import com.orbitalfrontier.common.Vec2

/**
 * Pure, libGDX-free geometry + tunables for the click-to-zoom map overlay (UC23).
 *
 * Like [MinimapLayout] everything is expressed in **world units** (the Scene2D stage's coordinate
 * space, i.e. screen pixels / [UiScale.factor]) so the panel maths share the controls' coordinate
 * system with no px<->world seam; [MapOverlayRenderer] divides its pixel viewport by the UI scale to
 * call in, then multiplies the returned geometry back up for the actual pixel draw. Kept engine-free
 * (no libGDX types, reuses [MinimapLayout.Rect]) so the placement + projection can be asserted
 * directly in a JVM unit test (ADR 0001).
 *
 * **Behaviour decisions (UC23):**
 * - The overlay spans the **full screen height** unconditionally (AC#2); its width is an independent,
 *   centred square side `min(vpHeight, vpWidth)`, so the width clamp on a narrow (portrait) viewport
 *   never shortens the full-height panel.
 * - It shows **more map area** than the minimap (AC#4, "genuine zoom-in"): the mapped world radius is
 *   the sector's content extent scaled by [AREA_MULTIPLIER], projected onto a far larger panel than
 *   the small HUD minimap, so markers spread out (more detail) while more of the sector is in view.
 * - It is **LIVE** ([pausesSimulation] = false): opening the map does not pause the simulation
 *   (see docs/design/world-and-sector.md for the LIVE-in-combat tradeoff).
 */
object MapOverlayLayout {
    /** Backdrop opacity behind the panel (AC#3): the scene stays faintly visible at ~0.8 alpha. */
    const val BACKDROP_ALPHA = 0.8f

    /**
     * World-radius multiplier for the overlay vs. the minimap (AC#4). The overlay maps a world radius
     * of `contentExtent * AREA_MULTIPLIER` onto its panel — > 1 means it shows *more area* than the
     * minimap (the confirmed reading), not a tighter crop.
     */
    const val AREA_MULTIPLIER = 2.0f

    /** UC23 keeps the simulation running while the overlay is open — the overlay is a pure HUD layer. */
    const val PAUSES_SIMULATION = false

    /** World-unit inset between the panel edge and the outermost marker, so markers never touch the frame. */
    const val PADDING = 32f

    /**
     * The overlay panel as a **full-height**, horizontally-centred rectangle in world units.
     *
     * Height is always the full viewport height (AC#2). Width is an independent centred square side
     * `min(vpHeight, vpWidth)` — on a landscape viewport that is the height (a tall portrait panel
     * centred on screen); on a narrow portrait viewport the width clamps to `vpWidth` but the height
     * is untouched, so "spans the whole height" always holds.
     */
    fun overlayRect(
        vpWidth: Float,
        vpHeight: Float,
    ): MinimapLayout.Rect {
        val height = vpHeight
        val width = minOf(vpHeight, vpWidth)
        val x = (vpWidth - width) / 2f
        return MinimapLayout.Rect(x, 0f, width, height)
    }

    /**
     * Project a centre-origin world position (sector centre = origin) into a panel pixel for [rect].
     *
     * Scales on the **limiting half-dimension** (`half = min(panelHalfW, panelHalfH) - PADDING`) so the
     * mapping is aspect-correct (the same scale on both axes — no stretch) and stays inside the
     * narrower axis of a non-square panel. World positions outside the shown extent are clamped to the
     * panel edge so the ship/markers stay visible (the sector is unbounded), mirroring the minimap's
     * `clampToPanel`.
     */
    fun project(
        rect: MinimapLayout.Rect,
        contentExtent: Float,
        world: Vec2,
    ): Vec2 {
        val centerX = rect.x + rect.width / 2f
        val centerY = rect.y + rect.height / 2f
        val half = minOf(rect.width / 2f, rect.height / 2f) - PADDING
        val extent = extentShown(contentExtent)
        val scale = if (extent > 0f) half / extent else 0f
        val dx = (world.x * scale).coerceIn(-half, half)
        val dy = (world.y * scale).coerceIn(-half, half)
        return Vec2(centerX + dx, centerY + dy)
    }

    /** The world radius the overlay maps onto its panel — the sector content extent scaled up (AC#4). */
    fun extentShown(contentExtent: Float): Float = contentExtent * AREA_MULTIPLIER
}
