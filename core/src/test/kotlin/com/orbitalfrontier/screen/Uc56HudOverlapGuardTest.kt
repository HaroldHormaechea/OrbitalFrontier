package com.orbitalfrontier.screen

import com.orbitalfrontier.notify.NotificationPolicy
import com.orbitalfrontier.render.HudControlLayout
import com.orbitalfrontier.render.HudLayout
import com.orbitalfrontier.render.MinimapLayout
import com.orbitalfrontier.render.NotificationLayout
import com.orbitalfrontier.settings.Handedness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) zero-overlap guard for the UC56 in-flight HUD: every DRAWN persistent
 * control must be pairwise non-overlapping at the supported viewports, on BOTH handedness arrangements.
 *
 * The asserted set is the interactive control layer, read from the single pure source
 * [HudControlLayout.compute] (no mirrored literals) so the guard can never drift from what PlayScreen
 * actually positions:
 *  - the movement joystick, the action-arc footprint, the top-left HUD readout block, the top-right
 *    minimap, and — critically — the Settings ball's **full drawn footprint** ([Layout.settingsBallFootprint]
 *    = icon + caption), NOT the bare 72px icon rect; plus
 *  - the transient UC35 notification toasts ([NotificationLayout.toastRect] × the default policy's
 *    `maxVisible`), which thread the top-centre band and must clear every interactive control.
 *
 * **§5 binding — assert against the shared origin, not a literal.** The Settings-ball→readout clearance is
 * asserted against [HudLayout.BLOCK_X] (the ONE constant both the reservation rect and the HudRenderer
 * draw-origin read), so the guard cannot pass GREEN while the on-screen text still collides with the ball.
 * The caption is bounded to the ball's x-span, so the footprint's right edge IS `BLOCK_X` and edge-touches
 * (clears) the readout block — the earlier "caption wider than the icon, overlapping CR 50000" defect this
 * guard exists to prevent.
 *
 * Deliberately OUT of scope (covered elsewhere): the first-run tutorial hint band — its non-overlap with
 * controls/minimap/toasts is covered by [com.orbitalfrontier.render.TutorialOverlayLayoutTest]; at the
 * 960×540 floor its toast-aware fallback slot shares the readout block's vertical band (a reservation-level
 * tolerance — the drawn copy threads the gap, verified on a live emulator), so it is not asserted here.
 */
class Uc56HudOverlapGuardTest {
    @Test
    fun `every drawn control is pairwise non-overlapping at supported viewports and both handedness`() {
        forEachConfig { vpWidth, vpHeight, handedness ->
            val rects = interactiveRects(vpWidth, vpHeight, handedness)
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val (nameA, a) = rects[i]
                    val (nameB, b) = rects[j]
                    assertTrue(
                        "UC56: '$nameA' ($a) must not overlap '$nameB' ($b) at " +
                            "${vpWidth}x$vpHeight, $handedness",
                        !a.overlaps(b),
                    )
                }
            }
        }
    }

    @Test
    fun `no visible toast overlaps any drawn control at supported viewports and both handedness`() {
        val maxVisible = NotificationPolicy().maxVisible
        forEachConfig { vpWidth, vpHeight, handedness ->
            val rects = interactiveRects(vpWidth, vpHeight, handedness)
            for (index in 0 until maxVisible) {
                val toast = NotificationLayout.toastRect(vpWidth, vpHeight, index)
                for ((name, control) in rects) {
                    assertTrue(
                        "UC56: toast[$index] ($toast) must not overlap '$name' ($control) at " +
                            "${vpWidth}x$vpHeight, $handedness",
                        !toast.overlaps(control),
                    )
                }
            }
        }
    }

    @Test
    fun `the settings ball footprint clears the readout block at the shared BLOCK_X origin`() {
        // §5: the clearance is asserted against the ONE shared constant, never a literal. The footprint's
        // right edge and the readout block's left edge are BOTH HudLayout.BLOCK_X, so they edge-touch
        // (no overlap) by construction — the reservation and the drawn text can never diverge.
        for ((vpWidth, vpHeight) in SUPPORTED_VIEWPORTS) {
            val footprint = HudControlLayout.settingsBallFootprint(vpHeight)
            val block = HudLayout.blockRect(vpWidth, vpHeight)
            assertEquals(
                "§5: the settings ball footprint's right edge is the shared BLOCK_X origin",
                HudLayout.BLOCK_X,
                footprint.x + footprint.width,
                EPS,
            )
            assertEquals(
                "§5: the readout block's left edge is the same shared BLOCK_X origin",
                HudLayout.BLOCK_X,
                block.x,
                EPS,
            )
            assertTrue(
                "§5: footprint and readout block edge-touch at BLOCK_X (no overlap)",
                !footprint.overlaps(block),
            )
            // The footprint stays on-screen (no left-edge clip — the other half of the caption defect).
            assertTrue("UC56: the footprint does not clip the left screen edge", footprint.x >= 0f)
        }
    }

    @Test
    fun `the footprint is the icon extended by the caption band, sharing the icon's x-span`() {
        // Drift guard: the overlap set must use the FULL drawn footprint, not the bare icon. The caption is
        // bounded to the ball's x-span, so the footprint shares the icon's x-range and only extends DOWNWARD
        // by SETTINGS_BALL_CAPTION_BAND (the caption sits below the icon).
        for ((_, vpHeight) in SUPPORTED_VIEWPORTS) {
            val icon = HudControlLayout.settingsBallRect(vpHeight)
            val footprint = HudControlLayout.settingsBallFootprint(vpHeight)
            assertEquals("footprint shares the icon's left edge", icon.x, footprint.x, EPS)
            assertEquals(
                "footprint shares the icon's x-span (caption bounded to the ball width)",
                icon.width,
                footprint.width,
                EPS,
            )
            assertEquals(
                "footprint's top edge matches the icon's top edge",
                icon.y + icon.height,
                footprint.y + footprint.height,
                EPS,
            )
            assertEquals(
                "footprint extends below the icon by the caption band",
                icon.height + HudControlLayout.SETTINGS_BALL_CAPTION_BAND,
                footprint.height,
                EPS,
            )
        }
    }

    private fun interactiveRects(
        vpWidth: Float,
        vpHeight: Float,
        handedness: Handedness,
    ): List<Pair<String, MinimapLayout.Rect>> {
        val layout = HudControlLayout.compute(vpWidth, vpHeight, handedness)
        // Use the FULL settings-ball FOOTPRINT (icon + caption), never the bare icon rect (§5 / caption bug).
        return listOf(
            "joystick" to layout.joystick,
            "actionArc" to layout.actionArc,
            "readoutBlock" to layout.readoutBlock,
            "settingsBallFootprint" to layout.settingsBallFootprint,
            "minimap" to layout.minimap,
        )
    }

    private fun forEachConfig(body: (Float, Float, Handedness) -> Unit) {
        for ((vpWidth, vpHeight) in SUPPORTED_VIEWPORTS) {
            for (handedness in Handedness.values()) {
                body(vpWidth, vpHeight, handedness)
            }
        }
    }

    private companion object {
        const val EPS = 1e-3f

        // World units: the 1080p floor (1920x1080 ÷ UiScale 2) and a larger 1280x720, mirroring the repo's
        // other layout guards (MinimapLayoutTest / TutorialOverlayLayoutTest).
        val SUPPORTED_VIEWPORTS = listOf(960f to 540f, 1280f to 720f)
    }
}
