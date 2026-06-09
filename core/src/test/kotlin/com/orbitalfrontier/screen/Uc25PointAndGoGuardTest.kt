package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** + **release-safety** wiring of UC25 (a debug-only
 * point-and-go navigation aid). The deterministic halves are covered behaviourally by the pure
 * [com.orbitalfrontier.debugnav.PointAndGoStateTest] (the arm/disarm gate) and
 * [com.orbitalfrontier.debugnav.PointAndGoTest] (the destination resolver). The glue that *uses* those —
 * a Scene2D arm button, an [com.badlogic.gdx.InputMultiplexer] world-tap processor, and the per-frame
 * teleport application against the Box2D body — lives in libGDX-touching code the headless backend
 * cannot construct, so the structural contract is pinned at the source level, mirroring the repo's
 * existing guards ([Uc23MapOverlayGuardTest], [Uc22MinimapTopRightGuardTest]).
 *
 * ACs covered here (release-safety + isolation half):
 *  - **AC#4 (gating)** — PlayScreen constructs the panel/button/tap-processor ONLY inside `if (debug)`;
 *    `debug` defaults `false` in both the PlayScreen and OrbitalFrontierGame constructors; AndroidLauncher
 *    passes `BuildConfig.DEBUG`; the android module enables `buildConfig`. So a release build compiles
 *    none of it in and tap handling stays byte-for-byte unchanged.
 *  - **AC#4 (no tap hijack)** — the world-tap processor is APPENDED to the multiplexer (`addProcessor`,
 *    with the stage holding first priority), NOT installed via `setProcessors`; and its `touchDown`
 *    returns `false` while disarmed, so normal taps fall through to the existing controls/stage.
 *  - **AC#5 (record/replay + autosave isolation)** — the render() teleport path applies the resolution
 *    via `physics.resetTo` only; it does NOT call `autosave.onEvent`, and the teleport is NOT routed
 *    through the joystick or `model.update` (so it is never recorded as flight input).
 *  - **AC#4 (map overlay)** — the debug arm panel hides with the other controls while the map is open.
 */
class Uc25PointAndGoGuardTest {
    // --- AC#4: the feature is constructed ONLY under `if (debug)` ------------------------------------

    @Test
    fun `the panel and button are only allocated in a debug build`() {
        assertTrue(
            "AC#4: the arm panel is null unless debug",
            PLAY_SCREEN_SOURCE.contains("pointAndGoPanel: Table? = if (debug) Table() else null"),
        )
        assertTrue(
            "AC#4: the arm button is null unless debug",
            PLAY_SCREEN_SOURCE.contains("pointAndGoButton: TextButton? = if (debug) TextButton("),
        )
    }

    @Test
    fun `all point-and-go wiring lives inside the debug-gated init block`() {
        val initBlock = between(PLAY_SCREEN_SOURCE, "if (debug) {", "override fun show()")
        assertTrue(
            "AC#4: the arm button listener is wired only in debug",
            initBlock.contains("pointAndGoState = pointAndGoState.toggled()"),
        )
        assertTrue("AC#4: the arm panel is added to the stage only in debug", initBlock.contains("stage.addActor(panel)"))
        assertTrue("AC#4: the world-tap processor is registered only in debug", initBlock.contains("inputMultiplexer.addProcessor("))
    }

    @Test
    fun `debug defaults false in both the PlayScreen and game constructors`() {
        assertTrue(
            "AC#4: PlayScreen.debug defaults false (release/tests are release-safe)",
            PLAY_SCREEN_SOURCE.contains("private val debug: Boolean = false,"),
        )
        assertTrue(
            "AC#4: OrbitalFrontierGame.debug defaults false",
            GAME_SOURCE.contains("private val debug: Boolean = false,"),
        )
    }

    @Test
    fun `the launcher passes BuildConfig_DEBUG and the android module enables buildConfig`() {
        assertTrue(
            "AC#4: AndroidLauncher gates the feature on the build variant",
            LAUNCHER_SOURCE.contains("debug = BuildConfig.DEBUG"),
        )
        assertTrue("AC#4: BuildConfig is imported", LAUNCHER_SOURCE.contains("import com.orbitalfrontier.BuildConfig"))
        // BuildConfig.DEBUG only exists if the android module generates BuildConfig.
        assertTrue("AC#4: the android module enables buildConfig generation", ANDROID_BUILD_SOURCE.contains("buildConfig = true"))
    }

    // --- AC#4: the world-tap processor is APPENDED and inert while disarmed -------------------------

    @Test
    fun `the world-tap processor is appended to the multiplexer, never installed via setProcessors`() {
        // The stage is constructed first in the multiplexer so flight controls keep first crack at every
        // touch; the point-and-go processor is APPENDED after it. setProcessors would replace the chain
        // and hijack input — it must never be used.
        assertTrue(
            "AC#4: the stage holds first input priority in the multiplexer",
            PLAY_SCREEN_SOURCE.contains("InputMultiplexer(stage)"),
        )
        assertTrue(
            "AC#4: the world-tap processor is appended (not replacing the chain)",
            PLAY_SCREEN_SOURCE.contains("inputMultiplexer.addProcessor("),
        )
        assertFalse(
            "AC#4: setProcessors is never called (it would replace the chain and hijack taps)",
            PLAY_SCREEN_SOURCE.contains("setProcessors("),
        )
    }

    @Test
    fun `the world-tap processor returns false while disarmed`() {
        assertTrue(
            "AC#4: a disarmed point-and-go lets normal taps fall through to the controls/stage",
            PLAY_SCREEN_SOURCE.contains("if (!pointAndGoState.armed) return false"),
        )
    }

    // --- AC#5: the teleport path is isolated from autosave + the record/replay harness --------------

    @Test
    fun `the render teleport path applies via physics_resetTo and resolves through the pure resolver`() {
        val teleportBlock = teleportBlock()
        assertTrue("AC#5: the teleport uses the pure PointAndGo resolver", teleportBlock.contains("PointAndGo.resolve("))
        assertTrue(
            "AC#5: the teleport is applied via the sanctioned transform-set path (ADR 0005)",
            teleportBlock.contains("physics.resetTo(resolution.kinematics)"),
        )
    }

    @Test
    fun `the teleport does not touch autosave or the recorded-flight path`() {
        val teleportBlock = teleportBlock()
        // A manual debug teleport must NOT be persisted as a save event...
        assertFalse(
            "AC#5: the teleport must not enqueue an autosave event",
            teleportBlock.contains("autosave.onEvent"),
        )
        // ...nor routed through the joystick / movement model, which is what the record/replay harness
        // captures as flight input. The teleport sets the body directly and is invisible to the sim.
        assertFalse(
            "AC#5: the teleport must not be routed through the joystick (would be recorded as input)",
            teleportBlock.contains("joystick"),
        )
        assertFalse(
            "AC#5: the teleport must not go through model.update (the recorded flight path)",
            teleportBlock.contains("model.update"),
        )
    }

    // --- bug fix: the panel Y is floored via PointAndGoPanelPlacement, not bottomControlBand() -------

    @Test
    fun `positionPointAndGoPanel delegates placement to PointAndGoPanelPlacement_place`() {
        val placement = section(PLAY_SCREEN_SOURCE, "private fun positionPointAndGoPanel(")
        assertTrue(
            "bug fix: positionPointAndGoPanel computes the panel rect via the pure placement helper",
            placement.contains("PointAndGoPanelPlacement.place("),
        )
    }

    @Test
    fun `positionPointAndGoPanel no longer derives the panel Y from bottomControlBand`() {
        val placement = section(PLAY_SCREEN_SOURCE, "private fun positionPointAndGoPanel(")
        // The bug was anchoring the panel Y to bottomControlBand() (the TOP of the bottom band), which
        // floated the toggle's hit-rect above the usable world area. The fix floors it at MARGIN via
        // PointAndGoPanelPlacement instead, so the placement function must not reference the band at all.
        assertFalse(
            "bug fix: the panel placement must not be anchored to bottomControlBand() any more",
            placement.contains("bottomControlBand()"),
        )
        assertTrue(
            "bug fix: the panel is floored at the bottom MARGIN (passed through to the placement helper)",
            placement.contains("margin = MARGIN"),
        )
    }

    // --- AC#4: the arm panel hides with the other controls while the map overlay is open ------------

    @Test
    fun `the debug arm panel hides while the map overlay is open`() {
        val render = section(PLAY_SCREEN_SOURCE, "override fun render(")
        assertTrue(
            "AC#4: the arm panel hides with the rest of the controls while the map is open",
            render.contains("pointAndGoPanel?.isVisible = false"),
        )
    }

    private companion object {
        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val GAME_SOURCE: String = readSource("app/OrbitalFrontierGame.kt")
        private val LAUNCHER_SOURCE: String =
            readFile(
                "android/src/main/kotlin/com/orbitalfrontier/android/AndroidLauncher.kt",
                "src/main/kotlin/com/orbitalfrontier/android/AndroidLauncher.kt",
            )
        private val ANDROID_BUILD_SOURCE: String = readFile("android/build.gradle.kts")

        /** The render()-scoped slice covering the one-shot teleport consumption at the top of the frame. */
        private fun teleportBlock(): String = between(PLAY_SCREEN_SOURCE, "val teleport = pendingTeleport", "UC07 fuel burn")

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * indentation — enough to scope an assertion to one declaration without a full parser (mirrors
         * [Uc23MapOverlayGuardTest.section]).
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

        /** A `core`-module source file under com/orbitalfrontier/, located via [readFile]. */
        private fun readSource(relative: String): String =
            readFile(
                "src/main/kotlin/com/orbitalfrontier/$relative",
                "core/src/main/kotlin/com/orbitalfrontier/$relative",
            )

        /**
         * Locates a file by walking up from the test working directory and trying each [candidates]
         * relative path at every ancestor (handles running from the module dir, the repo root, or a git
         * worktree). Hard-fails rather than passing silently if the file cannot be found (mirrors the
         * repo's existing source-anchored guards).
         */
        private fun readFile(vararg candidates: String): String {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate any of ${candidates.toList()} from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC25 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
