package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC22 (move the minimap to the top-right corner,
 * off the action buttons). The deterministic geometry of the placement is covered behaviourally in the
 * pure [com.orbitalfrontier.render.MinimapLayoutTest]; the glue that *uses* that geometry lives in
 * libGDX-touching code that the headless backend cannot construct:
 *
 *  - [com.orbitalfrontier.render.MinimapRenderer.render] issues real `ShapeRenderer`/`Gdx.gl` calls.
 *  - [com.orbitalfrontier.screen.PlayScreen] builds a live `Stage` and scene2d actors in its field
 *    initializers.
 *
 * Production must not be reshaped just to make those headlessly constructible, so the structural
 * contract for these ACs is pinned at the source level, mirroring the repo's existing guards
 * ([Uc21MainMenuGuardTest], [Uc20StationGridGuardTest], [Uc19WalkaroundGuardTest]).
 *
 * ACs covered here (structural half):
 *  - **AC#1** — the panel is anchored top-right: [MinimapLayout.panelRect] derives `x` from the right
 *    edge (`vpWidth -`) and `y` from the top (`vpHeight -`), NOT bottom-left (`originY = margin`).
 *  - **AC#2/#3** — the renderer plumbs `reservedBottom` through `panelRect`, and PlayScreen feeds it
 *    `bottomControlBand()` (= `MARGIN + max(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)`), so the panel
 *    is sized to clear the worst-case bottom controls on either handedness.
 *  - **AC#2 (reconcile neighbours)** — the displaced settings/handedness button moves to the top-LEFT
 *    band (no longer the top-right corner the minimap now owns) and is hidden while `combat.active`, so
 *    it cannot collide with the minimap or the combat ship schematic.
 *  - **AC#3 (drift guard)** — `ActionCluster.LAYOUT_HEIGHT` is the single reservation source and is
 *    derived from the cluster's own button/pad constants, so the reservation tracks the real cluster.
 */
class Uc22MinimapTopRightGuardTest {
    // --- AC#1: top-right anchoring lives in the pure MinimapLayout geometry ------------------------

    @Test
    fun `panelRect anchors the panel into the top-right corner`() {
        val panelRect = section(LAYOUT_SOURCE, "fun panelRect(")
        assertTrue(
            "AC#1: x must be anchored to the RIGHT edge (vpWidth - margin - size)",
            panelRect.contains("vpWidth - margin - size"),
        )
        assertTrue(
            "AC#1: y must be anchored to the TOP edge (vpHeight - margin - size)",
            panelRect.contains("vpHeight - margin - size"),
        )
    }

    @Test
    fun `the renderer no longer bottom-left anchors the panel`() {
        // The old placement pinned the panel to the bottom-left with `originY = marginPx`. After UC22 the
        // origin must come from the fitted, top-anchored MinimapLayout rect — never a raw bottom margin.
        assertFalse(
            "AC#1: the renderer must not bottom-anchor the panel via `originY = marginPx`",
            Regex("""originY\s*=\s*marginPx""").containsMatchIn(RENDERER_SOURCE),
        )
        assertTrue(
            "AC#1: the panel origin is derived from the top-anchored MinimapLayout rect",
            RENDERER_SOURCE.contains("rect.y * uiScale"),
        )
    }

    // --- AC#2/#3: the renderer fits the panel above the reserved bottom controls -------------------

    @Test
    fun `the renderer fits the panel via MinimapLayout, plumbing reservedBottom`() {
        val render = section(RENDERER_SOURCE, "fun render(")
        assertTrue("AC#2/#3: the render() signature accepts reservedBottom", render.contains("reservedBottom: Float"))
        assertTrue(
            "AC#1/#2: the renderer sizes/places the panel through MinimapLayout.panelRect",
            render.contains("MinimapLayout.panelRect"),
        )
        assertTrue(
            "AC#2/#3: reservedBottom is plumbed into panelRect (the control clearance)",
            render.contains("reservedBottom = reservedBottom"),
        )
    }

    @Test
    fun `PlayScreen feeds the worst-case bottom control band as reservedBottom`() {
        assertTrue(
            "AC#2/#3: PlayScreen passes bottomControlBand() as the minimap's reservedBottom",
            PLAY_SCREEN_SOURCE.contains("reservedBottom = bottomControlBand()"),
        )
        val band = section(PLAY_SCREEN_SOURCE, "private fun bottomControlBand()")
        assertTrue(
            "AC#3: the reservation is MARGIN + the worst-case (tallest) bottom control",
            band.contains("MARGIN + maxOf(JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)"),
        )
    }

    // --- AC#2: the displaced settings button moves to the top-left band and hides in combat --------

    @Test
    fun `the settings button is relocated to the top-left band, not the top-right corner`() {
        val layout = section(PLAY_SCREEN_SOURCE, "private fun layoutControls()")
        // Placed at the LEFT margin (the minimap now owns the top-right corner).
        assertTrue(
            "AC#2: the settings button is positioned at the LEFT margin",
            layout.contains("settingsOverlay.actor.setPosition("),
        )
        // Centred in the clear band between the HUD readout block (top) and the bottom control band.
        assertTrue("AC#2: the band's top is below the HUD block", layout.contains("screenHeight - HUD_BLOCK_HEIGHT"))
        assertTrue("AC#2: the band's bottom is the reserved control band", layout.contains("bottomControlBand()"))
        assertTrue(
            "AC#2: the settings button is centred in the top-left band",
            layout.contains("leftBandBottom") && layout.contains("leftBandTop"),
        )
    }

    @Test
    fun `the relocated settings button is hidden during combat`() {
        // Its band overlaps the combat-only ship schematic, so it must hide while an encounter is live —
        // keyed on the same combat.active flag as the schematic (an invisible scene2d actor also stops
        // receiving touch, so nothing leaks through it).
        assertTrue(
            "AC#2: the settings button hides while combat is active",
            PLAY_SCREEN_SOURCE.contains("settingsOverlay.actor.isVisible = !combat.active"),
        )
    }

    // --- AC#3: ActionCluster.LAYOUT_HEIGHT is the single, derived reservation source ----------------

    @Test
    fun `ActionCluster LAYOUT_HEIGHT is derived from the cluster's own constants`() {
        assertTrue(
            "AC#3: LAYOUT_HEIGHT is a const reservation source on ActionCluster",
            ACTION_CLUSTER_SOURCE.contains("const val LAYOUT_HEIGHT"),
        )
        // It must be COMPUTED from the same constants that build the Table rows, not a hardcoded literal,
        // so the reservation can never silently drift from the real laid-out cluster height.
        val layoutHeight = section(ACTION_CLUSTER_SOURCE, "const val LAYOUT_HEIGHT")
        for (token in listOf("FIRE_SIZE", "BUTTON_PAD", "PLACEHOLDER_BUTTON_COUNT", "BUTTON_SIZE")) {
            assertTrue("AC#3: LAYOUT_HEIGHT must be derived from $token", layoutHeight.contains(token))
        }
    }

    private companion object {
        private val LAYOUT_SOURCE: String = readSource("render/MinimapLayout.kt")
        private val RENDERER_SOURCE: String = readSource("render/MinimapRenderer.kt")
        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val ACTION_CLUSTER_SOURCE: String = readSource("screen/controls/ActionCluster.kt")

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * indentation — enough to scope an assertion to one declaration without a full parser (mirrors
         * [Uc21MainMenuGuardTest.section] / [Uc20StationGridGuardTest]).
         */
        private fun section(
            source: String,
            header: String,
        ): String {
            val start = source.indexOf(header)
            if (start < 0) throw AssertionError("Could not locate '$header' in source")
            val rest = source.substring(start)
            val end = Regex("""\n {4}}""").find(rest)?.range?.last ?: rest.length
            return rest.substring(0, end)
        }

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Hard-fails rather than passing silently if the file cannot be found
         * (mirrors the repo's existing source-anchored guards).
         */
        private fun readSource(relative: String): String {
            val candidates =
                listOf(
                    "src/main/kotlin/com/orbitalfrontier/$relative",
                    "core/src/main/kotlin/com/orbitalfrontier/$relative",
                )
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC22 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
