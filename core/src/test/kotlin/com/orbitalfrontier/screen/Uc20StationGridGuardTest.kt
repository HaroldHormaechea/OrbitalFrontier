package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** UC20 acceptance criteria on [StationHubScreen].
 *
 * Why source-anchored, not behavioural: [StationHubScreen] builds live libGDX objects in its field
 * initializers / `init` block (`Stage`, scene2d `Table`/`Label`/`TextButton`), which require a real GL
 * context the libGDX headless backend does not provide. It is NOT headlessly constructible, and
 * production must not be reshaped just to make it so — so the structural contract for the ACs that live
 * in this glue is pinned at the source level, mirroring the repo's existing guards
 * ([StationRefuelFeedbackSourceTest], [Uc19WalkaroundGuardTest]). The *behavioural* coverage of the
 * grid arithmetic itself (AC#2/#3, plus the deterministic AC#5 fit-math) lives in
 * [com.orbitalfrontier.screen.layout.MenuGridTest].
 *
 * ACs covered here:
 *  - **AC#1/#3** — the hub arranges its buttons via [com.orbitalfrontier.screen.layout.MenuGrid]
 *    (grid, not a single row) at the 4-row cap, column-major.
 *  - **AC#2** — the cap is the shared `MenuGrid.DEFAULT_MAX_ROWS` (= 4); the hub does not hardcode a
 *    different row count.
 *  - **AC#4 (+ UC07/UC18/UC15/UC19 no-regression)** — every existing action label is still present and
 *    every existing action hook (`onTrade`/`onUndock`/`onOutfit`/`onShipyard`/`onCrew`/`onMissions`/
 *    `onRefuel`/`onBuyFuel`/`onDisembark`) plus the fuel/feedback labels are still wired, so only the
 *    arrangement changed.
 *  - **AC#5 (structural)** — the grid cell width re-fits on `resize(...)`, so the layout reflows
 *    instead of clipping when the screen size / orientation changes (the live visual pass remains a
 *    separate emulator step).
 */
class Uc20StationGridGuardTest {
    // --- AC#1/#3: arranged via MenuGrid, column-major grid (not a single row) ---------------------

    @Test
    fun `the hub arranges its buttons through MenuGrid rather than a single row`() {
        assertTrue(
            "AC#1: the hub must import and use the shared MenuGrid layout helper",
            HUB_SOURCE.contains("import com.orbitalfrontier.screen.layout.MenuGrid"),
        )
        assertTrue(
            "AC#1/#3: the hub must place items by their MenuGrid cell (column-major fill)",
            HUB_SOURCE.contains("MenuGrid.indexAt(") && HUB_SOURCE.contains("GridCell("),
        )
        assertTrue(
            "AC#1: the hub must compute its column count from MenuGrid",
            HUB_SOURCE.contains("MenuGrid.columnCount("),
        )
        assertTrue(
            "AC#1: the hub must compute its row count from MenuGrid",
            HUB_SOURCE.contains("MenuGrid.rowCount("),
        )
    }

    // --- AC#2: 4-row cap comes from the shared MenuGrid constant, not a local literal --------------

    @Test
    fun `the hub uses MenuGrid's 4-row cap, not a hardcoded row count`() {
        assertTrue(
            "AC#2: the row cap must be MenuGrid.DEFAULT_MAX_ROWS (the single source of the 4-row rule)",
            HUB_SOURCE.contains("MenuGrid.DEFAULT_MAX_ROWS"),
        )
        // The shared constant itself must still be 4 (the AC#2 cap).
        assertTrue(
            "AC#2: MenuGrid.DEFAULT_MAX_ROWS must remain 4",
            Regex("""DEFAULT_MAX_ROWS\s*=\s*4\b""").containsMatchIn(MENU_GRID_SOURCE),
        )
    }

    // --- AC#4 (+ no-regression): every existing label and action hook is retained -----------------

    @Test
    fun `every existing station menu label is still present`() {
        val labels =
            listOf(
                "\"TRADE\"",
                "\"OUTFIT\"",
                "\"SHIPS\"",
                "\"CREW\"",
                "\"MISSIONS\"",
                "\"BUILD\"",
                "\"Refuel (H₂)\"",
                "\"Buy Fuel (credits)\"",
                "\"EXIT SHIP\"",
                "\"UNDOCK\"",
            )
        for (label in labels) {
            assertTrue("AC#4: the $label action must still be present (only arrangement changed)", HUB_SOURCE.contains(label))
        }
    }

    @Test
    fun `every existing action hook is still wired`() {
        val hooks =
            listOf(
                "onTrade",
                "onUndock",
                "onOutfit",
                "onShipyard",
                "onCrew",
                "onMissions",
                "onRefuel",
                "onBuyFuel",
                "onBuild",
                "onDisembark",
            )
        for (hook in hooks) {
            assertTrue("AC#4: the $hook action hook must remain wired (no behavioural regression)", HUB_SOURCE.contains(hook))
        }
    }

    @Test
    fun `the refuel feedback and fuel readout labels survive the relayout (UC07 plus UC18 no-regression)`() {
        assertTrue("UC07: the fuel readout label must remain", HUB_SOURCE.contains("fuelLabel"))
        assertTrue("UC18: the refuel feedback label must remain", HUB_SOURCE.contains("refuelFeedbackLabel"))
        // Both refuel buttons still display their returned feedback line (UC18 AC#1/#4 stays green).
        assertTrue(
            "UC18: the Refuel (H₂) button must still show onRefuel()'s feedback",
            Regex("""setText\s*\(\s*onRefuel\s*\(\s*\)\s*\)""").containsMatchIn(HUB_SOURCE),
        )
        assertTrue(
            "UC18: the Buy Fuel button must still show onBuyFuel()'s feedback",
            Regex("""setText\s*\(\s*onBuyFuel\s*\(\s*\)\s*\)""").containsMatchIn(HUB_SOURCE),
        )
    }

    // --- AC#5 (structural): the grid reflows on resize instead of clipping ------------------------

    @Test
    fun `the grid cell width is re-fitted on resize so the layout reflows instead of clipping`() {
        val resize = section(HUB_SOURCE, "override fun resize(")
        assertTrue(
            "AC#5: resize() must re-fit the grid cell width to the new viewport",
            resize.contains("currentCellWidth()") && resize.contains("cell.width("),
        )
        assertTrue(
            "AC#5: resize() must re-validate the grid layout after refitting",
            resize.contains("invalidateHierarchy()"),
        )
        // The fitted width itself routes through MenuGrid.cellWidth (the clamped, overflow-safe figure).
        assertTrue(
            "AC#5: the cell width must come from MenuGrid.cellWidth (clamped to MIN..MAX)",
            HUB_SOURCE.contains("MenuGrid.cellWidth("),
        )
    }

    private companion object {
        private val HUB_SOURCE: String = readSource("screen/StationHubScreen.kt")
        private val MENU_GRID_SOURCE: String = readSource("screen/layout/MenuGrid.kt")

        /**
         * The body of the declaration whose header is [header], from the header to the first line that
         * is a single closing brace at the declaration's indentation. Good enough to scope an assertion
         * to one method without a full parser (mirrors [Uc19WalkaroundGuardTest.section]).
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
         * or a git worktree). Refuses to pass silently if the file cannot be found (mirrors the repo's
         * existing source-anchored guards).
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
                    "the UC20 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
