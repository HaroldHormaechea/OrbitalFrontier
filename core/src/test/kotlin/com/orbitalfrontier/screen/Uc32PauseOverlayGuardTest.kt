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
    fun `there is no on-screen pause button and the modal pause overlay shows while paused`() {
        // UC56: the on-screen PAUSE button was REMOVED — pause is reached via the device Back key only
        // (routed in keyDown, asserted below). No PAUSE TextButton and no pauseButton actor remain.
        assertFalse(
            "UC56: there is no on-screen PAUSE TextButton anymore",
            PLAY_SCREEN_SOURCE.contains("TextButton(\"PAUSE\""),
        )
        assertFalse(
            "UC56: there is no pauseButton actor anymore",
            PLAY_SCREEN_SOURCE.contains("pauseButton"),
        )
        val renderFrame = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        // The modal pause overlay shows while paused, EXCEPT while the settings modal is up over it.
        assertTrue(
            "AC#1 (UC56): the modal pause overlay shows while paused, hidden under the settings modal",
            renderFrame.contains("pauseOverlay.actor.isVisible = paused && !settingsModalShown"),
        )
    }

    @Test
    fun `the Android BACK key is caught and routed to settings-close-resume-pause`() {
        // AC#1 / pitfall#2: BACK maps to pause here, not the default screen-back. The catch is enabled in
        // show() (and released in hide(), checked below). UC56: Back is now the ONLY pause trigger.
        val show = section(PLAY_SCREEN_SOURCE, "override fun show(")
        assertTrue(
            "AC#1: show() catches the Android BACK key",
            show.contains("Gdx.input.setCatchKey(Input.Keys.BACK, true)"),
        )
        assertTrue(
            "pitfall#2: the BACK catch is released in hide()",
            PLAY_SCREEN_SOURCE.contains("Gdx.input.setCatchKey(Input.Keys.BACK, false)"),
        )
        // The single keyDown handler routes BACK by precedence: an open settings modal closes FIRST
        // (→ flight or → pause menu per its context), else while paused it resumes, else in flight it pauses.
        val keyDown = between(PLAY_SCREEN_SOURCE, "override fun keyDown(", "return true")
        assertTrue("AC#1: the handler keys on the BACK button", keyDown.contains("Input.Keys.BACK"))
        assertTrue("AC#1 (UC56): an open settings modal closes first", keyDown.contains("closeSettings()"))
        assertTrue("AC#1: while paused BACK resumes", keyDown.contains("resumeGame()"))
        assertTrue("AC#1: in flight BACK opens pause", keyDown.contains("openPause()"))
    }

    // --- AC#3: Resume / Settings / Quit, and the settings sub-view governance ------------------------

    @Test
    fun `the overlay wires resume, settings, quit and back`() {
        assertTrue("AC#3: Resume resumes", PLAY_SCREEN_SOURCE.contains("pauseOverlay.onResume = { resumeGame() }"))
        assertTrue(
            "AC#3 (UC56): Settings opens the shared settings modal from the pause context",
            PLAY_SCREEN_SOURCE.contains("pauseOverlay.onSettings = { openSettingsModal(fromPause = true) }"),
        )
        assertTrue("AC#3: Quit quits to main menu", PLAY_SCREEN_SOURCE.contains("pauseOverlay.onQuit = { quitToMainMenu() }"))
        assertTrue(
            "AC#3: the overlay offers Resume/Settings/Quit",
            PAUSE_OVERLAY_SOURCE.contains("\"RESUME\"") &&
                PAUSE_OVERLAY_SOURCE.contains("\"SETTINGS\"") && PAUSE_OVERLAY_SOURCE.contains("\"QUIT TO MAIN MENU\""),
        )
    }

    @Test
    fun `the settings modal is visible exactly while open and hidden under a destruction screen`() {
        // UC56: the settings panel is now a MODAL gated solely on settingsModalShown (opened by the
        // in-flight Settings ball or the pause-menu SETTINGS action) — no longer a persistent corner panel
        // ANDed with combat/map. A pending destruction screen owns the foreground, so the modal is forced
        // hidden under one. (Pausing mid-combat then opening Settings still surfaces it: the gate is only
        // settingsModalShown, which the ball/pause action sets regardless of combat or map state.)
        val renderFrame = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        assertTrue(
            "AC#3 (UC56): the settings modal shows exactly while open, hidden under a destruction screen",
            renderFrame.contains("settingsOverlay.actor.isVisible = settingsModalShown && !destructionState.isPending"),
        )
        val modalLine = between(renderFrame, "settingsOverlay.actor.isVisible =", "\n")
        assertFalse("AC#3 (UC56): the settings-modal visibility is NOT ANDed with combat", modalLine.contains("combat"))
        assertFalse("AC#3 (UC56): the settings-modal visibility is NOT ANDed with the map overlay", modalLine.contains("mapOpen"))
    }

    @Test
    fun `opening the settings modal flags it shown and brings the settings panel to the front`() {
        // UC56: enterPauseSettings was replaced by openSettingsModal(fromPause); it sets the modal-shown
        // flag and re-parents the settings panel to the front so the pause/HUD backdrop can't swallow taps.
        val open = section(PLAY_SCREEN_SOURCE, "private fun openSettingsModal(")
        assertTrue("AC#3 (UC56): the settings-modal flag is set", open.contains("settingsModalShown = true"))
        assertTrue(
            "AC#3 (UC56): the settings panel is brought to the front over the backdrop",
            open.contains("settingsOverlay.actor.toFront()"),
        )
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
        // UC56: the on-screen pause button was removed, so anchor the z-order against the top-left Settings
        // ball (added with the HUD controls) and the map-overlay actors — the pause overlay must sit above both.
        val settingsBallIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(settingsBall.actor)")
        val overlayIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(pauseOverlay.actor)")
        val mapDismissIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(mapDismissActor)")
        assertTrue("pitfall#1: the pause overlay actor is added to the stage", overlayIdx >= 0)
        assertTrue("pitfall#1: the Settings ball is added to the stage (anchor)", settingsBallIdx >= 0)
        assertTrue("pitfall#1: the pause overlay is added after the HUD controls / Settings ball (top z)", overlayIdx > settingsBallIdx)
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
