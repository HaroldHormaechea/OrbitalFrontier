package com.orbitalfrontier.render

import com.orbitalfrontier.render.MinimapLayout.Rect
import com.orbitalfrontier.screen.controls.ActionArcLayout
import com.orbitalfrontier.screen.controls.ActionCluster
import com.orbitalfrontier.screen.controls.UiRect
import com.orbitalfrontier.settings.ScreenSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) geometry coverage for the UC35 notification band (AC#4): the transient
 * toasts must not obstruct the action arc (UC26), the minimap (UC22), or the top-left HUD readout block
 * (UC34) at any supported viewport.
 *
 * Everything is in **world units** — the same space [NotificationLayout]/[HudLayout]/[MinimapLayout] work
 * in — so non-overlap is asserted directly between the rectangles, mirroring [MinimapLayoutTest]. The arc
 * footprint comes from [ActionArcLayout] (the production geometry), whose [UiRect] is converted to the
 * shared [Rect] type for the overlap check since the two layers use different rect classes. The remaining
 * "no *visual* overlap" half of AC#4 is GL-bound and verified on a live emulator, not here.
 */
class NotificationLayoutTest {
    // --- AC#4: across supported viewports, no toast overlaps a reserved region --------------------------

    @Test
    fun `no visible toast overlaps the HUD block, minimap, or action arc at supported viewports`() {
        for ((vpWidth, vpHeight) in SUPPORTED_VIEWPORTS) {
            val hud = HudLayout.blockRect(vpWidth, vpHeight)
            val minimap = panel(vpWidth, vpHeight)
            val arcRight = arcBounds(ScreenSide.RIGHT, vpWidth)
            val arcLeft = arcBounds(ScreenSide.LEFT, vpWidth)
            for (index in 0 until MAX_VISIBLE) {
                val toast = NotificationLayout.toastRect(vpWidth, vpHeight, index)
                val at = "viewport ${vpWidth}x$vpHeight, toast[$index]=$toast"
                assertFalse("AC#4: toast must not overlap the HUD readout block — $at", toast.overlaps(hud))
                assertFalse("AC#4: toast must not overlap the minimap panel — $at", toast.overlaps(minimap))
                assertFalse("AC#4: toast must not overlap the right-handed action arc — $at", toast.overlaps(arcRight))
                assertFalse("AC#4: toast must not overlap the left-handed action arc — $at", toast.overlaps(arcLeft))
            }
        }
    }

    // --- AC#4: the smallest supported viewport is the pinch point — assert it explicitly ----------------

    @Test
    fun `at the smallest supported viewport the band stays clear of both top-corner reservations`() {
        val hud = HudLayout.blockRect(MIN_VP_WIDTH, MIN_VP_HEIGHT)
        val minimap = panel(MIN_VP_WIDTH, MIN_VP_HEIGHT)
        for (index in 0 until MAX_VISIBLE) {
            val toast = NotificationLayout.toastRect(MIN_VP_WIDTH, MIN_VP_HEIGHT, index)
            // The band threads between the top-left HUD block and the top-right minimap reservation.
            assertTrue("toast left edge clears the HUD block at the floor", toast.x >= hud.x + hud.width)
            assertTrue("toast right edge clears the minimap reservation at the floor", toast.x + toast.width <= minimap.x)
            assertFalse(toast.overlaps(hud))
            assertFalse(toast.overlaps(minimap))
        }
    }

    @Test
    fun `the toast width shrinks to the available band on a narrow viewport`() {
        // The documented small-screen fallback: rather than overrun a neighbour, the toast width clamps to
        // the clear centre band. Exercised below the supported floor to confirm the clamp logic itself.
        val wide = NotificationLayout.toastRect(vpWidth = 1280f, vpHeight = 720f, index = 0)
        assertEquals("a roomy viewport keeps the preferred width", NotificationLayout.TOAST_WIDTH, wide.width, EPS)

        val narrow = NotificationLayout.toastRect(vpWidth = 800f, vpHeight = 540f, index = 0)
        assertTrue("a narrow viewport shrinks the toast below the preferred width", narrow.width < NotificationLayout.TOAST_WIDTH)
        assertTrue("the shrunk toast still has positive width", narrow.width > 0f)
        // Centred within whatever clear band remains, never running under either neighbour.
        val bandLeft = HudLayout.BLOCK_WIDTH + NotificationLayout.SIDE_GAP
        val bandRight = 800f - NotificationLayout.RIGHT_RESERVED - NotificationLayout.SIDE_GAP
        assertTrue("stays right of the HUD block", narrow.x >= bandLeft - EPS)
        assertTrue("stays left of the minimap reservation", narrow.x + narrow.width <= bandRight + EPS)
    }

    @Test
    fun `toasts stack downward without overlapping each other`() {
        val a = NotificationLayout.toastRect(MIN_VP_WIDTH, MIN_VP_HEIGHT, 0)
        val b = NotificationLayout.toastRect(MIN_VP_WIDTH, MIN_VP_HEIGHT, 1)
        assertFalse("adjacent rows do not overlap", a.overlaps(b))
        assertTrue("later rows sit below earlier rows", b.y < a.y)
    }

    // --- UiRect → Rect conversion is faithful (the bridge the arc check relies on) ----------------------

    @Test
    fun `the UiRect to Rect conversion preserves all four fields`() {
        val src = UiRect(11f, 22f, 33f, 44f)
        val dst = src.toRect()
        assertEquals(src.x, dst.x, EPS)
        assertEquals(src.y, dst.y, EPS)
        assertEquals(src.width, dst.width, EPS)
        assertEquals(src.height, dst.height, EPS)
    }

    private companion object {
        const val EPS = 1e-3f

        // Minimum supported viewport: 1080p landscape at UiScale.factor = 2 → 1920x1080 px ÷ 2 = world.
        const val MIN_VP_WIDTH = 960f
        const val MIN_VP_HEIGHT = 540f

        val SUPPORTED_VIEWPORTS = listOf(MIN_VP_WIDTH to MIN_VP_HEIGHT, 1280f to 720f)

        // Default policy visible window — the number of toast rows that can be on screen at once.
        const val MAX_VISIBLE = 3

        // World-unit mirrors of PlayScreen / MinimapRenderer production constants (see MinimapLayoutTest).
        const val MARGIN = 24f
        const val JOYSTICK_SIZE = 220f
        const val MIN_SIZE = 120f
        const val CONTROL_GAP = 16f
        const val DEFAULT_SIZE = 180f
        val RESERVED_BOTTOM = MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

        fun UiRect.toRect(): Rect = Rect(x, y, width, height)

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
        ): Rect =
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
            ).bounds.toRect()
    }
}
