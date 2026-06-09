package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** UC19 acceptance criteria.
 *
 * Why source-anchored, not behavioural: [StationHubScreen], [StationWalkaroundScreen],
 * [com.orbitalfrontier.render.WalkaroundRenderer] and [com.orbitalfrontier.app.OrbitalFrontierGame]
 * all build live libGDX objects in their field initializers / `init` blocks (`OrthographicCamera`,
 * scene2d `Stage`, `ShapeRenderer`, `Game`), which require a real GL context that the libGDX headless
 * backend does not provide. They are NOT headlessly constructible, and production must not be reshaped
 * just to make them so. So the structural contract for the ACs that live in this glue is pinned at the
 * source level, mirroring the repo's existing guards ([com.orbitalfrontier.app.Uc17StartingCreditsTest],
 * [StationRefuelFeedbackSourceTest]).
 *
 * ACs covered here (behavioural coverage of the pure model/geometry lives in `RectTest`,
 * `StationInteriorTest`, `WalkaroundModelTest`):
 *  - **AC#1** — EXIT SHIP is purely additive: every existing hub service row is retained AND a
 *    defaulted `onDisembark` hook + an "EXIT SHIP" row are added.
 *  - **AC#3** — the avatar is drawn as a ball with a facing dot derived from `avatar.facing`.
 *  - **AC#6** — INTERACT is proximity-gated (visible only near the shopkeeper) and routes to the
 *    EXISTING `TradeScreen`; RE-BOARD is always present.
 *  - **AC#7** — re-board restores the docked state: the walk-around screen is decoupled from world/
 *    save state (its constructor takes none of WorldState/repository/autosave/PlayScreen), and the
 *    re-board path re-shows the untouched hub without undocking or mutating the dock state.
 */
class Uc19WalkaroundGuardTest {
    // --- AC#1: EXIT SHIP is additive; existing hub menus unchanged -------------------------------

    @Test
    fun `the hub keeps every existing service row`() {
        for (row in listOf("\"TRADE\"", "\"OUTFIT\"", "\"SHIPS\"", "\"CREW\"", "\"MISSIONS\"", "\"UNDOCK\"")) {
            assertTrue("AC#1: the hub must still render the $row row unchanged", HUB_SOURCE.contains(row))
        }
    }

    @Test
    fun `the hub adds a defaulted onDisembark hook and an EXIT SHIP row`() {
        assertTrue(
            "AC#1: onDisembark must be a defaulted no-op so existing call sites/tests are unaffected",
            Regex("""onDisembark\s*:\s*\(\)\s*->\s*Unit\s*=\s*\{\s*\}""").containsMatchIn(HUB_SOURCE),
        )
        assertTrue("AC#1: an EXIT SHIP row must be present", HUB_SOURCE.contains("\"EXIT SHIP\""))
        assertTrue(
            "AC#1: the EXIT SHIP row must fire onDisembark",
            Regex("""serviceButton\(\s*"EXIT SHIP"\s*,\s*onDisembark\s*\)""").containsMatchIn(HUB_SOURCE),
        )
    }

    // --- AC#3: avatar drawn as a ball + facing dot -----------------------------------------------

    @Test
    fun `the renderer draws the avatar as a circle with a facing dot`() {
        assertTrue("AC#3: the avatar body is a circle", RENDERER_SOURCE.contains("circle("))
        assertTrue(
            "AC#3: the facing dot must be derived from the avatar's facing direction",
            RENDERER_SOURCE.contains("avatar.facing.x") && RENDERER_SOURCE.contains("avatar.facing.y"),
        )
    }

    // --- AC#6: INTERACT proximity-gated -> existing shop; RE-BOARD always present ----------------

    @Test
    fun `RE-BOARD is always shown and INTERACT is proximity-gated on the shopkeeper`() {
        assertTrue("AC#7: RE-BOARD must be added to the stage", WALKAROUND_SOURCE.contains("stage.addActor(reboardButton)"))
        assertFalse(
            "AC#7: RE-BOARD must never be hidden (always available)",
            Regex("""reboardButton\.isVisible\s*=\s*false""").containsMatchIn(WALKAROUND_SOURCE),
        )
        assertTrue(
            "AC#6: INTERACT must start hidden",
            Regex("""interactButton\.isVisible\s*=\s*false""").containsMatchIn(WALKAROUND_SOURCE),
        )
        assertTrue(
            "AC#6: INTERACT visibility must be gated on isNearShopkeeper",
            Regex("""interactButton\.isVisible\s*=\s*model\.isNearShopkeeper""").containsMatchIn(WALKAROUND_SOURCE),
        )
    }

    @Test
    fun `INTERACT opens the EXISTING TradeScreen, not a new shop screen`() {
        // The game routes the walk-around's onInteract to a method that opens the same TradeScreen the
        // hub menus use — no bespoke on-foot shop UI (UC19 assumption).
        assertTrue(
            "AC#6: the walk-around must route INTERACT to opening the shop from foot",
            Regex("""onInteract\s*=\s*\{\s*openShopFromWalkaround\(station\)\s*\}""").containsMatchIn(GAME_SOURCE),
        )
        val openShop = section(GAME_SOURCE, "private fun openShopFromWalkaround(station: Station)")
        assertTrue(
            "AC#6: opening the shop on foot must construct the existing TradeScreen",
            openShop.contains("TradeScreen("),
        )
    }

    // --- AC#7: re-board restores docked state; walk-around is world/save-decoupled ---------------

    @Test
    fun `the walk-around screen constructor takes no world or save state`() {
        val ctor = constructorParams(WALKAROUND_SOURCE)
        for (forbidden in listOf("WorldState", "Repository", "Autosave", "autosave", "PlayScreen", "SqlDriver", "GameState")) {
            assertFalse(
                "AC#7: StationWalkaroundScreen must NOT couple to $forbidden (it is decoupled from world/save)",
                ctor.contains(forbidden),
            )
        }
        // It DOES take exactly the transient interior + the two navigation callbacks.
        assertTrue(ctor.contains("interior: StationInterior"))
        assertTrue(ctor.contains("onReboard"))
        assertTrue(ctor.contains("onInteract"))
    }

    @Test
    fun `opening the walk-around uses a transient prototype interior`() {
        val openWalkaround = section(GAME_SOURCE, "private fun openWalkaround(station: Station)")
        assertTrue(
            "AC#7: the interior is rebuilt transiently from prototype(), never persisted",
            openWalkaround.contains("StationInterior.prototype()"),
        )
        assertTrue(
            "AC#7: RE-BOARD must route back to the hub",
            Regex("""onReboard\s*=\s*\{\s*returnToHubFromFoot\(\)\s*\}""").containsMatchIn(openWalkaround),
        )
    }

    @Test
    fun `re-boarding re-shows the untouched hub without undocking or mutating dock state`() {
        val reboard = section(GAME_SOURCE, "private fun returnToHubFromFoot()")
        assertTrue("AC#7: re-board re-shows the kept-alive hub", reboard.contains("stationHubScreen") && reboard.contains("setScreen"))
        // The docked WorldState must be left exactly as it was — no undock, no dock-state mutation.
        assertFalse("AC#7: re-board must not undock", reboard.contains("undock"))
        assertFalse("AC#7: re-board must not touch dockedStation", reboard.contains("dockedStation"))
        assertFalse("AC#7: re-board must not touch world/save state", reboard.contains("WorldState"))
    }

    private companion object {
        private val HUB_SOURCE: String = readSource("screen/StationHubScreen.kt")
        private val WALKAROUND_SOURCE: String = readSource("screen/StationWalkaroundScreen.kt")
        private val RENDERER_SOURCE: String = readSource("render/WalkaroundRenderer.kt")
        private val GAME_SOURCE: String = readSource("app/OrbitalFrontierGame.kt")

        /** The parameter list of `StationWalkaroundScreen(...)` up to `: ScreenAdapter()`. */
        private fun constructorParams(source: String): String =
            Regex("""class StationWalkaroundScreen\((.*?)\)\s*:\s*ScreenAdapter\(\)""", RegexOption.DOT_MATCHES_ALL)
                .find(source)
                ?.groupValues?.get(1)
                ?: throw AssertionError("Could not locate the StationWalkaroundScreen constructor")

        /**
         * The body of the function/declaration whose header is [header], from the header to the first
         * line that is a single closing brace at the function's indentation. Good enough to scope an
         * assertion to one method without a full parser.
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
         * or a git worktree). Refuses to pass silently if the file cannot be found.
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
                    "the UC19 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
