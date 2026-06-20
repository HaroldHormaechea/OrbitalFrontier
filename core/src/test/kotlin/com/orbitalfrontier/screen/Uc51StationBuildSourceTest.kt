package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC51's **build/edit UI wiring** (AC#1) — the deferred-from-UC15 build screen
 * (ADR 0014) replacing the direct default-build action.
 *
 * Why source-anchored, not behavioural: [StationBuildScreen], [StationHubScreen] and
 * [com.orbitalfrontier.app.OrbitalFrontierGame] build live libGDX objects (a `Stage`, scene2d actors) in
 * their field initializers, which need a real GL context — they are NOT headlessly constructible, so the
 * wiring cannot be driven from a plain JVM unit test, and production must not be reshaped just to test it.
 * The *menu logic* is pure and proven for real in [com.orbitalfrontier.station.StationBuildMenuTest]; here
 * we pin the screen-routing at the source level, mirroring the repo's existing guards
 * ([Uc44CombatHudSourceTest], [Uc40EconomyFeedbackSourceTest]).
 *
 * All assertions run over **comment-stripped CODE**, so a doc comment that merely names a symbol is prose
 * and never satisfies the guard. The contract pinned:
 *  - the hub's BUILD action opens the dedicated build screen (no longer a direct default build);
 *  - the build screen lists the pure [com.orbitalfrontier.station.StationBuildMenu] options and a CONFIRM
 *    fires the injected `onBuild(order)` intent;
 *  - the game routes that intent to [PlayScreen.build], which performs the pure `StationBuilder.resolve`.
 */
class Uc51StationBuildSourceTest {
    // --- the hub's BUILD opens the build screen --------------------------------------------------------

    @Test
    fun `the game routes the hub BUILD action to the station build screen`() {
        assertTrue(
            "OrbitalFrontierGame must wire the hub's onBuild to openStationBuildScreen (no direct default build)",
            GAME.contains(Regex("""onBuild\s*=\s*\{\s*openStationBuildScreen\s*\(""")),
        )
        assertTrue(
            "OrbitalFrontierGame must define openStationBuildScreen",
            GAME.contains(Regex("""fun\s+openStationBuildScreen\s*\(""")),
        )
    }

    @Test
    fun `the build screen is fed the pure StationBuildMenu options`() {
        assertTrue(
            "openStationBuildScreen must source its rows from the pure StationBuildMenu.options(...)",
            GAME.contains(Regex("""StationBuildMenu\.options\s*\(""")),
        )
    }

    // --- the build screen renders the menu + CONFIRM fires onBuild(order) ------------------------------

    @Test
    fun `the build screen lists the supplied options and a CONFIRM fires onBuild with the option order`() {
        assertTrue(
            "StationBuildScreen must read its options from the injected optionsSupplier()",
            BUILD_SCREEN.contains(Regex("""optionsSupplier\s*\(\s*\)""")),
        )
        assertTrue(
            "a CONFIRM tap must fire the injected onBuild intent with the option's StationBuildOrder",
            BUILD_SCREEN.contains(Regex("""onBuild\s*\(\s*option\.order\s*\)""")),
        )
    }

    // --- the game routes the build intent to PlayScreen.build (the pure StationBuilder) ----------------

    @Test
    fun `the game routes the build intent to PlayScreen build`() {
        assertTrue(
            "the build screen's onBuild must route to playScreen.build(order)",
            GAME.contains(Regex("""onBuild\s*=\s*\{\s*order[^}]*playScreen\s*\?\.\s*build\s*\(\s*order\s*\)""")),
        )
    }

    @Test
    fun `PlayScreen build performs the pure StationBuilder resolve`() {
        assertTrue(
            "PlayScreen.build must resolve the order through the pure StationBuilder.resolve(...)",
            PLAY_SCREEN.contains(Regex("""StationBuilder\.resolve\s*\(""")),
        )
    }

    private companion object {
        private val GAME =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                    "src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                ),
            )
        private val BUILD_SCREEN =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/screen/StationBuildScreen.kt",
                    "src/main/kotlin/com/orbitalfrontier/screen/StationBuildScreen.kt",
                ),
            )
        private val PLAY_SCREEN =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                    "src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                ),
            )

        private fun stripComments(source: String): String =
            source
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        private fun readSource(vararg candidates: String): String {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate ${candidates.firstOrNull()} from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC51 station-build source guard cannot run (refusing to pass silently).",
            )
        }
    }
}
