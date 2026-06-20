package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC23 (tap the minimap to open a full-height,
 * 80%-opaque zoomed map overlay; any tap dismisses it). The deterministic halves are covered
 * behaviourally in the pure [com.orbitalfrontier.render.MapOverlayStateTest] (open/close toggle) and
 * [com.orbitalfrontier.render.MapOverlayLayoutTest] (full-height geometry, projection, opacity + LIVE
 * contracts). The glue that *uses* those — Scene2D actors + a `ShapeRenderer` overlay — lives in
 * libGDX-touching code the headless backend cannot construct, so the structural contract is pinned at
 * the source level, mirroring the repo's existing guards ([Uc22MinimapTopRightGuardTest],
 * [Uc21MainMenuGuardTest]).
 *
 * ACs covered here (structural/wiring half):
 *  - **AC#1** — an invisible minimap tap-target actor, laid out from the SHARED
 *    [com.orbitalfrontier.render.MinimapRenderer.panelRect] geometry (no duplicated literals), carries
 *    a `ClickListener` that toggles the overlay open.
 *  - **AC#3** — [com.orbitalfrontier.render.MapOverlayRenderer] draws a full-screen backdrop at
 *    `BACKDROP_ALPHA` and projects markers via [com.orbitalfrontier.render.MapOverlayLayout].
 *  - **AC#5** — a full-screen dismiss actor, gated visible on the overlay-open state and consuming its
 *    tap (`ClickListener`), dismisses the overlay (no trap).
 *  - **AC#2/#3 (draw order)** — the overlay is drawn AFTER `stage.draw()`, on top of the (hidden)
 *    gameplay controls; while open every gameplay control is hidden (incl. `settingsOverlay ... && !mapOpen`).
 *  - **AC#4 + UC10** — the overlay's marker filter mirrors the minimap's revealed-contacts predicate,
 *    so it never reveals unscanned contacts.
 *  - **AC#6** — the simulation step is NOT gated on the overlay state: opening the map is LIVE.
 */
class Uc23MapOverlayGuardTest {
    // --- AC#1: the minimap tap-target actor toggles the overlay open --------------------------------

    @Test
    fun `the minimap tap-target is laid out from the shared HudControlLayout minimap geometry`() {
        // UC56: every drawn control (incl. the minimap tap target) is laid out from the single pure
        // HudControlLayout; its minimapRect delegates to MinimapLayout.panelRect (the renderer's shared
        // geometry), so the tap target still derives from ONE source — no duplicated corner literals.
        assertTrue(
            "AC#1 (UC56): the control layout is computed from the shared HudControlLayout",
            PLAY_SCREEN_SOURCE.contains("HudControlLayout.compute(screenWidth, screenHeight, handedness)"),
        )
        assertTrue(
            "AC#1 (UC56): the tap target is sized/placed from the shared controls.minimap rect",
            PLAY_SCREEN_SOURCE.contains(
                "minimapTapTarget.setBounds(controls.minimap.x, controls.minimap.y, controls.minimap.width, controls.minimap.height)",
            ),
        )
        assertTrue(
            "AC#1 (UC56): HudControlLayout's minimap rect delegates to the shared panelRect geometry",
            HUD_CONTROL_LAYOUT_SOURCE.contains("MinimapLayout.panelRect("),
        )
    }

    @Test
    fun `tapping the minimap target toggles the overlay open via a ClickListener`() {
        val tapTarget = between(PLAY_SCREEN_SOURCE, "minimapTapTarget.addListener(", "mapDismissActor.isVisible = false")
        assertTrue("AC#1: the tap target carries a ClickListener", tapTarget.contains("ClickListener()"))
        assertTrue(
            "AC#1: a minimap tap toggles the overlay open",
            tapTarget.contains("mapOverlayState = mapOverlayState.toggled()"),
        )
    }

    // --- AC#5: a full-screen dismiss actor, gated on open, consumes its tap and dismisses -----------

    @Test
    fun `the dismiss actor is full-screen and gated visible on the overlay-open state`() {
        assertTrue(
            "AC#5: the dismiss actor covers the whole stage",
            PLAY_SCREEN_SOURCE.contains("mapDismissActor.setBounds(0f, 0f, screenWidth, screenHeight)"),
        )
        assertTrue(
            "AC#5: the dismiss actor is visible only while the overlay is open",
            PLAY_SCREEN_SOURCE.contains("mapDismissActor.isVisible = mapOpen"),
        )
    }

    @Test
    fun `tapping the dismiss actor dismisses the overlay via a consuming ClickListener`() {
        // End anchor is the first stage.addActor(...) after the listener block. (UC26 removed the old
        // `actionCluster.actor.pack()` anchor — the arc now uses a fixed footprint, not a packed Table.)
        val dismiss = between(PLAY_SCREEN_SOURCE, "mapDismissActor.addListener(", "stage.addActor(joystick.actor)")
        // A ClickListener consumes touchDown by default, so the tap never leaks through to a flight
        // control under the 80%-opaque backdrop.
        assertTrue("AC#5: the dismiss actor carries a (consuming) ClickListener", dismiss.contains("ClickListener()"))
        assertTrue(
            "AC#5: any tap while open dismisses the overlay (no trap)",
            dismiss.contains("mapOverlayState = mapOverlayState.dismissed()"),
        )
    }

    @Test
    fun `the dismiss actor is added last so it has the top z-order`() {
        // It must catch taps over everything (including the minimap) while the overlay is open.
        val tapIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(minimapTapTarget)")
        val dismissIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(mapDismissActor)")
        assertTrue("AC#5: both overlay actors are added to the stage", tapIdx >= 0 && dismissIdx >= 0)
        assertTrue("AC#5: the dismiss actor is added after the tap target (top z)", dismissIdx > tapIdx)
    }

    // --- AC#2/#3: the overlay is drawn after stage.draw(), with controls hidden while open ----------

    @Test
    fun `the overlay is drawn after stage_draw on top of the hidden controls`() {
        // UC32 split render() into the gated advanceSimulation(dt) + the both-states renderFrame(...); the
        // draw lives in renderFrame now, so scope the draw-order assertions there.
        val render = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        val stageDrawIdx = render.indexOf("stage.draw()")
        val overlayIdx = render.indexOf("mapOverlay.render(")
        assertTrue("AC#2/#3: stage.draw() is present in the render path", stageDrawIdx >= 0)
        assertTrue("AC#2/#3: the overlay is drawn in the render path", overlayIdx >= 0)
        assertTrue("AC#2/#3: the overlay is drawn AFTER stage.draw()", overlayIdx > stageDrawIdx)
        assertTrue(
            "AC#2/#3: the overlay draw is gated on the overlay-open state",
            render.contains("if (mapOpen) {") && render.contains("mapOverlay.render("),
        )
    }

    @Test
    fun `gameplay controls are hidden while the overlay is open`() {
        // UC56: control-visibility lives in renderFrame(...). The Settings ball hides while the map overlay
        // is open (part of `!mapOpen`); the joystick + action cluster hide via the `controlsHidden` flag,
        // which includes `mapOpen`.
        val render = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        assertTrue(
            "AC#2 (UC56): the Settings ball hides while the map overlay is open (and combat / pause / destruction)",
            render.contains("settingsBall.isVisible = !combat.active && !mapOpen && !paused && !destructionState.isPending"),
        )
        assertTrue(
            "AC#2 (UC56): controls are hidden when the map overlay is open (controlsHidden includes mapOpen)",
            render.contains("val controlsHidden = mapOpen || paused || destructionState.isPending"),
        )
        assertTrue(
            "AC#2: the joystick is hidden while the overlay is open",
            render.contains("joystick.actor.isVisible = false"),
        )
        assertTrue(
            "AC#2: the action cluster is hidden while the overlay is open",
            render.contains("actionCluster.actor.isVisible = false"),
        )
    }

    // --- AC#6: opening the overlay is LIVE — the simulation step is NOT gated on the overlay --------

    @Test
    fun `the simulation step is not gated on the overlay state`() {
        // UC32 extracted the per-frame advance into advanceSimulation(dt); the physics step lives there now.
        val advance = section(PLAY_SCREEN_SOURCE, "private fun advanceSimulation(")
        assertTrue("AC#6: the per-frame physics step runs in advanceSimulation()", advance.contains("physics.step(dt)"))
        // The advance is called every frame in render(); UC32 gates it ONLY on the pause state. The map
        // overlay must NOT short-circuit or skip the simulation: render() carries no early-out and no
        // `if (!mapOpen)` wrapper around the advance — `mapOpen` may ONLY gate control visibility + the draw.
        val render = section(PLAY_SCREEN_SOURCE, "override fun render(")
        assertTrue("AC#6: render() advances the sim every frame (LIVE map overlay)", render.contains("advanceSimulation(dt)"))
        assertFalse(
            "AC#6: render() must not early-return when the map is open",
            Regex("""if\s*\(\s*mapOpen\s*\)\s*return""").containsMatchIn(render),
        )
        assertFalse(
            "AC#6: the simulation must not be skipped while the overlay is open",
            Regex("""if\s*\(\s*!\s*mapOpen\s*\)""").containsMatchIn(render),
        )
    }

    // --- AC#3/#4 + UC10: the renderer draws a full-screen backdrop, projects + filters like minimap --

    @Test
    fun `the overlay renderer draws a full-screen backdrop at BACKDROP_ALPHA`() {
        assertTrue(
            "AC#3: a full-screen backdrop rect is drawn",
            MAP_OVERLAY_RENDERER_SOURCE.contains("shapeRenderer.rect(0f, 0f, viewportWidth, viewportHeight)"),
        )
        assertTrue(
            "AC#3: the backdrop colour uses MapOverlayLayout.BACKDROP_ALPHA",
            MAP_OVERLAY_RENDERER_SOURCE.contains("BACKDROP_ALPHA"),
        )
    }

    @Test
    fun `the overlay renderer projects markers through MapOverlayLayout`() {
        assertTrue(
            "AC#4: markers are projected by the pure MapOverlayLayout (zoom geometry)",
            MAP_OVERLAY_RENDERER_SOURCE.contains("MapOverlayLayout.project("),
        )
    }

    @Test
    fun `the overlay marker filter mirrors the minimap revealed-contacts predicate`() {
        // UC10: a Transponder (gate/station) always draws; a hidden contact only once its id is in
        // revealedContacts. The overlay must use the SAME predicate so it never reveals unscanned POIs.
        val predicate = "poi !is Transponder && poi.id !in revealedContacts"
        assertTrue(
            "AC#4/UC10: the overlay uses the minimap's revealed-contacts filter",
            MAP_OVERLAY_RENDERER_SOURCE.contains(predicate),
        )
        assertTrue(
            "the minimap predicate this mirrors still exists (guards against silent drift)",
            MINIMAP_RENDERER_SOURCE.contains(predicate),
        )
    }

    private companion object {
        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val HUD_CONTROL_LAYOUT_SOURCE: String = readSource("render/HudControlLayout.kt")
        private val MAP_OVERLAY_RENDERER_SOURCE: String = readSource("render/MapOverlayRenderer.kt")
        private val MINIMAP_RENDERER_SOURCE: String = readSource("render/MinimapRenderer.kt")

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * indentation — enough to scope an assertion to one declaration without a full parser (mirrors
         * [Uc22MinimapTopRightGuardTest.section]).
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

        /** The slice of [source] from [from] up to the next occurrence of [to] (exclusive). */
        private fun between(
            source: String,
            from: String,
            to: String,
        ): String {
            val start = source.indexOf(from)
            if (start < 0) throw AssertionError("Could not locate '$from' in source")
            val end = source.indexOf(to, start)
            if (end < 0) throw AssertionError("Could not locate '$to' after '$from' in source")
            return source.substring(start, end)
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
                    "the UC23 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
