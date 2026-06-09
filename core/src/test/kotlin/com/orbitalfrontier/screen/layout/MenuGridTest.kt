package com.orbitalfrontier.screen.layout

import com.orbitalfrontier.render.UiScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.min

/**
 * Behavioural unit tests for the pure [MenuGrid] arithmetic that drives the UC20 station-menu grid.
 *
 * [MenuGrid] carries no libGDX dependency, so the grid contract (AC#2 "≤ 4 rows, columns grow" and
 * AC#3 "consistent column-major fill order") is verified directly here on the JVM, with no GL context.
 * The screen glue that maps these results onto a Scene2D `Table` is GL-bound and pinned separately by
 * the source-anchored [com.orbitalfrontier.screen.Uc20StationGridGuardTest].
 *
 * The final `no horizontal overflow at the narrow floor` test discharges the *deterministic* part of
 * AC#5 (layout fits without clipping): it proves mathematically that, at the narrowest supported
 * viewport, the fitted column width times the column count plus the gaps never exceeds the available
 * width for the realistic station button counts. The remaining AC#5 step — a live emulator visual pass
 * at ~720px and ~1080px in both orientations — is GL/render-bound and is run by the root session via
 * the android-emulator-setup skill (it cannot be discharged by a JVM test).
 */
class MenuGridTest {
    // --- AC#2: column/row counts — never more than 4 rows; columns grow with item count ----------

    @Test
    fun `columnCount is ceil(itemCount over maxRows)`() {
        val expected =
            mapOf(
                0 to 0,
                1 to 1,
                2 to 1,
                3 to 1,
                4 to 1,
                5 to 2,
                8 to 2,
                9 to 3,
                10 to 3,
                13 to 4,
            )
        for ((n, cols) in expected) {
            assertEquals("columnCount($n) at maxRows=4", cols, MenuGrid.columnCount(n))
        }
    }

    @Test
    fun `rowCount is min(itemCount, maxRows) and never exceeds 4`() {
        val expected =
            mapOf(
                0 to 0,
                1 to 1,
                2 to 2,
                3 to 3,
                4 to 4,
                5 to 4,
                8 to 4,
                9 to 4,
                10 to 4,
                13 to 4,
            )
        for ((n, rows) in expected) {
            assertEquals("rowCount($n) at maxRows=4", rows, MenuGrid.rowCount(n))
        }
    }

    @Test
    fun `rowCount never exceeds maxRows across a wide range (AC#2 hard cap)`() {
        for (n in 0..200) {
            assertTrue(
                "AC#2: rowCount($n) must never exceed the 4-row cap",
                MenuGrid.rowCount(n) <= MenuGrid.DEFAULT_MAX_ROWS,
            )
        }
    }

    @Test
    fun `columns grow (never shrink) as item count rises (AC#2 overflow into columns)`() {
        var previous = 0
        for (n in 0..200) {
            val cols = MenuGrid.columnCount(n)
            assertTrue("AC#2: columnCount must be monotonic non-decreasing at n=$n", cols >= previous)
            previous = cols
        }
    }

    @Test
    fun `rows times columns always covers every item without leaving a full empty column`() {
        for (n in 1..200) {
            val rows = MenuGrid.rowCount(n)
            val cols = MenuGrid.columnCount(n)
            assertTrue("the grid must hold all $n items", rows * cols >= n)
            assertTrue("the grid must not waste a whole extra column at n=$n", (cols - 1) * rows < n)
        }
    }

    // --- AC#3: consistent column-major fill order ------------------------------------------------

    @Test
    fun `cellOf fills column-major — a column fills top-to-bottom before the next column`() {
        // 10 items at maxRows=4: column 0 = indices 0..3, column 1 = 4..7, column 2 = 8..9.
        val expected =
            listOf(
                GridCell(0, 0),
                GridCell(1, 0),
                GridCell(2, 0),
                GridCell(3, 0),
                GridCell(0, 1),
                GridCell(1, 1),
                GridCell(2, 1),
                GridCell(3, 1),
                GridCell(0, 2),
                GridCell(1, 2),
            )
        for (index in expected.indices) {
            assertEquals("cellOf($index) column-major", expected[index], MenuGrid.cellOf(index))
        }
    }

    @Test
    fun `cellOf and indexAt are exact inverses (round-trip) for every item`() {
        for (index in 0 until 40) {
            val cell = MenuGrid.cellOf(index)
            assertEquals("indexAt(cellOf($index)) must round-trip", index, MenuGrid.indexAt(cell))
        }
    }

    @Test
    fun `placements lists one cell per item, in item order, matching cellOf`() {
        for (n in listOf(0, 1, 3, 4, 5, 10, 13)) {
            val placements = MenuGrid.placements(n)
            assertEquals("placements($n) size", n, placements.size)
            for (index in 0 until n) {
                assertEquals("placements($n)[$index]", MenuGrid.cellOf(index), placements[index])
            }
        }
    }

    @Test
    fun `placements assigns every item a distinct cell (no two items share a position)`() {
        for (n in listOf(1, 4, 5, 10, 13, 37)) {
            val placements = MenuGrid.placements(n)
            assertEquals("placements($n) must have no duplicate cells", n, placements.toSet().size)
        }
    }

    @Test
    fun `placements stay within the computed rows and columns`() {
        for (n in listOf(1, 4, 5, 10, 13, 37)) {
            val rows = MenuGrid.rowCount(n)
            val cols = MenuGrid.columnCount(n)
            for (cell in MenuGrid.placements(n)) {
                assertTrue("row in bounds for n=$n", cell.row in 0 until rows)
                assertTrue("column in bounds for n=$n", cell.column in 0 until cols)
            }
        }
    }

    // --- cellWidth: viewport fitting + clamps ----------------------------------------------------

    @Test
    fun `cellWidth is zero when there are no columns`() {
        assertEquals(0f, MenuGrid.cellWidth(1000f, 0, GAP, MIN, MAX), EPS)
        assertEquals(0f, MenuGrid.cellWidth(1000f, -3, GAP, MIN, MAX), EPS)
    }

    @Test
    fun `cellWidth exactly fills the available width when the raw figure is in range`() {
        // 3 columns into 296 world-units: (296 - 4*8) / 3 = 88, which is within [88, 220] (no clamp),
        // so 3 cells + 4 gaps == 296 exactly.
        val available = 296f
        val cols = 3
        val width = MenuGrid.cellWidth(available, cols, GAP, MIN, MAX)
        assertEquals(88f, width, EPS)
        assertEquals(available, cols * width + (cols + 1) * GAP, EPS)
    }

    @Test
    fun `cellWidth clamps to the minimum on a narrow viewport`() {
        // 4 columns into a too-narrow 200 world-units: raw = (200 - 5*8)/4 = 40 < MIN, clamps to 88.
        assertEquals(MIN, MenuGrid.cellWidth(200f, 4, GAP, MIN, MAX), EPS)
    }

    @Test
    fun `cellWidth clamps to the maximum on a wide viewport`() {
        // 1 column into a wide 1000 world-units: raw = (1000 - 2*8)/1 = 984 > MAX, clamps to 220.
        assertEquals(MAX, MenuGrid.cellWidth(1000f, 1, GAP, MIN, MAX), EPS)
    }

    // --- require-guards --------------------------------------------------------------------------

    @Test
    fun `negative item counts and non-positive maxRows are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.columnCount(-1) }
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.rowCount(-1) }
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.placements(-1) }
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.columnCount(4, maxRows = 0) }
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.rowCount(4, maxRows = 0) }
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.cellOf(0, maxRows = 0) }
    }

    @Test
    fun `a negative flat index is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MenuGrid.cellOf(-1) }
    }

    // --- AC#5 (deterministic part): no horizontal overflow at the narrow floor -------------------

    @Test
    fun `the fitted grid never overflows horizontally at the narrowest supported viewport`() {
        // Narrowest supported phone width ~720 physical px. The UI viewport renders at UiScale.factor
        // (ADR 0015) via unitsPerPixel = 1/factor, so worldWidth = 720 / factor. The hub then reserves
        // MARGIN on each side, leaving `available` world-units for the grid. For factor=2 this is the
        // 296-unit floor the screen actually fits into.
        val worldWidth = NARROW_PHYSICAL_PX / UiScale.factor
        val available = worldWidth - 2 * MARGIN

        // Realistic station-menu sizes: the hub shows up to ~10 action buttons (TRADE, OUTFIT, SHIPS,
        // CREW, MISSIONS, BUILD, Refuel, Buy Fuel, EXIT SHIP, UNDOCK), i.e. at most 3 columns at maxRows
        // 4. Prove the *rendered* total (cells + all gaps) fits within the available width for each.
        for (n in 1..10) {
            val cols = MenuGrid.columnCount(n)
            val width = MenuGrid.cellWidth(available, cols, GAP, MIN, MAX)
            val rendered = cols * width + (cols + 1) * GAP
            assertTrue(
                "AC#5: n=$n ($cols cols) renders $rendered units, must fit within $available available",
                rendered <= available + EPS,
            )
        }

        // And the worst case at the floor — the MIN-clamp could in principle overflow, so prove the
        // bare minimum footprint of the widest realistic grid (3 columns at MIN width) still fits.
        val maxCols = MenuGrid.columnCount(10)
        val minFootprint = maxCols * MIN + (maxCols + 1) * GAP
        assertTrue(
            "AC#5: the MIN-clamped 3-column footprint ($minFootprint) must fit within $available",
            minFootprint <= available + EPS,
        )
    }

    @Test
    fun `cellWidth never exceeds the available width for any in-range column count`() {
        // Cross-check the fit invariant directly: when cellWidth does not clamp to MIN, the rendered
        // total equals (and so never exceeds) the available width; when it clamps to MAX it is smaller.
        val available = 296f
        for (cols in 1..6) {
            val width = MenuGrid.cellWidth(available, cols, GAP, MIN, MAX)
            if (width > MIN) {
                assertTrue(
                    "non-MIN-clamped cellWidth must not overflow at cols=$cols",
                    cols * width + (cols + 1) * GAP <= available + EPS,
                )
            }
        }
    }

    @Test
    fun `documented column-major derivation matches the model for sampled counts`() {
        // Sanity-cross-check the model against an independent ceil/min computation.
        for (n in 0..50) {
            val cols = if (n == 0) 0 else ceil(n.toDouble() / MenuGrid.DEFAULT_MAX_ROWS).toInt()
            val rows = min(n, MenuGrid.DEFAULT_MAX_ROWS)
            assertEquals("independent columnCount($n)", cols, MenuGrid.columnCount(n))
            assertEquals("independent rowCount($n)", rows, MenuGrid.rowCount(n))
        }
    }

    private companion object {
        const val EPS = 1e-3f
        const val GAP = 8f
        const val MIN = 88f
        const val MAX = 220f
        const val MARGIN = 32f
        const val NARROW_PHYSICAL_PX = 720f
    }
}
