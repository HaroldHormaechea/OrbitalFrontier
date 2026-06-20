package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC50 — the fleet & crew management screen wiring (AC#3) and the device-side
 * crew-wage drain (AC#2).
 *
 * Why source-anchored, not behavioural: [FleetCrewScreen] / [PlayScreen] / [StationHubScreen] build live
 * libGDX objects (a `Stage`, scene2d actors, GL-backed skins) in their field initializers, which need a
 * real GL context — they are NOT headlessly constructible, so the wiring cannot be driven from a plain JVM
 * unit test, and production must not be reshaped just to test it. The *behaviour* behind each intent is
 * pure and proven for real in [com.orbitalfrontier.crew.CrewAssignmentTest] / [com.orbitalfrontier.crew
 * .WagesTest] / [com.orbitalfrontier.ship.FleetTest]; here we pin the wiring at the source level, mirroring
 * the repo's existing guards ([Uc44CombatHudSourceTest], [Uc47BuyUsedSourceTest]).
 *
 * All assertions run over **comment-stripped CODE** (block + line comments removed), so a doc comment that
 * merely names `CrewOrder.Reassign`/`Wages.resolve` is prose and never satisfies the guard — only real code
 * does. The contract pinned:
 *  - AC#3: FleetCrewScreen lists ships + crew (reads the fleet + roster suppliers, groups crew by ship);
 *    SET ACTIVE fires [com.orbitalfrontier.ship.FleetOrder.SwitchActive], MOVE → fires
 *    [com.orbitalfrontier.crew.CrewOrder.Reassign], ROLE → fires [com.orbitalfrontier.crew.CrewOrder.ChangeRole].
 *  - AC#3 routing: PlayScreen routes crew orders to the pure [com.orbitalfrontier.crew.CrewAssignment] and
 *    the active-ship switch to the pure [com.orbitalfrontier.ship.FleetResolver] (no duplicated switch logic).
 *  - AC#3 wiring: OrbitalFrontierGame constructs the FleetCrewScreen and routes its intents to the play
 *    screen; StationHubScreen surfaces the FLEET button.
 *  - AC#2: PlayScreen drains crew wages via the pure [com.orbitalfrontier.crew.Wages], raises the
 *    UNPAID_WAGES toast on a shortfall, and autosaves on a real drain.
 */
class Uc50FleetCrewSourceTest {
    // --- AC#3: FleetCrewScreen lists ships + crew and fires the right pure intents ---------------------

    @Test
    fun `FleetCrewScreen lists ships and their crew from the live suppliers`() {
        assertTrue(
            "FleetCrewScreen must read the live fleet for its ship rows",
            FLEET_CREW.contains(Regex("""fleetSupplier\s*\(\s*\)""")),
        )
        assertTrue(
            "FleetCrewScreen must read the live roster for its crew rows",
            FLEET_CREW.contains(Regex("""rosterSupplier\s*\(\s*\)""")),
        )
        assertTrue(
            "FleetCrewScreen must group crew by ship (roster.forShip(...))",
            FLEET_CREW.contains(Regex("""\.forShip\s*\(""")),
        )
    }

    @Test
    fun `the SET ACTIVE row fires a FleetOrder SwitchActive through onFleetOrder`() {
        assertTrue(
            "SET ACTIVE must fire FleetOrder.SwitchActive (reusing the pure FleetResolver switch, no duplicate)",
            FLEET_CREW.contains(Regex("""onFleetOrder\s*\(\s*FleetOrder\.SwitchActive\s*\(""")),
        )
    }

    @Test
    fun `the MOVE row fires a CrewOrder Reassign through onCrewOrder`() {
        assertTrue(
            "MOVE → must fire CrewOrder.Reassign via onCrewOrder",
            FLEET_CREW.contains(Regex("""onCrewOrder\s*\(\s*CrewOrder\.Reassign\s*\(""")),
        )
    }

    @Test
    fun `the ROLE row fires a CrewOrder ChangeRole through onCrewOrder`() {
        assertTrue(
            "ROLE → must fire CrewOrder.ChangeRole via onCrewOrder",
            FLEET_CREW.contains(Regex("""onCrewOrder\s*\(\s*CrewOrder\.ChangeRole\s*\(""")),
        )
    }

    // --- AC#3 routing: PlayScreen folds crew orders through CrewAssignment, switch through FleetResolver ---

    @Test
    fun `PlayScreen routes crew orders to the pure CrewAssignment resolver`() {
        assertTrue(
            "applyCrewOrder must resolve via the pure CrewAssignment.resolve(fleet, crewRoster, order)",
            PLAY_SCREEN.contains(Regex("""CrewAssignment\.resolve\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen routes the active-ship switch to the pure FleetResolver (no duplicated switch logic)`() {
        assertTrue(
            "the active-ship switch must reuse FleetResolver.resolve (the same path the shipyard uses)",
            PLAY_SCREEN.contains(Regex("""FleetResolver\.resolve\s*\(""")),
        )
    }

    // --- AC#3 wiring: the game constructs the screen and routes its intents; the hub surfaces FLEET -------

    @Test
    fun `OrbitalFrontierGame constructs the FleetCrewScreen and routes its intents to the play screen`() {
        assertTrue("the game must construct a FleetCrewScreen", GAME.contains(Regex("""FleetCrewScreen\s*\(""")))
        assertTrue(
            "MOVE / ROLE intents must route to PlayScreen.applyCrewOrder",
            GAME.contains(Regex("""onCrewOrder\s*=\s*\{[^}]*applyCrewOrder\s*\(""")),
        )
        assertTrue(
            "SET ACTIVE must route to PlayScreen.fleetCommand (the pure FleetResolver path)",
            GAME.contains(Regex("""onFleetOrder\s*=\s*\{[^}]*fleetCommand\s*\(""")),
        )
    }

    @Test
    fun `StationHubScreen surfaces a FLEET button wired to the fleet-crew intent`() {
        assertTrue(
            "the station hub must surface a FLEET service button firing onFleetCrew",
            STATION_HUB.contains(Regex(""""FLEET"\s*,\s*onFleetCrew""")),
        )
        assertTrue(
            "the game must wire onFleetCrew to open the fleet-crew screen",
            GAME.contains(Regex("""onFleetCrew\s*=\s*\{[^}]*openFleetCrew\s*\(""")),
        )
    }

    // --- AC#2: the device-side wage drain uses the pure Wages resolver + the UNPAID_WAGES toast ----------

    @Test
    fun `PlayScreen drains crew wages via the pure Wages resolver`() {
        assertTrue(
            "the wage drain must resolve via the pure Wages.resolve(credits, fleet.totalCrew, wageParams)",
            PLAY_SCREEN.contains(Regex("""Wages\.resolve\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen raises the unpaid-wages toast on a shortfall and autosaves on a real drain`() {
        assertTrue(
            "a wage shortfall must enqueue the styled UNPAID_WAGES toast",
            PLAY_SCREEN.contains(Regex("""GameNotifications\.unpaidWages\s*\(""")),
        )
        assertTrue(
            "a real drain must autosave so the upkeep is durable (AC#2 persists)",
            PLAY_SCREEN.contains(Regex("""autosave\.onEvent\s*\(\s*"wages"""")),
        )
    }

    private companion object {
        private val FLEET_CREW =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/FleetCrewScreen.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/FleetCrewScreen.kt",
                ),
            )
        private val PLAY_SCREEN =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                ),
            )
        private val STATION_HUB =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/StationHubScreen.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/StationHubScreen.kt",
                ),
            )
        private val GAME =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                ),
            )

        /**
         * Strip Kotlin block (`/* … */`) and line (`// …`) comments so the guards inspect actual CODE only —
         * a doc comment naming CrewOrder/Wages must not satisfy a wiring assertion. Mirrors the
         * comment-stripping in [Uc44CombatHudSourceTest].
         */
        private fun stripComments(source: String): String =
            source
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        /**
         * Locate a production source by walking up from the test working directory, trying each candidate
         * relative path at every ancestor (handles running from the module dir, repo root, or a worktree).
         * A missing file is a hard error so the guard fails loudly rather than passing vacuously.
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
                    "the UC50 fleet-crew source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
