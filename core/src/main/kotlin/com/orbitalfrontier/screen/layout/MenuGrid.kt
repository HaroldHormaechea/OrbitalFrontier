package com.orbitalfrontier.screen.layout

/**
 * A zero-based grid coordinate: [row] and [column] are both 0-based indices into the menu grid.
 */
data class GridCell(
    val row: Int,
    val column: Int,
)

/**
 * Pure, libGDX-free grid arithmetic for the station-hub menu (UC20).
 *
 * The station menu used to be a single vertical/horizontal run of buttons; UC20 arranges the same
 * buttons into a grid capped at [DEFAULT_MAX_ROWS] rows, growing into extra **columns** as the
 * item count rises (rows ≤ 4, columns = ceil(n / 4)). Fill order is **column-major**: a column is
 * filled top-to-bottom before the next column starts, so only the last column is ever short. This
 * keeps the original button order reading down each column.
 *
 * Everything here is plain arithmetic with no Scene2D / libGDX dependency, so it is unit-testable
 * on the JVM in isolation; [com.orbitalfrontier.screen.StationHubScreen] maps these results onto a
 * Scene2D `Table`.
 */
object MenuGrid {
    /** Hard cap on rows; columns grow once more than this many items must be shown (UC20 AC#2). */
    const val DEFAULT_MAX_ROWS = 4

    /** Number of columns needed to show [itemCount] items at most [maxRows] tall; 0 when empty. */
    fun columnCount(
        itemCount: Int,
        maxRows: Int = DEFAULT_MAX_ROWS,
    ): Int {
        require(itemCount >= 0) { "itemCount must be >= 0, was $itemCount" }
        require(maxRows >= 1) { "maxRows must be >= 1, was $maxRows" }
        return (itemCount + maxRows - 1) / maxRows
    }

    /** Number of rows actually used for [itemCount] items, capped at [maxRows]; 0 when empty. */
    fun rowCount(
        itemCount: Int,
        maxRows: Int = DEFAULT_MAX_ROWS,
    ): Int {
        require(itemCount >= 0) { "itemCount must be >= 0, was $itemCount" }
        require(maxRows >= 1) { "maxRows must be >= 1, was $maxRows" }
        return minOf(itemCount, maxRows)
    }

    /** The (row, column) cell a flat [index] occupies under column-major fill. */
    fun cellOf(
        index: Int,
        maxRows: Int = DEFAULT_MAX_ROWS,
    ): GridCell {
        require(index >= 0) { "index must be >= 0, was $index" }
        require(maxRows >= 1) { "maxRows must be >= 1, was $maxRows" }
        return GridCell(index % maxRows, index / maxRows)
    }

    /** The flat index a [cell] maps back to under column-major fill (inverse of [cellOf]). */
    fun indexAt(
        cell: GridCell,
        maxRows: Int = DEFAULT_MAX_ROWS,
    ): Int {
        require(maxRows >= 1) { "maxRows must be >= 1, was $maxRows" }
        return cell.column * maxRows + cell.row
    }

    /** The cell each item 0..[itemCount]-1 lands in, in item order (column-major). */
    fun placements(
        itemCount: Int,
        maxRows: Int = DEFAULT_MAX_ROWS,
    ): List<GridCell> {
        require(itemCount >= 0) { "itemCount must be >= 0, was $itemCount" }
        require(maxRows >= 1) { "maxRows must be >= 1, was $maxRows" }
        return (0 until itemCount).map { cellOf(it, maxRows) }
    }

    /**
     * Per-cell width that fits [columns] equal columns plus a [gap] on each side and between them,
     * clamped to [[minWidth], [maxWidth]]. Returns 0 when there are no columns. When [availableWidth]
     * is 0 (e.g. the viewport has not been laid out yet) the raw figure goes negative and clamps to
     * [minWidth], so cells always have a sane width.
     */
    fun cellWidth(
        availableWidth: Float,
        columns: Int,
        gap: Float,
        minWidth: Float,
        maxWidth: Float,
    ): Float {
        if (columns <= 0) {
            return 0f
        }
        val raw = (availableWidth - (columns + 1) * gap) / columns
        return raw.coerceIn(minWidth, maxWidth)
    }
}
