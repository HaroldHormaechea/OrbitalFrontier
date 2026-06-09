package com.orbitalfrontier.screen.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the UC25 debug point-and-go panel placement geometry,
 * [PointAndGoPanelPlacement.place]. This is the deterministic half of the toggle-hit-target bug fix:
 * the earlier code anchored the panel's Y from `PlayScreen.bottomControlBand()` (the *top* of the
 * bottom control band), which pushed the toggle's hit-rect above the usable world area, so it drew
 * clipped at the top-centre but was never hittable. The fix floors the panel at the bottom [MARGIN]
 * and centres it horizontally in the inner gap between the two flight controls.
 *
 * The on-device tap → unproject → hit-test that actually flips the toggle is GL-bound and re-verified
 * on the emulator by the team lead; this suite locks the geometry invariant the hit-test relies on:
 * at both supported viewport sizes and for both handedness orientations, the placed panel is fully
 * on-screen and disjoint from BOTH flight controls.
 *
 * The two flight-control rects are built exactly the way [com.orbitalfrontier.screen.PlayScreen] lays
 * them out — joystick [JOYSTICK_SIZE] x [JOYSTICK_SIZE] and action cluster
 * [ActionCluster.LAYOUT_WIDTH] x [ActionCluster.LAYOUT_HEIGHT], both floored at [MARGIN], one on each
 * side via the same `sideX` rule — so the geometry under test matches the real layout.
 */
class PointAndGoPanelPlacementTest {
    // --- place(): on-screen + disjoint from both controls, at both sizes and both handedness ---------

    @Test
    fun `places the panel on-screen and clear of both controls - 640x360, joystick left`() {
        assertWellPlaced(viewportWidth = 640f, viewportHeight = 360f, joystickOnLeft = true)
    }

    @Test
    fun `places the panel on-screen and clear of both controls - 640x360, joystick right`() {
        assertWellPlaced(viewportWidth = 640f, viewportHeight = 360f, joystickOnLeft = false)
    }

    @Test
    fun `places the panel on-screen and clear of both controls - 960x540, joystick left`() {
        assertWellPlaced(viewportWidth = 960f, viewportHeight = 540f, joystickOnLeft = true)
    }

    @Test
    fun `places the panel on-screen and clear of both controls - 960x540, joystick right`() {
        assertWellPlaced(viewportWidth = 960f, viewportHeight = 540f, joystickOnLeft = false)
    }

    // --- overlaps(): the disjointness primitive the placement contract is asserted against -----------

    @Test
    fun `overlaps is true only when two rects share interior area`() {
        val base = UiRect(100f, 100f, 50f, 50f)
        assertTrue("interior overlap is an overlap", base.overlaps(UiRect(120f, 120f, 50f, 50f)))
        assertFalse("a fully-disjoint rect does not overlap", base.overlaps(UiRect(400f, 400f, 50f, 50f)))
    }

    @Test
    fun `a shared edge is not an overlap`() {
        val base = UiRect(100f, 100f, 50f, 50f)
        // base.right == 150 touches the neighbour's left edge — separable hit-rects, NOT an overlap.
        assertFalse("right-to-left edge touch is not an overlap", base.overlaps(UiRect(150f, 100f, 50f, 50f)))
        // base.top == 150 touches the neighbour's bottom edge.
        assertFalse("top-to-bottom edge touch is not an overlap", base.overlaps(UiRect(100f, 150f, 50f, 50f)))
    }

    private companion object {
        // Mirror of PlayScreen's layout constants (kept local so this stays a pure, libGDX-free test).
        private const val MARGIN = 24f
        private const val JOYSTICK_SIZE = 220f

        // A representative debug arm-panel footprint. Comfortably narrower than the inner gap between the
        // two controls at every supported size (the narrowest gap is ~260 world units), so the centred
        // placement neither overlaps a control nor is clamped — see the clamp-is-a-no-op assertion below.
        private const val PANEL_WIDTH = 160f
        private const val PANEL_HEIGHT = 64f

        /** PlayScreen.sideX: a widget hugs the left margin, or sits one margin in from the right edge. */
        private fun sideX(
            onLeft: Boolean,
            viewportWidth: Float,
            widgetWidth: Float,
        ): Float = if (onLeft) MARGIN else viewportWidth - MARGIN - widgetWidth

        /**
         * Build the two control rects for the given viewport and handedness (joystick left + cluster
         * right, or joystick right + cluster left), call [PointAndGoPanelPlacement.place], and assert
         * the returned panel is (a) fully on-screen, (b) disjoint from BOTH controls, and (c) placed at
         * the un-clamped centred position (the `coerceIn` clamp is a no-op within this envelope — so a
         * future change that shrinks the gap trips this test instead of silently overlapping a control).
         */
        private fun assertWellPlaced(
            viewportWidth: Float,
            viewportHeight: Float,
            joystickOnLeft: Boolean,
        ) {
            val joystick =
                UiRect(
                    sideX(joystickOnLeft, viewportWidth, JOYSTICK_SIZE),
                    MARGIN,
                    JOYSTICK_SIZE,
                    JOYSTICK_SIZE,
                )
            val actionCluster =
                UiRect(
                    sideX(!joystickOnLeft, viewportWidth, ActionCluster.LAYOUT_WIDTH),
                    MARGIN,
                    ActionCluster.LAYOUT_WIDTH,
                    ActionCluster.LAYOUT_HEIGHT,
                )

            val panel =
                PointAndGoPanelPlacement.place(
                    joystick = joystick,
                    actionCluster = actionCluster,
                    panelWidth = PANEL_WIDTH,
                    panelHeight = PANEL_HEIGHT,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    margin = MARGIN,
                )

            // (a) Fully on-screen, inside the same margin the controls respect.
            assertTrue("panel left edge is inside the left margin", panel.x >= MARGIN)
            assertTrue("panel right edge is inside the right margin", panel.right <= viewportWidth - MARGIN)
            assertTrue("panel bottom edge is inside the bottom margin", panel.y >= MARGIN)
            assertTrue("panel top edge is inside the top margin", panel.top <= viewportHeight - MARGIN)

            // (b) Disjoint from BOTH flight controls — the invariant the on-device hit-test relies on.
            assertFalse("panel must not overlap the joystick", panel.overlaps(joystick))
            assertFalse("panel must not overlap the action cluster", panel.overlaps(actionCluster))

            // (c) The clamp is a no-op: the panel sits exactly at the un-clamped centred position. If a
            // future change narrows the gap so the centred panel would overlap a control, the centred X
            // would have to be clamped and this assertion fails — surfacing the regression loudly.
            val leftControl = if (joystick.x <= actionCluster.x) joystick else actionCluster
            val rightControl = if (joystick.x <= actionCluster.x) actionCluster else joystick
            val gapCentre = (leftControl.right + rightControl.x) / 2f
            assertEquals("panel X is the un-clamped centred X (clamp is a no-op)", gapCentre - PANEL_WIDTH / 2f, panel.x, 1e-3f)
            assertEquals("panel Y is the un-clamped bottom floor (clamp is a no-op)", MARGIN, panel.y, 1e-3f)
        }
    }
}
