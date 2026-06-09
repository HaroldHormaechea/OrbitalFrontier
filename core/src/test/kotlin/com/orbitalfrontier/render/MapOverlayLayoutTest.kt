package com.orbitalfrontier.render

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) geometry + tunable coverage for the UC23 click-to-zoom map overlay,
 * [MapOverlayLayout]. Everything here is in **world units** (screen px / [UiScale.factor]) — the same
 * space the controls are laid out in.
 *
 * Deterministic halves of the ACs covered here:
 *  - **AC#2** — the panel spans the **full** viewport height, unconditionally (landscape, square, and
 *    narrow-portrait viewports), with a centred width that never exceeds the viewport width.
 *  - **AC#3** — the backdrop opacity contract ([BACKDROP_ALPHA] == 0.8f).
 *  - **AC#4** — the overlay shows **more area** than the minimap: [extentShown] > contentExtent (a
 *    genuine zoom-in, == 2× the content extent).
 *  - **AC#6** — the LIVE-simulation contract ([PAUSES_SIMULATION] == false).
 *  - **projection** — `project` maps the world centre to the panel centre, is aspect-correct (the same
 *    scale on both axes, no stretch), and clamps out-of-extent world points to the panel edge so
 *    markers/ship stay visible (mirrors the minimap's clampToPanel).
 *
 * The actual 0.8 blend on-device, the full-height span on real hardware, and the zoom revealing more
 * POIs are GL-bound and verified by a live emulator pass (a separate root-session step).
 */
class MapOverlayLayoutTest {
    // --- AC#2: the panel spans the full viewport height, with a centred width ----------------------

    @Test
    fun `overlay spans the full height on a landscape viewport`() {
        val rect = MapOverlayLayout.overlayRect(vpWidth = 1200f, vpHeight = 600f)
        assertEquals("AC#2: panel height == full viewport height", 600f, rect.height, EPS)
        assertEquals("AC#2: panel bottom sits at y = 0 (full height)", 0f, rect.y, EPS)
        assertTrue("AC#2: panel width never exceeds the viewport width", rect.width <= 1200f)
        assertEquals("AC#2: panel is horizontally centred", 1200f / 2f, rect.x + rect.width / 2f, EPS)
    }

    @Test
    fun `overlay spans the full height on a square viewport`() {
        val rect = MapOverlayLayout.overlayRect(vpWidth = 600f, vpHeight = 600f)
        assertEquals("AC#2: panel height == full viewport height (square)", 600f, rect.height, EPS)
        assertTrue("AC#2: panel width never exceeds the viewport width", rect.width <= 600f)
        assertEquals("AC#2: panel is horizontally centred", 600f / 2f, rect.x + rect.width / 2f, EPS)
    }

    @Test
    fun `overlay spans the full height on a narrow portrait viewport`() {
        // The width clamp on a narrow (portrait) viewport must never shorten the full-height panel.
        val rect = MapOverlayLayout.overlayRect(vpWidth = 400f, vpHeight = 800f)
        assertEquals("AC#2: full height holds unconditionally on portrait", 800f, rect.height, EPS)
        assertEquals("AC#2: panel bottom sits at y = 0", 0f, rect.y, EPS)
        assertEquals("AC#2: width clamps to the narrow viewport width", 400f, rect.width, EPS)
        assertTrue("AC#2: panel width never exceeds the viewport width", rect.width <= 400f)
        assertEquals("AC#2: panel is horizontally centred", 400f / 2f, rect.x + rect.width / 2f, EPS)
    }

    // --- AC#4: the overlay shows MORE area than the minimap (a genuine zoom-in) --------------------

    @Test
    fun `extentShown is strictly greater than the minimap content extent`() {
        // AC#4 (CONFIRMED reading): the overlay maps a LARGER world radius than the minimap, so more of
        // the sector is in view — not a tighter crop of the same content.
        assertTrue("AC#4: the overlay shows more area than the minimap", MapOverlayLayout.extentShown(500f) > 500f)
    }

    @Test
    fun `extentShown is the content extent scaled by the area multiplier`() {
        assertEquals("AC#4: shown extent == contentExtent * AREA_MULTIPLIER", 1000f, MapOverlayLayout.extentShown(500f), EPS)
        assertEquals("AC#4: AREA_MULTIPLIER is the 2x genuine zoom-in", 2.0f, MapOverlayLayout.AREA_MULTIPLIER, EPS)
    }

    // --- AC#3 / AC#6: the opacity + live-simulation contracts --------------------------------------

    @Test
    fun `backdrop alpha is the 80 percent opacity contract`() {
        assertEquals("AC#3: the backdrop is drawn at ~0.8 alpha", 0.8f, MapOverlayLayout.BACKDROP_ALPHA, EPS)
    }

    @Test
    fun `opening the overlay does not pause the simulation`() {
        // AC#6: this game keeps the simulation LIVE while the map overlay is open (the consistent,
        // documented behaviour); the renderer/PlayScreen read this contract rather than re-deciding it.
        assertEquals("AC#6: the overlay is a pure HUD layer, simulation stays live", false, MapOverlayLayout.PAUSES_SIMULATION)
    }

    // --- projection: centre-origin, aspect-correct, edge-clamped -----------------------------------

    @Test
    fun `project maps the world centre to the panel centre`() {
        val rect = MapOverlayLayout.overlayRect(vpWidth = 400f, vpHeight = 800f)
        val p = MapOverlayLayout.project(rect, contentExtent = 84f, world = Vec2.ZERO)
        assertEquals("world origin maps to panel centre x", rect.x + rect.width / 2f, p.x, EPS)
        assertEquals("world origin maps to panel centre y", rect.y + rect.height / 2f, p.y, EPS)
    }

    @Test
    fun `project is aspect-correct - the same scale on both axes`() {
        // A non-square (portrait) panel: width 400, height 800. The mapping must use a SINGLE scale on
        // the limiting half-dimension, so the map is not stretched. With contentExtent = 84,
        // extentShown = 168 and half = min(200, 400) - PADDING(32) = 168, so scale = 168/168 = 1.
        val rect = MapOverlayLayout.overlayRect(vpWidth = 400f, vpHeight = 800f)
        val centerX = rect.x + rect.width / 2f
        val centerY = rect.y + rect.height / 2f
        val p = MapOverlayLayout.project(rect, contentExtent = 84f, world = Vec2(40f, 80f))
        // Same scale (1.0) on both axes: the offset equals the world delta, with no per-axis distortion.
        assertEquals("aspect-correct: x offset == world x at unit scale", 40f, p.x - centerX, EPS)
        assertEquals("aspect-correct: y offset == world y at unit scale", 80f, p.y - centerY, EPS)
    }

    @Test
    fun `project clamps out-of-extent world points to the panel edge`() {
        // The sector is unbounded, so a marker far outside the shown extent must clamp to the panel edge
        // (centre ± half) and stay visible, mirroring the minimap's clampToPanel.
        val rect = MapOverlayLayout.overlayRect(vpWidth = 400f, vpHeight = 800f)
        val centerX = rect.x + rect.width / 2f
        val centerY = rect.y + rect.height / 2f
        val half = minOf(rect.width / 2f, rect.height / 2f) - MapOverlayLayout.PADDING

        val far = MapOverlayLayout.project(rect, contentExtent = 84f, world = Vec2(100_000f, 100_000f))
        assertEquals("clamp +x to the panel edge", centerX + half, far.x, EPS)
        assertEquals("clamp +y to the panel edge", centerY + half, far.y, EPS)

        val farNeg = MapOverlayLayout.project(rect, contentExtent = 84f, world = Vec2(-100_000f, -100_000f))
        assertEquals("clamp -x to the panel edge", centerX - half, farNeg.x, EPS)
        assertEquals("clamp -y to the panel edge", centerY - half, farNeg.y, EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
