package com.orbitalfrontier.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC32 (an in-flight pause overlay that freezes
 * the deterministic tick). The pure paused/running toggle is covered behaviourally in
 * [com.orbitalfrontier.render.PauseStateTest]; the glue that *uses* it — the per-frame sim gate, the
 * Scene2D pause overlay/HUD button, the BACK-key routing, held-input neutralisation, the quit-saves
 * ordering — lives in libGDX-touching code the headless backend cannot construct, so the structural
 * contract is pinned at the source level (mirrors [Uc23MapOverlayGuardTest]).
 *
 * The developer extracted the whole per-frame advance into `advanceSimulation(dt)` and gates only the
 * CALL (`if (!paused) { advanceSimulation(dt) }`); `renderFrame(...)` runs in BOTH states. So the AC#2/#5
 * guard asserts the EXTRACTED shape: the advance markers live inside `advanceSimulation` and never in
 * `renderFrame` / render()'s unconditional body.
 *
 * ACs covered here (structural/wiring half):
 *  - **AC#1** — a HUD PAUSE button opens the overlay; the Android BACK key also drives pause/resume.
 *  - **AC#2/#5** — the sole `advanceSimulation(` call site sits inside `if (!paused)`; no advance marker
 *    (`physics.step`, `runCombat`, `autosave.update`, the mission/combat `+= dt` ticks) runs in `renderFrame`.
 *  - **AC#3** — the overlay offers Resume/Settings/Quit; the Settings sub-view reads `pauseSettingsShown`
 *    (NOT ANDed with combat/map) and brings the settings panel to the front (z-order).
 *  - **AC#4** — Quit flushes `autosave.onPauseOrExit()` BEFORE handing off to `onQuitToMainMenu`.
 *  - **pitfalls** — opening pause dismisses the map (one-overlay), cancels Scene2D touch focus + stops the
 *    THRUST cue (held inputs don't stick), and the modal backdrop sits at the top z-order swallowing taps.
 */
class Uc32PauseOverlayGuardTest {
    // --- AC#2/#5: the extracted per-frame advance is gated on the pause state -----------------------

    @Test
    fun `the pause gate is read once per frame in render`() {
        val render = section(PLAY_SCREEN_SOURCE, "override fun render(")
        assertTrue(
            "AC#2: render() reads the pause gate once (val paused = pauseState.isPaused)",
            render.contains("val paused = pauseState.isPaused"),
        )
    }

    @Test
    fun `the sole advanceSimulation call site is gated by if not paused`() {
        val render = section(PLAY_SCREEN_SOURCE, "override fun render(")
        // The whole per-frame advance is one extracted call, and it appears exactly once in render().
        assertEquals(
            "AC#2: advanceSimulation(dt) is called exactly once in render()",
            1,
            Regex("""advanceSimulation\(""").findAll(render).count(),
        )
        // That call is enclosed by the `if (!paused)` branch — while paused no game time passes (AC#2/#5).
        val gated = between(render, "if (!paused) {", "}")
        assertTrue(
            "AC#2/#5: the advanceSimulation(dt) call sits inside the `if (!paused)` branch",
            gated.contains("advanceSimulation(dt)"),
        )
    }

    @Test
    fun `every advance marker lives inside advanceSimulation, never in the both-states render path`() {
        val advance = section(PLAY_SCREEN_SOURCE, "private fun advanceSimulation(")
        val renderFrame = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        val render = section(PLAY_SCREEN_SOURCE, "override fun render(")
        // Markers that advance deterministic game time. They MUST be inside advanceSimulation (the gated
        // path) and MUST NOT appear in renderFrame (runs in BOTH states) nor in render()'s body (only the
        // gate + the gated call + renderFrame call live there). runCombat() is the advance entry for the
        // combat tick; the combat `+= dt` lives inside that method, so it is checked against renderFrame.
        val inAdvance = listOf("physics.step(dt)", "missionTickAccumulator += dt", "runCombat(", "autosave.update(dt)")
        for (marker in inAdvance) {
            assertTrue("AC#2: `$marker` is part of advanceSimulation (the gated advance)", advance.contains(marker))
            assertFalse("AC#5: `$marker` must NOT run in renderFrame (both states)", renderFrame.contains(marker))
            assertFalse("AC#5: `$marker` must NOT run in render()'s unconditional body", render.contains(marker))
        }
        // The combat sub-tick advances game time too and must never run while paused.
        assertFalse(
            "AC#5: the combat `+= dt` tick must NOT run in renderFrame (both states)",
            renderFrame.contains("combatTickAccumulator += dt"),
        )
    }

    // --- AC#1: a HUD PAUSE button + the Android BACK key open/close the overlay ---------------------

    @Test
    fun `a HUD PAUSE button opens the overlay and hides while an overlay is up`() {
        assertTrue(
            "AC#1: a top-centre HUD PAUSE button exists",
            PLAY_SCREEN_SOURCE.contains("pauseButton = TextButton(\"PAUSE\""),
        )
        val pauseListener = between(PLAY_SCREEN_SOURCE, "pauseButton.addListener(", "pauseOverlay.onResume")
        assertTrue("AC#1: tapping the PAUSE button opens the overlay", pauseListener.contains("openPause()"))
        val renderFrame = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        assertTrue(
            "AC#1: the PAUSE button is reachable while running with no overlay, hidden otherwise",
            renderFrame.contains("pauseButton.isVisible = !mapOpen && !paused"),
        )
        assertTrue(
            "AC#1: the modal pause overlay shows exactly while paused",
            renderFrame.contains("pauseOverlay.actor.isVisible = paused"),
        )
    }

    @Test
    fun `the Android BACK key is caught and routed to pause-resume-back`() {
        // AC#1 / pitfall#2: BACK maps to pause here, not the default screen-back. The catch is enabled in
        // show() (and released in hide(), checked below).
        val show = section(PLAY_SCREEN_SOURCE, "override fun show(")
        assertTrue(
            "AC#1: show() catches the Android BACK key",
            show.contains("Gdx.input.setCatchKey(Input.Keys.BACK, true)"),
        )
        assertTrue(
            "pitfall#2: the BACK catch is released in hide()",
            PLAY_SCREEN_SOURCE.contains("Gdx.input.setCatchKey(Input.Keys.BACK, false)"),
        )
        // The single keyDown handler in the file routes BACK to the three pause transitions.
        val keyDown = between(PLAY_SCREEN_SOURCE, "override fun keyDown(", "return true")
        assertTrue("AC#1: the handler keys on the BACK button", keyDown.contains("Input.Keys.BACK"))
        assertTrue("AC#1: in the settings sub-view BACK steps back", keyDown.contains("exitPauseSettings()"))
        assertTrue("AC#1: while paused BACK resumes", keyDown.contains("resumeGame()"))
        assertTrue("AC#1: in flight BACK opens pause", keyDown.contains("openPause()"))
    }

    // --- AC#3: Resume / Settings / Quit, and the settings sub-view governance ------------------------

    @Test
    fun `the overlay wires resume, settings, quit and back`() {
        assertTrue("AC#3: Resume resumes", PLAY_SCREEN_SOURCE.contains("pauseOverlay.onResume = { resumeGame() }"))
        assertTrue(
            "AC#3: Settings opens the settings sub-view",
            PLAY_SCREEN_SOURCE.contains("pauseOverlay.onSettings = { enterPauseSettings() }"),
        )
        assertTrue("AC#3: Quit quits to main menu", PLAY_SCREEN_SOURCE.contains("pauseOverlay.onQuit = { quitToMainMenu() }"))
        assertTrue(
            "AC#3: the overlay offers Resume/Settings/Quit",
            PAUSE_OVERLAY_SOURCE.contains("\"RESUME\"") &&
                PAUSE_OVERLAY_SOURCE.contains("\"SETTINGS\"") && PAUSE_OVERLAY_SOURCE.contains("\"QUIT TO MAIN MENU\""),
        )
    }

    @Test
    fun `the paused settings-visibility branch reads pauseSettingsShown and is not ANDed with combat or map`() {
        val renderFrame = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        assertTrue(
            "AC#3: the settings panel visibility branches on the paused state",
            renderFrame.contains(
                "settingsOverlay.actor.isVisible = if (paused) pauseSettingsShown else (!combat.active && !mapOpen)",
            ),
        )
        // The paused branch (between `if (paused)` and `else`) is JUST pauseSettingsShown — so pausing
        // mid-combat or over the map and then opening Settings still surfaces the panel.
        val pausedBranch = between(renderFrame, "isVisible = if (paused)", "else")
        assertTrue("AC#3: the paused branch reads pauseSettingsShown", pausedBranch.contains("pauseSettingsShown"))
        assertFalse("AC#3: the paused branch is NOT ANDed with combat", pausedBranch.contains("combat"))
        assertFalse("AC#3: the paused branch is NOT ANDed with the map overlay", pausedBranch.contains("mapOpen"))
    }

    @Test
    fun `entering the settings sub-view brings the settings panel to the front`() {
        // z-order: the in-flight settings panel was added BEFORE the pause backdrop, so it must be
        // re-parented to the front, else the backdrop swallows its taps.
        val enter = section(PLAY_SCREEN_SOURCE, "private fun enterPauseSettings(")
        assertTrue("AC#3: the settings sub-view flag is set", enter.contains("pauseSettingsShown = true"))
        assertTrue("AC#3: the settings panel is brought to the front over the backdrop", enter.contains("settingsOverlay.actor.toFront()"))
    }

    // --- AC#4: Quit flushes a durable autosave BEFORE leaving --------------------------------------

    @Test
    fun `quitting saves before handing off to the main menu`() {
        val quit = section(PLAY_SCREEN_SOURCE, "private fun quitToMainMenu(")
        val saveIdx = quit.indexOf("autosave.onPauseOrExit()")
        val handoffIdx = quit.indexOf("onQuitToMainMenu()")
        assertTrue("AC#4: quit flushes a durable autosave", saveIdx >= 0)
        assertTrue("AC#4: quit hands off to the app to rebuild the main menu", handoffIdx >= 0)
        assertTrue("AC#4: the autosave flush happens BEFORE the hand-off (no progress lost)", saveIdx < handoffIdx)
    }

    // --- pitfalls: one-overlay, held-input neutralisation, top-z modal backdrop --------------------

    @Test
    fun `opening pause dismisses the map and neutralises held inputs`() {
        val open = section(PLAY_SCREEN_SOURCE, "private fun openPause(")
        assertTrue("AC#2: opening pause freezes the sim", open.contains("pauseState = pauseState.paused()"))
        assertTrue(
            "pitfall#1: opening pause forces the map overlay dismissed (one overlay at a time)",
            open.contains("mapOverlayState = mapOverlayState.dismissed()"),
        )
        assertTrue(
            "pitfall#3: a held stick/FIRE is released — Scene2D touch focus is cancelled",
            open.contains("stage.cancelTouchFocus()"),
        )
        assertTrue(
            "pitfall#3 + audio: the looping THRUST cue stops so it does not stick on resume",
            open.contains("audio.stopSfx(Sfx.THRUST)"),
        )
    }

    @Test
    fun `the modal pause overlay is added last for the top z-order and swallows taps`() {
        val pauseButtonIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(pauseButton)")
        val overlayIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(pauseOverlay.actor)")
        val mapDismissIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(mapDismissActor)")
        assertTrue("pitfall#1: the pause overlay actor is added to the stage", overlayIdx >= 0)
        assertTrue("pitfall#1: the pause overlay is added after the HUD pause button (top z)", overlayIdx > pauseButtonIdx)
        assertTrue("pitfall#1: the pause overlay is added after the map overlay actors (top z)", overlayIdx > mapDismissIdx)
        // The backdrop image swallows every tap so no flight control underneath fires while frozen.
        assertTrue(
            "pitfall#1: the modal backdrop is a tap-swallowing ClickListener",
            PAUSE_OVERLAY_SOURCE.contains("backdrop.addListener("),
        )
        assertTrue("pitfall#1: the backdrop uses a (consuming) ClickListener", PAUSE_OVERLAY_SOURCE.contains("ClickListener()"))
    }

    @Test
    fun `the pause overlay is disposed with the screen`() {
        val dispose = section(PLAY_SCREEN_SOURCE, "override fun dispose(")
        assertTrue("the pause overlay (which owns its backdrop texture) is disposed", dispose.contains("pauseOverlay.dispose()"))
    }

    private companion object {
        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val PAUSE_OVERLAY_SOURCE: String = readSource("screen/controls/PauseOverlay.kt")

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * 4-space indentation — enough to scope an assertion to one declaration without a full parser
         * (mirrors [Uc23MapOverlayGuardTest.section]).
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
            val end = source.indexOf(to, start + from.length)
            if (end < 0) throw AssertionError("Could not locate '$to' after '$from' in source")
            return source.substring(start, end)
        }

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Hard-fails rather than passing silently (mirrors the repo's existing guards).
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
                    "the UC32 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
