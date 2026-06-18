package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** structural contract of UC26 (the bottom-corner semicircular
 * action arc). The pure geometry is covered behaviourally by
 * [com.orbitalfrontier.screen.controls.ActionArcLayoutTest]; the glue that *wires* every player action
 * onto the arc lives in [com.orbitalfrontier.screen.PlayScreen]'s libGDX-touching field initializers,
 * `init` block and `render()`, which the headless backend cannot construct. So — exactly as the repo's
 * other screen guards do ([Uc22MinimapTopRightGuardTest], [Uc25PointAndGoGuardTest]) — the wiring is
 * pinned at the source level.
 *
 * ACs covered here (structural half):
 *  - **AC#3** — FIRE is always visible and enabled, INCLUDING during combat: the [ActionCluster] seeds
 *    FIRE available and refuses to toggle it off, the arc is hidden only while the map overlay is open
 *    (never on `combat.active`), and FIRE drives the combat tick via `isFirePressed()`.
 *  - **AC#5** — all five previously-panel-based actions (DOCK/MINE/SCAN/RADIO/point-and-go) move onto the
 *    arc; the standalone `position*Panel` context panels are all removed.
 *  - **AC#6** — each contextual action is availability-gated (`setActionAvailable(... , <in-range?>)`),
 *    and the debug point-and-go button is enabled only inside `if (debug)`.
 *  - **AC#8** — DOCK/SCAN/RADIO are edge-triggered via the arc callbacks; MINE is read held each frame.
 */
class Uc26ActionArcGuardTest {
    // --- AC#3: FIRE is always available and never toggled off ---------------------------------------

    @Test
    fun `the ActionCluster seeds FIRE available and refuses to toggle it off`() {
        assertTrue(
            "AC#3: FIRE is the one action seeded available from the start",
            ACTION_CLUSTER_SOURCE.contains("Action.entries.associateWith { it == Action.FIRE }"),
        )
        val setAvailable = section(ACTION_CLUSTER_SOURCE, "fun setActionAvailable(")
        assertTrue(
            "AC#3: setActionAvailable early-returns for FIRE, so it can never be hidden/disabled",
            setAvailable.contains("if (action == Action.FIRE) return"),
        )
    }

    @Test
    fun `the arc hides only with the map overlay, never during combat`() {
        // The arc-visibility block keys on `controlsHidden = mapOpen || paused` (UC32 added the pause
        // case). The settings button hides on combat.active (its band overlaps the combat schematic) — the
        // arc deliberately does NOT, so FIRE survives an encounter (AC#3). Pinning the absence of `combat`
        // in this block is what makes that explicit.
        val visibility = between(PLAY_SCREEN_SOURCE, "UC26: the whole action arc", "mapDismissActor.isVisible = mapOpen")
        assertTrue("AC#3: the arc hides while the map is open", visibility.contains("actionCluster.actor.isVisible = false"))
        assertTrue("AC#3: the arc is shown again when the map is closed", visibility.contains("actionCluster.actor.isVisible = true"))
        // Inspect only the CODE after the comment block (the comment legitimately mentions combat); the
        // executable visibility branch must key on `controlsHidden` (mapOpen || paused), never on combat.
        val visibilityCode = visibility.substringAfter("if (controlsHidden)")
        assertFalse(
            "AC#3: the arc visibility must NOT be gated on combat (FIRE stays visible in an encounter)",
            visibilityCode.contains("combat"),
        )
    }

    @Test
    fun `FIRE drives the combat tick via isFirePressed`() {
        assertTrue(
            "AC#3: the per-frame combat input reads FIRE as a held action off the arc",
            PLAY_SCREEN_SOURCE.contains("if (actionCluster.isFirePressed()) FireAction.FIRE"),
        )
    }

    // --- AC#5: the five standalone context panels are retired in favour of arc buttons --------------

    @Test
    fun `all five standalone context-panel position functions are removed`() {
        for (panel in listOf(
            "positionDockPanel(",
            "positionMinePanel(",
            "positionScanPanel(",
            "positionRadioPanel(",
            "positionPointAndGoPanel(",
        )) {
            assertFalse("AC#5: the retired context panel $panel must no longer exist", PLAY_SCREEN_SOURCE.contains(panel))
        }
    }

    @Test
    fun `every action is wired onto the arc`() {
        // AC#8: the three edge-triggered actions latch their one-shot intent via the arc's tap callbacks...
        assertTrue("AC#5/#8: DOCK wired onto the arc", PLAY_SCREEN_SOURCE.contains("actionCluster.onDock ="))
        assertTrue("AC#5/#8: SCAN wired onto the arc", PLAY_SCREEN_SOURCE.contains("actionCluster.onScan ="))
        assertTrue("AC#5/#8: RADIO accept wired onto the arc", PLAY_SCREEN_SOURCE.contains("actionCluster.onAcceptRadio ="))
        // ...and the two held actions are read each frame off the arc.
        assertTrue("AC#5/#8: MINE read held off the arc", PLAY_SCREEN_SOURCE.contains("actionCluster.isMinePressed()"))
        assertTrue("AC#5/#8: FIRE read held off the arc", PLAY_SCREEN_SOURCE.contains("actionCluster.isFirePressed()"))
    }

    // --- AC#6: contextual actions are availability-gated; point-and-go is debug-gated ---------------

    @Test
    fun `each contextual action is availability-gated by its in-range predicate`() {
        assertTrue(
            "AC#6: DOCK shows only while a dockable station is in range",
            PLAY_SCREEN_SOURCE.contains("actionCluster.setActionAvailable(ActionCluster.Action.DOCK, available != null)"),
        )
        assertTrue(
            "AC#6: MINE shows only while an asteroid field is in range",
            PLAY_SCREEN_SOURCE.contains("actionCluster.setActionAvailable(ActionCluster.Action.MINE, field != null)"),
        )
        assertTrue(
            "AC#6: RADIO shows only while a radio offer is in range",
            PLAY_SCREEN_SOURCE.contains("actionCluster.setActionAvailable(ActionCluster.Action.RADIO, radioOffer != null)"),
        )
    }

    @Test
    fun `the point-and-go arc button is enabled only inside the debug-gated block`() {
        val initBlock = between(PLAY_SCREEN_SOURCE, "if (debug) {", "override fun show()")
        assertTrue(
            "AC#6: the debug point-and-go button is enabled only in a debug build",
            initBlock.contains("actionCluster.setActionAvailable(ActionCluster.Action.POINT_AND_GO, true)"),
        )
    }

    private companion object {
        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val ACTION_CLUSTER_SOURCE: String = readSource("screen/controls/ActionCluster.kt")

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * indentation — enough to scope an assertion to one declaration without a full parser (mirrors
         * the repo's existing source-anchored guards).
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
         * Locates a `core` production source file by walking up from the test working directory and trying
         * each candidate relative path at every ancestor (handles running from the module dir, the repo
         * root, or a git worktree). Hard-fails rather than passing silently if the file cannot be found.
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
                    "the UC26 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
