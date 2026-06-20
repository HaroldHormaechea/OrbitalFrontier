package com.orbitalfrontier.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC33 (a destruction / game-over screen that
 * freezes the sim, reports the consequences, and requires a deliberate CONTINUE before respawning). The
 * pure gate is covered behaviourally in [com.orbitalfrontier.render.DestructionStateTest] and the pure
 * consequence summary in [com.orbitalfrontier.combat.DestructionSummaryTest]; the glue that *uses* them —
 * the per-frame sim gate nested under the pause gate, the modal overlay z-order, the durable critical
 * flush, the hidden HUD/controls, the no-prior-dock fallback — lives in libGDX-touching code the headless
 * backend cannot construct, so the structural contract is pinned at the source level (mirrors
 * [Uc32PauseOverlayGuardTest]).
 *
 * ACs covered here (structural/wiring half):
 *  - **AC#1** — a pending destruction freezes the deterministic advance (nested under the pause gate);
 *    the modal overlay is shown and the flight controls / pause button hide while it is pending.
 *  - **AC#2** — the overlay reports cargo lost, the credit/insurance penalty, and the respawn location;
 *    the summary is built from the respawn result.
 *  - **AC#3** — a single CONTINUE button clears the gate (un-freezes + hides the overlay next frame).
 *  - **AC#4** — the respawn is durably persisted at the moment of destruction via `onCriticalEvent(`.
 *  - **fallback** — destroyed before the first dock respawns at `START_SECTOR` / `Vec2.ZERO`, labelled
 *    from the authored sector `displayName` (never hardcoded).
 */
class Uc33DestructionScreenGuardTest {
    // --- AC#1: the destruction gate freezes the sim, NESTED under the pause gate --------------------

    @Test
    fun `the destruction gate is nested inside the pause gate and skips the advance while pending`() {
        val render = section(PLAY_SCREEN_SOURCE, "override fun render(")
        // The pause branch is the outer gate; the destruction check sits inside it so the two compose
        // (while EITHER is raised, no game time passes — AC#1).
        val pauseBranch = between(render, "if (!paused) {", "}")
        assertTrue(
            "AC#1: the destruction gate is read inside the `if (!paused)` branch (nested, composes with pause)",
            pauseBranch.contains("!destructionState.isPending"),
        )
        assertTrue(
            "AC#1: the per-frame advance runs only when no destruction is pending",
            pauseBranch.contains("advanceSimulation(dt)"),
        )
        // The advance call appears exactly once, behind the nested gate.
        assertEquals(
            "AC#1: advanceSimulation(dt) is called exactly once in render()",
            1,
            Regex("""advanceSimulation\(""").findAll(render).count(),
        )
    }

    @Test
    fun `the flight controls, settings ball and settings modal hide while a destruction is pending`() {
        val renderFrame = section(PLAY_SCREEN_SOURCE, "private fun renderFrame(")
        assertTrue(
            "AC#1: the controls-hidden flag includes a pending destruction",
            renderFrame.contains("val controlsHidden = mapOpen || paused || destructionState.isPending"),
        )
        // UC56: the on-screen PAUSE button was removed (nothing to suppress there); instead the top-left
        // Settings ball and the settings modal each fold !destructionState.isPending into their own
        // visibility, so neither shows under the destruction screen.
        assertTrue(
            "AC#1 (UC56): the Settings ball is hidden under a pending destruction",
            renderFrame.contains("settingsBall.isVisible = !combat.active && !mapOpen && !paused && !destructionState.isPending"),
        )
        assertTrue(
            "AC#1 (UC56): the settings modal is forced hidden under a pending destruction",
            renderFrame.contains("settingsOverlay.actor.isVisible = settingsModalShown && !destructionState.isPending"),
        )
        assertTrue(
            "AC#1: the modal destruction overlay shows exactly while one is pending",
            renderFrame.contains("destructionOverlay.actor.isVisible = destructionState.isPending"),
        )
    }

    @Test
    fun `the modal destruction overlay is added to the stage last for the top z-order`() {
        val pauseOverlayIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(pauseOverlay.actor)")
        val destructionOverlayIdx = PLAY_SCREEN_SOURCE.indexOf("stage.addActor(destructionOverlay.actor)")
        assertTrue("the destruction overlay actor is added to the stage", destructionOverlayIdx >= 0)
        assertTrue(
            "AC#1: the destruction overlay is added AFTER the pause overlay (very top z-order)",
            destructionOverlayIdx > pauseOverlayIdx,
        )
        // The backdrop swallows every tap so no flight control underneath fires while frozen.
        assertTrue(
            "AC#1: the modal backdrop is a (consuming) tap-swallowing ClickListener",
            DESTRUCTION_OVERLAY_SOURCE.contains("backdrop.addListener(") && DESTRUCTION_OVERLAY_SOURCE.contains("ClickListener()"),
        )
    }

    // --- AC#2: the overlay reports the consequences, built from the respawn result -----------------

    @Test
    fun `the overlay reports ship-destroyed, cargo lost, the penalty and the respawn location`() {
        assertTrue("AC#2: the overlay titles the game-over moment", DESTRUCTION_OVERLAY_SOURCE.contains("\"SHIP DESTROYED\""))
        assertTrue("AC#2: the overlay reports cargo lost", DESTRUCTION_OVERLAY_SOURCE.contains("Cargo lost:"))
        assertTrue("AC#2: the overlay reports the insurance/credit penalty", DESTRUCTION_OVERLAY_SOURCE.contains("Insurance:"))
        assertTrue("AC#2: the overlay reports the respawn location", DESTRUCTION_OVERLAY_SOURCE.contains("Respawn:"))
    }

    @Test
    fun `respawn builds the summary from the respawn result and shows it on the overlay`() {
        val respawn = section(PLAY_SCREEN_SOURCE, "private fun respawnPlayer(")
        assertTrue(
            "AC#2: the consequence summary is built from the respawn result + resolved location name",
            respawn.contains("DestructionSummary.from(result, destination.name)"),
        )
        assertTrue("AC#2: the overlay's readout is refreshed from the summary", respawn.contains("destructionOverlay.setSummary(summary)"))
        assertTrue(
            "AC#1: the gate is raised pending on that summary",
            respawn.contains("destructionState = destructionState.pending(summary)"),
        )
    }

    // --- AC#3: a deliberate CONTINUE clears the gate -----------------------------------------------

    @Test
    fun `the CONTINUE button is wired to clear the gate`() {
        assertTrue(
            "AC#3: the overlay's CONTINUE tap routes to continueAfterDestruction()",
            PLAY_SCREEN_SOURCE.contains("destructionOverlay.onContinue = { continueAfterDestruction() }"),
        )
        assertTrue(
            "AC#3: the overlay offers a single CONTINUE button",
            DESTRUCTION_OVERLAY_SOURCE.contains("TextButton(\"CONTINUE\""),
        )
        val cont = section(PLAY_SCREEN_SOURCE, "private fun continueAfterDestruction(")
        assertTrue(
            "AC#3: acknowledging CONTINUE clears the pending gate (un-freezes the sim)",
            cont.contains("destructionState = destructionState.cleared()"),
        )
    }

    // --- AC#4: the respawn is durably persisted at the moment of destruction -----------------------

    @Test
    fun `respawn durably flushes the save via onCriticalEvent`() {
        val respawn = section(PLAY_SCREEN_SOURCE, "private fun respawnPlayer(")
        assertTrue(
            "AC#4: the respawn is committed durably (blocking flush) before the player acknowledges it",
            respawn.contains("autosave.onCriticalEvent("),
        )
        // A held stick/FIRE is neutralised so it cannot sit live under the frozen overlay (mirrors openPause).
        assertTrue("pitfall: a held input is released — Scene2D touch focus is cancelled", respawn.contains("stage.cancelTouchFocus()"))
        assertTrue(
            "pitfall + audio: the looping THRUST cue is stopped under the frozen overlay",
            respawn.contains("audio.stopSfx(Sfx.THRUST)"),
        )
    }

    // --- fallback: no prior dock respawns at the game-start point ----------------------------------

    @Test
    fun `the no-prior-dock fallback respawns at the game-start sector and origin`() {
        val resolve = section(PLAY_SCREEN_SOURCE, "private fun resolveRespawnLocation(")
        assertTrue(
            "fallback: a player with no prior dock falls back to the game-start sector",
            resolve.contains("MvpSectorMap.START_SECTOR"),
        )
        assertTrue("fallback: the start respawn is at the origin (where a new game spawns)", resolve.contains("Vec2.ZERO"))
        assertTrue(
            "fallback: the location label is read from the authored world, never hardcoded",
            resolve.contains(".displayName"),
        )
    }

    @Test
    fun `the destruction overlay is disposed with the screen`() {
        val dispose = section(PLAY_SCREEN_SOURCE, "override fun dispose(")
        assertTrue(
            "the destruction overlay (which owns its backdrop texture) is disposed",
            dispose.contains("destructionOverlay.dispose()"),
        )
    }

    private companion object {
        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val DESTRUCTION_OVERLAY_SOURCE: String = readSource("screen/controls/DestructionOverlay.kt")

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * 4-space indentation — enough to scope an assertion to one declaration without a full parser
         * (mirrors [Uc32PauseOverlayGuardTest.section]).
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
         * or a git worktree). Hard-fails rather than passing silently (mirrors [Uc32PauseOverlayGuardTest]).
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
                    "the UC33 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
