package com.orbitalfrontier.render

import com.orbitalfrontier.render.MinimapLayout.Rect
import com.orbitalfrontier.screen.controls.ActionArcLayout
import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.settings.ScreenSide
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) geometry coverage for the UC36 first-run tutorial hint band: the draw-only
 * onboarding band must not obstruct any reserved HUD region at the supported viewports — the bottom-corner
 * controls (the movement joystick + the UC26 action arc), the top-right minimap (UC22), and the transient
 * UC35 notification toasts it must compose with (the use-case pitfall: "starting the tutorial mid-flight
 * must compose with … notifications (UC35)").
 *
 * Everything is in **world units** — the same space [TutorialOverlayLayout]/[NotificationLayout]/
 * [MinimapLayout] work in — so non-overlap is asserted directly between the rectangles, mirroring
 * [NotificationLayoutTest]. The arc footprint comes from the production [ActionArcLayout]; its [UiRect] is
 * converted to the shared [Rect] for the overlap check. The "no *visual* overlap" half is GL-bound and is
 * verified on a live emulator, not here.
 */
class TutorialOverlayLayoutTest {
    // --- The band clears the bottom-corner controls + minimap at every supported viewport --------------

    @Test
    fun `the hint band clears both corner controls and the minimap at supported viewports`() {
        for ((vpWidth, vpHeight) in SUPPORTED_VIEWPORTS) {
            val band = TutorialOverlayLayout.bandRect(vpWidth, vpHeight)
            val at = "viewport ${vpWidth}x$vpHeight, band=$band"

            // Both handedness arrangements: the band reserves the worst-case corner footprint on BOTH
            // sides, so it must clear the joystick and the arc whichever corner each occupies.
            assertNoOverlap("AC: band must clear the right action arc — $at", band, arcBounds(ScreenSide.RIGHT, vpWidth))
            assertNoOverlap("AC: band must clear the left action arc — $at", band, arcBounds(ScreenSide.LEFT, vpWidth))
            assertNoOverlap("AC: band must clear the right joystick — $at", band, joystickBounds(ScreenSide.RIGHT, vpWidth))
            assertNoOverlap("AC: band must clear the left joystick — $at", band, joystickBounds(ScreenSide.LEFT, vpWidth))
            assertNoOverlap("AC: band must clear the minimap panel — $at", band, panel(vpWidth, vpHeight))
        }
    }

    @Test
    fun `the band is centred with positive width and floored above the bottom controls`() {
        for ((vpWidth, vpHeight) in SUPPORTED_VIEWPORTS) {
            val band = TutorialOverlayLayout.bandRect(vpWidth, vpHeight)
            val at = "viewport ${vpWidth}x$vpHeight, band=$band"
            assertTrue("the band has positive width at a supported viewport — $at", band.width > 0f)
            // Horizontally centred in the viewport (equal reservation on both sides).
            val centre = band.x + band.width / 2f
            assertTrue("the band is horizontally centred — $at", kotlin.math.abs(centre - vpWidth / 2f) < EPS)
            // Floored above the worst-case bottom corner-control top.
            val controlTop = MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)
            assertTrue("the band sits above the bottom control band — $at", band.y >= controlTop - EPS)
        }
    }

    @Test
    fun `a viewport too narrow for a clear centre strip clamps the band width to zero`() {
        // Below the supported floor the centre strip can vanish; rather than overrun a corner control the
        // width clamps to 0 (the caller then draws nothing meaningful) — the documented degenerate case.
        val reserve = maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_WIDTH) + TutorialOverlayLayout.SIDE_GAP
        val band = TutorialOverlayLayout.bandRect(vpWidth = 2f * reserve - 10f, vpHeight = 540f)
        assertTrue("a too-narrow viewport clamps to zero width, never negative", band.width == 0f)
    }

    // --- AC: the band composes with the UC35 toast band (must not overlap a visible toast) -------------

    @Test
    fun `the hint band does not overlap any visible toast at supported viewports`() {
        for ((vpWidth, vpHeight) in SUPPORTED_VIEWPORTS) {
            val band = TutorialOverlayLayout.bandRect(vpWidth, vpHeight)
            for (index in 0 until MAX_VISIBLE_TOASTS) {
                val toast = NotificationLayout.toastRect(vpWidth, vpHeight, index)
                assertNoOverlap(
                    "UC35 compose: the tutorial band must not overlap toast[$index] at ${vpWidth}x$vpHeight " +
                        "(band=$band, toast=$toast)",
                    band,
                    toast,
                )
            }
        }
    }

    private fun assertNoOverlap(
        message: String,
        a: Rect,
        b: Rect,
    ) {
        assertTrue(message, !a.overlaps(b))
    }

    private companion object {
        const val EPS = 1e-3f

        // Minimum supported viewport: 1080p landscape at UiScale.factor = 2 → 1920x1080 px ÷ 2 = world
        // (the documented floor, mirroring MinimapLayoutTest / NotificationLayoutTest).
        const val MIN_VP_WIDTH = 960f
        const val MIN_VP_HEIGHT = 540f

        val SUPPORTED_VIEWPORTS = listOf(MIN_VP_WIDTH to MIN_VP_HEIGHT, 1280f to 720f, 1920f to 1080f)

        // Default notification policy visible window (NotificationPolicy.maxVisible).
        const val MAX_VISIBLE_TOASTS = 3

        // World-unit mirrors of PlayScreen / MinimapRenderer production constants (see NotificationLayoutTest).
        const val MARGIN = 24f
        const val JOYSTICK_SIZE = 220f
        const val MIN_SIZE = 120f
        const val CONTROL_GAP = 16f
        const val DEFAULT_SIZE = 180f
        val RESERVED_BOTTOM = MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

        fun panel(
            vpWidth: Float,
            vpHeight: Float,
        ): Rect =
            MinimapLayout.panelRect(
                vpWidth = vpWidth,
                vpHeight = vpHeight,
                reservedBottom = RESERVED_BOTTOM,
                margin = MARGIN,
                maxSize = DEFAULT_SIZE,
                minSize = MIN_SIZE,
                gap = CONTROL_GAP,
            )

        /** The production action-arc footprint for [side] at [vpWidth], as a [Rect] for the overlap check. */
        fun arcBounds(
            side: ScreenSide,
            vpWidth: Float,
        ): Rect {
            val b =
                ActionArcLayout.compute(
                    side = side,
                    viewportWidth = vpWidth,
                    viewportHeight = MIN_VP_HEIGHT,
                    margin = MARGIN,
                    radius = ActionCluster.RADIUS,
                    buttonDiameter = ActionCluster.BUTTON_DIAMETER,
                    spanStartDegrees = ActionCluster.SPAN_START_DEGREES,
                    spanEndDegrees = ActionCluster.SPAN_END_DEGREES,
                    count = 1,
                ).bounds
            return Rect(b.x, b.y, b.width, b.height)
        }

        /** The movement-joystick footprint for [side] at [vpWidth] (PlayScreen anchors it at the bottom corner). */
        fun joystickBounds(
            side: ScreenSide,
            vpWidth: Float,
        ): Rect {
            val x = if (side == ScreenSide.LEFT) MARGIN else vpWidth - MARGIN - JOYSTICK_SIZE
            return Rect(x, MARGIN, JOYSTICK_SIZE, JOYSTICK_SIZE)
        }
    }
}
