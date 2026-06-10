package com.orbitalfrontier.screen.controls

import com.orbitalfrontier.settings.ScreenSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Pure (libGDX-free, JVM-only) geometry contract for the UC26 bottom-corner semicircular action arc,
 * [ActionArcLayout.compute]. The arc replaces the retired vertical action stack: FIRE is pinned to a
 * fixed span endpoint and the contextual buttons (DOCK/MINE/SCAN/RADIO/point-and-go) spread evenly and
 * reflow as availability changes, so the visible count ranges 1..6 (6 only in a debug build).
 *
 * This suite locks the invariants the on-device rendering relies on, exercised across that whole 1..6
 * range at the 960x540 world floor (the minimum supported size, see [com.orbitalfrontier.render.MinimapLayout]):
 *  - **AC#1/#7 (non-overlap)** — every adjacent button-centre chord is >= D + MIN_GAP, INCLUDING the
 *    FIRE -> nearest-contextual pair, so no two circular buttons collide at any visible count.
 *  - **AC#1/#9 (in-bounds)** — every button rect stays within [MARGIN, viewport - MARGIN] on both axes.
 *  - **AC#3/#7 (FIRE anchored)** — FIRE's centre is byte-identical for N = 1 and N = 6, so it never
 *    moves as contextual buttons appear/disappear.
 *  - **AC#1 (handedness mirror)** — the LEFT arc is the exact horizontal mirror of the RIGHT arc.
 *  - **AC#9 (stable footprint)** — the arc bounds are the (radius + diameter) square, independent of count.
 *
 * The remaining "renders correctly on a device" half of these ACs is GL-bound and verified by the
 * mandatory visual acceptance gate (a separate emulator pass), not here.
 */
class ActionArcLayoutTest {
    // --- AC#1/#7: adjacent-centre chord >= D + MIN_GAP for every pair, at every visible count ---------

    @Test
    fun `every adjacent button pair clears D plus MIN_GAP at counts 1 through 6`() {
        for (side in ScreenSide.entries) {
            for (count in 1..MAX_BUTTONS) {
                val arc = arc(side, count)
                for (i in 0 until count - 1) {
                    val a = arc.buttons[i]
                    val b = arc.buttons[i + 1]
                    val chord = hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble()).toFloat()
                    // i == 0 is exactly the FIRE -> nearest-contextual pair the AC calls out explicitly.
                    assertTrue(
                        "side=$side count=$count pair($i,${i + 1}) chord=$chord must clear D+MIN_GAP=${BUTTON_DIAMETER + MIN_GAP}",
                        chord >= BUTTON_DIAMETER + MIN_GAP - EPS,
                    )
                }
            }
        }
    }

    // Note: the buttons render as CIRCLES of diameter D, so the non-overlap contract is the centre-to-
    // centre chord (>= D + MIN_GAP, asserted above), NOT the axis-aligned bounding boxes — two circles
    // whose centres are > D apart on a diagonal still have overlapping square bounding boxes, which is
    // expected and harmless. The chord invariant is the one the on-device hit-test relies on.

    // --- AC#1/#9: every button rect is inside [MARGIN, viewport - MARGIN] on both axes ---------------

    @Test
    fun `every button stays within the margins at the 960x540 floor for both counts and sides`() {
        for (side in ScreenSide.entries) {
            for (count in 1..MAX_BUTTONS) {
                val arc = arc(side, count)
                for ((i, rect) in arc.buttons.withIndex()) {
                    assertTrue("side=$side count=$count button $i left < MARGIN", rect.x >= MARGIN - EPS)
                    assertTrue("side=$side count=$count button $i bottom < MARGIN", rect.y >= MARGIN - EPS)
                    assertTrue(
                        "side=$side count=$count button $i right past viewport-MARGIN",
                        rect.right <= VP_WIDTH - MARGIN + EPS,
                    )
                    assertTrue(
                        "side=$side count=$count button $i top past viewport-MARGIN",
                        rect.top <= VP_HEIGHT - MARGIN + EPS,
                    )
                }
            }
        }
    }

    // --- AC#3/#7: FIRE (index 0) is pinned — identical centre for N = 1 and N = 6 --------------------

    @Test
    fun `FIRE centre is identical for one button and the full count`() {
        for (side in ScreenSide.entries) {
            val solo = arc(side, 1).buttons[0]
            val full = arc(side, MAX_BUTTONS).buttons[0]
            assertEquals("side=$side: FIRE centreX must not move as contextual buttons appear", solo.centerX, full.centerX, EPS)
            assertEquals("side=$side: FIRE centreY must not move as contextual buttons appear", solo.centerY, full.centerY, EPS)
        }
        // And the contextual buttons DO move (the arc genuinely reflows) — guards against a degenerate
        // layout that pins everything. Compare button index 1 at N = 2 vs N = 6.
        val near2 = arc(ScreenSide.RIGHT, 2).buttons[1]
        val near6 = arc(ScreenSide.RIGHT, MAX_BUTTONS).buttons[1]
        assertTrue(
            "a contextual button must reflow as the count changes",
            kotlin.math.abs(near2.centerX - near6.centerX) > EPS,
        )
    }

    // --- AC#1: the LEFT arc is the exact horizontal mirror of the RIGHT arc --------------------------

    @Test
    fun `the left-handed arc mirrors the right-handed arc about the vertical centre line`() {
        for (count in 1..MAX_BUTTONS) {
            val right = arc(ScreenSide.RIGHT, count)
            val left = arc(ScreenSide.LEFT, count)
            for (i in 0 until count) {
                val r = right.buttons[i]
                val l = left.buttons[i]
                // Mirror about x = VP_WIDTH / 2: a centre at right.cx maps to VP_WIDTH - right.cx.
                assertEquals("count=$count button $i: centreX must mirror", VP_WIDTH - r.centerX, l.centerX, EPS)
                assertEquals("count=$count button $i: centreY must be preserved under mirror", r.centerY, l.centerY, EPS)
            }
        }
    }

    // --- AC#9: the footprint is the (radius + diameter) square, independent of the visible count ------

    @Test
    fun `the arc bounds are the fixed radius-plus-diameter square regardless of count`() {
        val footprint = RADIUS + BUTTON_DIAMETER
        for (side in ScreenSide.entries) {
            val reference = arc(side, 1).bounds
            assertEquals("footprint width == radius + diameter", footprint, reference.width, EPS)
            assertEquals("footprint height == radius + diameter", footprint, reference.height, EPS)
            for (count in 1..MAX_BUTTONS) {
                val bounds = arc(side, count).bounds
                assertEquals("side=$side count=$count bounds.x drifted", reference.x, bounds.x, EPS)
                assertEquals("side=$side count=$count bounds.y drifted", reference.y, bounds.y, EPS)
                assertEquals("side=$side count=$count bounds.width drifted", reference.width, bounds.width, EPS)
                assertEquals("side=$side count=$count bounds.height drifted", reference.height, bounds.height, EPS)
            }
        }
    }

    // --- UiRect.overlaps contract: edge-touching is NOT an overlap (the disjointness primitive) ------

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
        // Mirror the production arc parameters (ActionCluster's companion constants) so the geometry under
        // test matches the real laid-out arc; the screen-space frame is the 960x540 minimum supported floor.
        const val MARGIN = 24f
        const val VP_WIDTH = 960f
        const val VP_HEIGHT = 540f
        const val RADIUS = ActionCluster.RADIUS
        const val BUTTON_DIAMETER = ActionCluster.BUTTON_DIAMETER
        const val MIN_GAP = ActionCluster.MIN_GAP
        const val SPAN_START = ActionCluster.SPAN_START_DEGREES
        const val SPAN_END = ActionCluster.SPAN_END_DEGREES

        // FIRE + DOCK + MINE + SCAN + RADIO + debug point-and-go = the worst-case 6.
        const val MAX_BUTTONS = 6
        const val EPS = 1e-3f

        /** Compute the screen-space arc at the supported floor for [side] and [count] visible buttons. */
        fun arc(
            side: ScreenSide,
            count: Int,
        ): ActionArcLayout.Arc =
            ActionArcLayout.compute(
                side = side,
                viewportWidth = VP_WIDTH,
                viewportHeight = VP_HEIGHT,
                margin = MARGIN,
                radius = RADIUS,
                buttonDiameter = BUTTON_DIAMETER,
                spanStartDegrees = SPAN_START,
                spanEndDegrees = SPAN_END,
                count = count,
            )
    }
}
