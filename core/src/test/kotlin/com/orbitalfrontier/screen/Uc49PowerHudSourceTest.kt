package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC49 — the power-brownout wiring inside [PlayScreen] (AC#1/#3/#4), the
 * power fields the pure [com.orbitalfrontier.render.HudViewModel] carries, and the PWR/BROWNOUT
 * readout the [com.orbitalfrontier.render.HudRenderer] draws (AC#3).
 *
 * Why source-anchored, not behavioural: [PlayScreen] builds live libGDX objects in its field
 * initializers (camera, `SpriteBatch`/`ShapeRenderer`, scene2d actors), so it needs a real GL context
 * and is NOT headlessly constructible — the wiring cannot be driven from a plain JVM unit test, and
 * production must not be reshaped just to test it. The *derivation* logic is pure and proven for real:
 * the budget resolver in [com.orbitalfrontier.power.BrownoutTest], the HUD power fields in
 * [com.orbitalfrontier.render.HudViewModelTest], and the end-to-end shed effects in
 * [com.orbitalfrontier.playthrough.Uc49PowerBrownoutReplayTest]. Here we pin the GL-bound wiring at the
 * source level, mirroring the repo's existing guards ([Uc44CombatHudSourceTest], `Uc34ExpandedHudGuardTest`).
 *
 * All assertions run over **comment-stripped CODE** (block + line comments removed), so a doc comment
 * that merely names `Brownout`/`brownout`/`PWR` is prose and never satisfies the guard — only real code
 * does. The contract pinned:
 *  - AC#1/#4: PlayScreen resolves the tick's budget via `Brownout.resolve(thrusting, powerParams)` off the
 *    SAME `thrusting` bool fed to fuel burn (the lockstep mirror of the test-set Simulation).
 *  - AC#4: the resolved brownout gates the shared seams — `scannerPowered = brownout.isPowered(SCANNER)`
 *    into the scan, `weaponsPowered = brownout.isPowered(WEAPONS)` into combat fire.
 *  - AC#3: the snapshot is fed into `HudViewModel.build(...)`; the pure view-model carries the power
 *    fields derived from the [com.orbitalfrontier.power.BrownoutResult]; the renderer draws the PWR gauge
 *    and a BROWNOUT cue.
 */
class Uc49PowerHudSourceTest {
    // --- AC#1/#4: PlayScreen resolves the budget off the fuel-burn `thrusting` bool (lockstep) --------

    @Test
    fun `PlayScreen resolves the tick brownout from the same thrusting bool as fuel burn`() {
        assertTrue(
            "PlayScreen must resolve the budget via Brownout.resolve(thrusting, powerParams) (AC#1/#4 lockstep)",
            PLAY_SCREEN.contains(Regex("""Brownout\.resolve\s*\(\s*thrusting\s*,\s*powerParams\s*\)""")),
        )
    }

    // --- AC#4: the resolved brownout gates the shared scan + combat seams ------------------------------

    @Test
    fun `PlayScreen gates scanning on the shed SCANNER`() {
        assertTrue(
            "PlayScreen must pass scannerPowered = brownout.isPowered(PowerSystem.SCANNER) into the scan (AC#4)",
            PLAY_SCREEN.contains(Regex("""scannerPowered\s*=\s*brownout\.isPowered\s*\(\s*PowerSystem\.SCANNER\s*\)""")),
        )
    }

    @Test
    fun `PlayScreen gates combat fire on the shed WEAPONS`() {
        assertTrue(
            "PlayScreen must pass weaponsPowered = brownout.isPowered(PowerSystem.WEAPONS) into Combat.step (AC#4)",
            PLAY_SCREEN.contains(Regex("""weaponsPowered\s*=\s*brownout\.isPowered\s*\(\s*PowerSystem\.WEAPONS\s*\)""")),
        )
    }

    // --- AC#3: the snapshot is fed into the HUD view-model the renderer draws --------------------------

    @Test
    fun `PlayScreen feeds the brownout snapshot into the HUD view-model`() {
        val build = section(PLAY_SCREEN, "HudViewModel.build(")
        assertTrue(
            "PlayScreen must feed `brownout = brownout` into HudViewModel.build (AC#3)",
            build.contains(Regex("""brownout\s*=\s*brownout""")),
        )
    }

    // --- AC#3: the pure view-model carries the power fields, derived from the BrownoutResult -----------

    @Test
    fun `HudViewModel carries the power readout fields`() {
        for (field in listOf("reactorOutput", "powerDraw", "brownout", "shedSystems")) {
            assertTrue(
                "HudViewModel must declare a `val $field` power field (AC#3)",
                HUD_VIEW_MODEL.contains(Regex("""val\s+$field\b""")),
            )
        }
    }

    @Test
    fun `HudViewModel derives the power fields from the brownout snapshot`() {
        val build = section(HUD_VIEW_MODEL, "fun build(")
        val assignments =
            listOf(
                "reactorOutput = brownout.reactorOutput",
                "powerDraw = brownout.totalDemand",
                "brownout = brownout.isBrownout",
                "shedSystems = brownout.shedSystems",
            )
        for (assignment in assignments) {
            assertTrue("HudViewModel.build must set `$assignment` (AC#3)", build.contains(assignment))
        }
    }

    // --- AC#3: the renderer draws the PWR gauge and a BROWNOUT cue -------------------------------------

    @Test
    fun `HudRenderer draws the PWR gauge and a BROWNOUT cue`() {
        val drawn = drawnLabels(HUD_RENDERER)
        assertTrue("HudRenderer must draw a `PWR ` power-gauge label (AC#3)", drawn.any { it.contains("PWR ") })
        assertTrue("HudRenderer must draw a `BROWNOUT` caution cue (AC#3)", drawn.any { it.contains("BROWNOUT") })
    }

    private companion object {
        private val PLAY_SCREEN = stripComments(readSource("screen/PlayScreen.kt"))
        private val HUD_RENDERER = stripComments(readSource("render/HudRenderer.kt"))
        private val HUD_VIEW_MODEL = stripComments(readSource("render/HudViewModel.kt"))

        /**
         * Strip Kotlin block (`/* … */`) and line (`// …`) comments so the guards inspect actual CODE only —
         * a doc comment naming Brownout/brownout/PWR must not satisfy a wiring assertion. Mirrors the
         * comment-stripping in [Uc44CombatHudSourceTest].
         */
        private fun stripComments(source: String): String =
            source
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        /** Every string literal appended into a renderer's draw buffer (the text actually drawn). */
        private fun drawnLabels(source: String): List<String> =
            Regex("""append\("([^"]*)"\)""").findAll(source).map { it.groupValues[1] }.toList()

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * indentation — enough to scope an assertion without a full parser (mirrors `Uc34ExpandedHudGuardTest`).
         */
        private fun section(
            source: String,
            header: String,
        ): String {
            val start = source.indexOf(header)
            if (start < 0) throw AssertionError("Could not locate '$header' in source")
            val rest = source.substring(start)
            val end = Regex("""\n {8}}""").find(rest)?.range?.last ?: rest.length
            return rest.substring(0, end)
        }

        /**
         * Locate a production source by walking up from the test working directory, trying the candidate
         * relative path at every ancestor (handles the module dir, the repo root, or a git worktree). A
         * missing file is a hard error so the guard fails loudly rather than passing vacuously.
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
                    "the UC49 power-HUD source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
