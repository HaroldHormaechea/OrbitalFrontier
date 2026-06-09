package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC18 AC#1/#4 feedback on **both** station refuel buttons.
 *
 * Why source-anchored, not a behavioural test: both [com.orbitalfrontier.screen.PlayScreen] and
 * [com.orbitalfrontier.screen.StationHubScreen] build live libGDX objects in their field initializers
 * (`OrthographicCamera`, scene2d `Stage`/`Label`/`Table`), which require a real GL context. They are
 * NOT headlessly constructible — the libGDX headless backend provides no GL — so `refuel()` /
 * `buyFuel()` cannot be driven from a plain JVM unit test, and production must not be reshaped just to
 * make them testable. Instead we pin the contract at the source level, mirroring the repo's existing
 * source-anchored guards ([com.orbitalfrontier.app.Uc17StartingCreditsTest], `NoBox2DGuardTest`).
 *
 * The contract being pinned (UC18 AC#1: "the 'nothing happens' case no longer occurs"; AC#4: a no-op
 * "produces correct, clear behaviour (no-op with feedback) rather than a silent failure"):
 *  - `PlayScreen.refuel()` and `PlayScreen.buyFuel()` each return a `String` (not `Unit`).
 *  - Every no-op branch of each returns a **non-empty** feedback literal — no silent fall-through.
 *  - `StationHubScreen` wires BOTH buttons to show the returned feedback line on screen.
 */
class StationRefuelFeedbackSourceTest {
    // --- PlayScreen.refuel(): UC07 hydrogen-conversion path now reports feedback (no silent no-op) ---

    @Test
    fun `PlayScreen refuel returns a String, not Unit`() {
        assertTrue(
            "refuel() must return a feedback String for the hub to show (UC18 AC#1)",
            PLAY_SCREEN_SOURCE.contains(Regex("""fun\s+refuel\s*\(\s*\)\s*:\s*String""")),
        )
    }

    @Test
    fun `PlayScreen refuel no-op branches return non-empty feedback`() {
        // The two no-op outcomes of the hydrogen-conversion refuel (tank full / no hydrogen) must each
        // surface a clear, non-empty line rather than a bare `return`.
        assertTrue(
            "the full-tank no-op must report feedback",
            PLAY_SCREEN_SOURCE.contains("\"Tank full\""),
        )
        assertTrue(
            "the no-hydrogen no-op must report feedback",
            PLAY_SCREEN_SOURCE.contains("\"No hydrogen to convert\""),
        )
    }

    // --- PlayScreen.buyFuel(): UC18 credits-for-fuel path reports feedback on every status ---

    @Test
    fun `PlayScreen buyFuel returns a String, not Unit`() {
        assertTrue(
            "buyFuel() must return a feedback String for the hub to show (UC18 AC#1)",
            PLAY_SCREEN_SOURCE.contains(Regex("""fun\s+buyFuel\s*\(\s*\)\s*:\s*String""")),
        )
    }

    @Test
    fun `PlayScreen buyFuel maps every no-op status to non-empty feedback`() {
        // FULL / BROKE / UNAVAILABLE are the three no-op StationRefuelStatus values; each must yield a
        // clear, non-empty message so "nothing happens" never reaches the player (UC18 AC#4).
        for (expected in listOf("\"Tank full\"", "\"Insufficient credits\"", "\"No fuel sold here\"")) {
            assertTrue(
                "buyFuel() must surface feedback $expected for its matching no-op status",
                PLAY_SCREEN_SOURCE.contains(expected),
            )
        }
    }

    // --- StationHubScreen: BOTH buttons show their returned feedback line ---

    @Test
    fun `StationHubScreen wires an onBuyFuel hook alongside onRefuel`() {
        assertTrue(
            "the hub must expose the UC18 buy-fuel button hook",
            HUB_SCREEN_SOURCE.contains("onBuyFuel"),
        )
        assertTrue(
            "the hub must keep the UC07 refuel button hook",
            HUB_SCREEN_SOURCE.contains("onRefuel"),
        )
    }

    @Test
    fun `StationHubScreen shows the feedback line returned by BOTH refuel buttons`() {
        // Each button's click handler must push its returned String into the shared feedback label, so
        // neither path is a silent tap (UC18 AC#1/#4 — verified on BOTH buttons, per the binding).
        assertTrue(
            "the Refuel (H2) button must display onRefuel()'s feedback",
            HUB_SCREEN_SOURCE.contains(Regex("""setText\s*\(\s*onRefuel\s*\(\s*\)\s*\)""")),
        )
        assertTrue(
            "the Buy Fuel button must display onBuyFuel()'s feedback",
            HUB_SCREEN_SOURCE.contains(Regex("""setText\s*\(\s*onBuyFuel\s*\(\s*\)\s*\)""")),
        )
    }

    private companion object {
        private val PLAY_SCREEN_SOURCE: String =
            readSource(
                "src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                "core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
            )
        private val HUB_SCREEN_SOURCE: String =
            readSource(
                "src/main/kotlin/com/orbitalfrontier/screen/StationHubScreen.kt",
                "core/src/main/kotlin/com/orbitalfrontier/screen/StationHubScreen.kt",
            )

        /**
         * Locates a production source file by walking up from the test working directory and trying each
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Refuses to pass silently if the file cannot be found.
         */
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
                    "the UC18 feedback source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
