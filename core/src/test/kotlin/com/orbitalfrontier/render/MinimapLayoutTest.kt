package com.orbitalfrontier.render

import com.orbitalfrontier.render.MinimapLayout.Rect
import com.orbitalfrontier.screen.controls.ActionCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) geometry coverage for UC22 — the minimap moves to the top-right corner
 * and must no longer overlap the action buttons or any other interactive HUD control "in any supported
 * screen size/orientation" (AC#1/#2/#3).
 *
 * Everything here is in **world units** (the same space [MinimapLayout] and
 * [com.orbitalfrontier.screen.PlayScreen.layoutControls] work in — screen px / [UiScale.factor]). The
 * deterministic overlap-freedom of the placement is asserted directly against world-unit models of the
 * bottom controls (action cluster + joystick) and the relocated top-left settings button. The
 * remaining "no *visual* overlap/clipping" half of AC#2 is GL-bound and verified by a live emulator
 * pass (a separate root-session step), not here.
 *
 * The control geometry below mirrors production's constants ([com.orbitalfrontier.screen.PlayScreen]'s
 * MARGIN/JOYSTICK_SIZE/HUD_BLOCK_HEIGHT/SETTINGS_*, [ActionCluster.LAYOUT_HEIGHT], and
 * [MinimapRenderer]'s MIN_SIZE/CONTROL_GAP/DEFAULT_SIZE). [ActionCluster.LAYOUT_HEIGHT] is referenced
 * directly (its own guard test pins it to the real cluster height), so the reservation can't silently
 * drift from the cluster it is meant to clear.
 */
class MinimapLayoutTest {
    // --- AC#1: the panel is anchored flush into the top-right corner -------------------------------

    @Test
    fun `panel is anchored flush to the top-right corner`() {
        val rect = panel(vpWidth = MIN_VP_WIDTH, vpHeight = MIN_VP_HEIGHT)
        // Right edge sits exactly MARGIN in from the right of the viewport.
        assertEquals("AC#1: right edge flush at vpWidth - margin", MIN_VP_WIDTH - MARGIN, rect.x + rect.width, EPS)
        // Top edge sits exactly MARGIN down from the top of the viewport.
        assertEquals("AC#1: top edge flush at vpHeight - margin", MIN_VP_HEIGHT - MARGIN, rect.y + rect.height, EPS)
    }

    // --- AC#2/#3: at the minimum supported size the panel clears every bottom control + settings ----

    @Test
    fun `at the minimum supported size the fitted panel clears the controls and settings`() {
        val rect = panel(vpWidth = MIN_VP_WIDTH, vpHeight = MIN_VP_HEIGHT)

        // Fitted side: (vpHeight - margin - reservedBottom - gap) clamped — lands in (minSize, maxSize).
        // UC26 shrank the arc footprint 336 -> 304, so reservedBottom drops 360 -> 328 and the fitted side
        // grows 140 -> 172 (= 540 - 24 - 328 - 16, still inside [120, 180]).
        assertEquals("fitted side at the 540-world floor", 172f, rect.width, EPS)
        assertTrue("fitted side stays within [MIN_SIZE, DEFAULT_SIZE]", rect.width in MIN_SIZE..DEFAULT_SIZE)

        // AC#3: clear of the action cluster on the right edge (the worst-case 304-tall right control).
        assertFalse("AC#3: panel must not overlap the action cluster", rect.overlaps(actionClusterRight(MIN_VP_WIDTH)))
        // AC#2: clear of the joystick too (whichever control handedness puts on the right edge).
        assertFalse("AC#2: panel must not overlap the joystick", rect.overlaps(joystickRight(MIN_VP_WIDTH)))
        // AC#2: clear of the relocated top-left settings/handedness button.
        assertFalse("AC#2: panel must not overlap the relocated settings button", rect.overlaps(settingsLeftBand(MIN_VP_HEIGHT)))

        // The bottom edge sits exactly CONTROL_GAP above the reserved control band when not clamped.
        assertEquals("panel bottom is gap-clear of the reserved band", RESERVED_BOTTOM + CONTROL_GAP, rect.y, EPS)
    }

    @Test
    fun `the full default size is retained at a tall viewport`() {
        val rect = panel(vpWidth = 1280f, vpHeight = 720f)
        assertEquals("a tall viewport keeps the full DEFAULT_SIZE panel", DEFAULT_SIZE, rect.width, EPS)
        // Still corner-anchored and still clear of the controls at the larger size.
        assertEquals(720f - MARGIN, rect.y + rect.height, EPS)
        assertFalse(rect.overlaps(actionClusterRight(1280f)))
        assertFalse(rect.overlaps(joystickRight(1280f)))
        assertFalse(rect.overlaps(settingsLeftBand(720f)))
    }

    // --- Floor guard: the clamp's minimum can never re-introduce an overlap at the supported floor --

    @Test
    fun `the minSize floor fits within the budget at the minimum supported size`() {
        // The available vertical budget for the panel above the reserved band, at the 540-world floor.
        val budget = MIN_VP_HEIGHT - MARGIN - RESERVED_BOTTOM - CONTROL_GAP
        // If minSize ever exceeded the budget, coerceIn would clamp UP to minSize and push the panel's
        // bottom edge down into the reserved control band — re-introducing the very overlap UC22 fixes.
        // Pinning minSize <= budget guarantees the clamp floor is safe at every supported size.
        assertTrue(
            "MIN_SIZE ($MIN_SIZE) must fit the budget ($budget) so the clamp floor can't overlap the controls",
            MIN_SIZE <= budget,
        )
        // And concretely: even clamped to the floor, the bottom edge stays clear of the reserved band.
        val flooredBottom = MIN_VP_HEIGHT - MARGIN - MIN_SIZE
        assertTrue("a floor-clamped panel still clears the reserved band", flooredBottom >= RESERVED_BOTTOM)
    }

    // --- Rect.overlaps contract: edge-touching is NOT an overlap -----------------------------------

    @Test
    fun `overlaps treats edge-touching as non-overlap`() {
        val a = Rect(0f, 0f, 10f, 10f)
        // Sharing the right/left edge (x == a.x + a.width) is flush, not overlapping.
        assertFalse("right-edge touch is not an overlap", a.overlaps(Rect(10f, 0f, 10f, 10f)))
        // Sharing the top/bottom edge is flush, not overlapping.
        assertFalse("top-edge touch is not an overlap", a.overlaps(Rect(0f, 10f, 10f, 10f)))
        // A genuine interior intersection IS an overlap.
        assertTrue("an interior intersection is an overlap", a.overlaps(Rect(5f, 5f, 10f, 10f)))
    }

    // --- AC#3 (drift guard): the reservation source equals the real cluster height -----------------

    @Test
    fun `ActionCluster LAYOUT_HEIGHT is the expected 304 reservation`() {
        // The minimap reserves bottomControlBand() = MARGIN + max(JOYSTICK_SIZE, LAYOUT_HEIGHT). If the
        // cluster grows/shrinks, this constant must track it (its prefHeight equality is pinned in the
        // GL-bound source guard); pinning the value here catches a silent drift in the reservation.
        // UC26: the arc footprint is RADIUS (240) + BUTTON_DIAMETER (64) = 304 (was a 336-tall stack).
        assertEquals("ActionCluster.LAYOUT_HEIGHT must stay 304 (RADIUS 240 + BUTTON_DIAMETER 64)", 304f, ActionCluster.LAYOUT_HEIGHT, EPS)
        // And it must be the larger of the two bottom controls, i.e. the value that drives the reserve.
        assertTrue("the cluster is the worst-case bottom control", ActionCluster.LAYOUT_HEIGHT >= JOYSTICK_SIZE)
        assertEquals("reservedBottom is MARGIN + the worst-case control", RESERVED_BOTTOM, MARGIN + ActionCluster.LAYOUT_HEIGHT, EPS)
    }

    private companion object {
        const val EPS = 1e-3f

        // World-unit mirrors of PlayScreen / MinimapRenderer production constants.
        const val MARGIN = 24f
        const val JOYSTICK_SIZE = 220f
        const val HUD_BLOCK_HEIGHT = 104f
        const val SETTINGS_WIDTH = 200f
        const val SETTINGS_HEIGHT = 56f
        const val MIN_SIZE = 120f
        const val CONTROL_GAP = 16f
        const val DEFAULT_SIZE = 180f

        // bottomControlBand() = MARGIN + max(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT) = 24 + 336.
        val RESERVED_BOTTOM = MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

        // Minimum supported viewport: 1080p landscape at UiScale.factor = 2 → 1920x1080 px ÷ 2 = world.
        const val MIN_VP_WIDTH = 960f
        const val MIN_VP_HEIGHT = 540f

        // UC26: the action arc footprint is a (RADIUS + BUTTON_DIAMETER) square, so its width equals
        // LAYOUT_WIDTH (304). Referenced directly so the test can't drift from the real cluster footprint.
        val CLUSTER_WIDTH = ActionCluster.LAYOUT_WIDTH

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

        /** The action cluster anchored to the RIGHT edge (worst case vs the top-right minimap). */
        fun actionClusterRight(vpWidth: Float): Rect =
            Rect(vpWidth - MARGIN - CLUSTER_WIDTH, MARGIN, CLUSTER_WIDTH, ActionCluster.LAYOUT_HEIGHT)

        /** The movement joystick anchored to the RIGHT edge (the other control handedness may put there). */
        fun joystickRight(vpWidth: Float): Rect = Rect(vpWidth - MARGIN - JOYSTICK_SIZE, MARGIN, JOYSTICK_SIZE, JOYSTICK_SIZE)

        /** The relocated settings/handedness button, centred in the top-LEFT band (UC22). */
        fun settingsLeftBand(vpHeight: Float): Rect {
            val bandBottom = RESERVED_BOTTOM
            val bandTop = vpHeight - HUD_BLOCK_HEIGHT
            val y = (bandBottom + bandTop) / 2f - SETTINGS_HEIGHT / 2f
            return Rect(MARGIN, y, SETTINGS_WIDTH, SETTINGS_HEIGHT)
        }
    }
}
