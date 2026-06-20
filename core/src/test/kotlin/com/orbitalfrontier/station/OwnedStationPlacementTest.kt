package com.orbitalfrontier.station

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OwnedStationPlacement] (UC51 AC#2/#4) — the deterministic, injective placement of
 * player-owned stations.
 *
 * The pure projection ([OwnedStationProjection]) leans on this being a **pure function of the station
 * id**: placement persists across a reload (AC#4) without a save column only because the same id always
 * derives the same position. The challenger's #1 obligation is pinned here: for arbitrary N the
 * positions are **pairwise-distinct** and **clear of the authored hazards** (Alpha Station + the
 * `alpha-raider-picket` zone), so a surfaced owned station never overlaps an authored POI/zone.
 */
class OwnedStationPlacementTest {
    private val alpha = SectorId("alpha")

    /** Alpha Station's authored position (the placement must stay well clear of its dock circle). */
    private val alphaStationPos = Vec2(0f, 600f)

    /** The `alpha-raider-picket` zone centre + radius (the placement must stay clear of the combat zone). */
    private val picketCenter = Vec2(900f, 0f)
    private val picketRadius = 260f

    private fun station(id: Long): OwnedStation = OwnedStation.founded(StationId(id), alpha, StationModuleCatalog.COMMERCE_HUB)

    @Test
    fun `position is a pure function of the station id`() {
        val a = OwnedStationPlacement.position(station(7))
        val b = OwnedStationPlacement.position(station(7))
        assertEquals("same id must derive the same position every call (AC#4 — placement is derived)", a, b)
        assertEquals(
            "position(station) must delegate to positionFor(id)",
            OwnedStationPlacement.positionFor(StationId(7)),
            a,
        )
    }

    @Test
    fun `the first station sits at the documented lattice slot`() {
        // slot 0 ⇒ column 0, row 0 ⇒ x = BASE.x + (0 - (COLUMNS-1)/2)*STEP_X, y = BASE.y.
        val expectedX = OwnedStationPlacement.BASE.x + (0 - (OwnedStationPlacement.COLUMNS - 1) / 2f) * OwnedStationPlacement.STEP_X
        assertEquals(expectedX, OwnedStationPlacement.positionFor(StationId(0)).x, 1e-4f)
        assertEquals(OwnedStationPlacement.BASE.y, OwnedStationPlacement.positionFor(StationId(0)).y, 1e-4f)
    }

    // --- challenger #1: pairwise-distinct for arbitrary N -----------------------------------------------

    @Test
    fun `positions are pairwise-distinct for N stations in one sector`() {
        // N = 12 (> 3, and spanning more than COLUMNS=5 so the row-wrap is exercised too).
        val n = 12
        val positions = (0L until n).map { OwnedStationPlacement.positionFor(StationId(it)) }
        assertEquals(
            "all $n placements must be pairwise-distinct (injective in the id — challenger #1)",
            n,
            positions.toSet().size,
        )
    }

    @Test
    fun `row wraps after COLUMNS stations`() {
        // Station COLUMNS sits one row deeper (more negative y) than station 0, same column.
        val first = OwnedStationPlacement.positionFor(StationId(0))
        val wrapped = OwnedStationPlacement.positionFor(StationId(OwnedStationPlacement.COLUMNS.toLong()))
        assertEquals("the wrapped slot shares column 0's x", first.x, wrapped.x, 1e-4f)
        assertTrue("the wrapped slot fans further south (smaller y)", wrapped.y < first.y)
    }

    // --- challenger #1: clearance from the authored hazards ---------------------------------------------

    @Test
    fun `every placement stays clear of Alpha Station and the raider picket zone`() {
        for (id in 0L until 12L) {
            val pos = OwnedStationPlacement.positionFor(StationId(id))

            val toAlpha = (pos - alphaStationPos).length
            assertTrue(
                "placement $id ($pos) must be well clear of Alpha Station at $alphaStationPos (was $toAlpha)",
                toAlpha > 600f,
            )

            val toPicket = (pos - picketCenter).length
            assertTrue(
                "placement $id ($pos) must sit outside the alpha-raider-picket zone (r$picketRadius, was $toPicket)",
                toPicket > picketRadius,
            )
        }
    }
}
