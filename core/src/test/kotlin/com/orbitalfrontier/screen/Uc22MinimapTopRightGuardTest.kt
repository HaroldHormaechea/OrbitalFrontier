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
        // UC23 extracted the panel geometry into the renderer's public `panelRect(...)` method — the
        // single geometry source now shared by this draw and PlayScreen's minimap tap-target — which
        // delegates to MinimapLayout.panelRect. So render() sizes/places the panel via that shared
        // method, and the renderer (as a whole) still routes through the pure MinimapLayout geometry.
        assertTrue(
            "AC#1/#2: render() sizes/places the panel via the shared panelRect(...) method",
            render.contains("panelRect("),
        )
        assertTrue(
            "AC#1/#2: the renderer still delegates panel geometry to MinimapLayout.panelRect",
            RENDERER_SOURCE.contains("MinimapLayout.panelRect"),
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
    fun `the settings control is relocated to the top-left, not the top-right corner`() {
        val layout = section(PLAY_SCREEN_SOURCE, "private fun layoutControls()")
        // UC56: the displaced settings control is now the top-left Settings BALL, positioned from the
        // single pure HudControlLayout (the minimap owns the top-right corner). Its rect is top-left
        // anchored (HudControlLayout.settingsBallRect → x = SETTINGS_BALL_MARGIN), so it is never placed
        // in the minimap's top-right corner.
        assertTrue(
            "AC#2 (UC56): the whole control layout is computed from the shared HudControlLayout",
            layout.contains("HudControlLayout.compute(screenWidth, screenHeight, handedness)"),
        )
        assertTrue(
            "AC#2 (UC56): the Settings ball is positioned from HudControlLayout's top-left settingsBall rect",
            layout.contains("settingsBall.actor.setPosition(controls.settingsBall.x, controls.settingsBall.y)"),
        )
    }

    @Test
    fun `the relocated settings ball is hidden during combat`() {
        // UC56: the settings control is the top-left ball; it shares its band with the combat-only ship
        // schematic, so it must hide while an encounter is live — keyed on the same combat.active flag (an
        // invisible scene2d actor also stops receiving touch). It also hides under map / pause / destruction.
        assertTrue(
            "AC#2 (UC56): the Settings ball hides on combat.active (and map / pause / destruction)",
            PLAY_SCREEN_SOURCE.contains(
                "settingsBall.isVisible = !combat.active && !mapOpen && !paused && !destructionState.isPending",
            ),
        )
    }

    // --- AC#3: ActionCluster.LAYOUT_HEIGHT is the single, derived reservation source ----------------

    @Test
    fun `ActionCluster LAYOUT_HEIGHT is derived from the cluster's own constants`() {
        assertTrue(
            "AC#3: LAYOUT_HEIGHT is a const reservation source on ActionCluster",
            ACTION_CLUSTER_SOURCE.contains("const val LAYOUT_HEIGHT"),
        )
        // It must be COMPUTED from the constants that define the arc footprint, not a hardcoded literal,
        // so the reservation can never silently drift from the real laid-out cluster height. UC26 replaced
        // the old stacked-Table derivation (FIRE_SIZE/BUTTON_PAD/rows) with the arc footprint: the
        // (RADIUS + BUTTON_DIAMETER) square that bounds the full sweep regardless of visible-button count.
        val layoutHeight = section(ACTION_CLUSTER_SOURCE, "const val LAYOUT_HEIGHT")
        for (token in listOf("RADIUS", "BUTTON_DIAMETER")) {
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
