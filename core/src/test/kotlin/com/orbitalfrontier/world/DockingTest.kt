package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure dock/undock resolver (UC05 AC#5) — the docking analogue of
 * [GateTraversalTest].
 *
 * Exercises [Docking.availableStation] (in/out of range, inclusive boundary, nearest-with-tie-break)
 * and [Docking.resolve] (DOCK in/out of range, UNDOCK, idempotent no-ops, the already-docked guard),
 * all as side-effect-free functions of (world, sector, ship position, action). The MVP geometry —
 * Alpha Station at `(0, 600)`, dock radius 100 — is read from the production [MvpSectorMap] so the
 * tests track the real map; the tie-break test builds a tiny bespoke world for two overlapping
 * stations (the MVP map never overlaps two).
 */
class DockingTest {
    private val world = MvpSectorMap.build()
    private val alpha = SectorId("alpha")
    private val alphaStation = PoiId("alpha-station")

    /** Alpha Station's authored position, read from the production map. */
    private val stationPos = world.sector(alpha).station(alphaStation)!!.position

    // --- availableStation ---

    @Test
    fun `a ship on top of a station is in dock range`() {
        assertEquals(alphaStation, Docking.availableStation(world, alpha, stationPos)?.id)
    }

    @Test
    fun `a ship exactly on the dock ring is in range (boundary is inclusive)`() {
        // 100 wu south of the station ⇒ distance == dockingRadius, which availableStation treats as in range.
        val onTheRing = stationPos + Vec2(0f, -100f)
        assertEquals(100f, (onTheRing - stationPos).length, 1e-3f)
        assertEquals(alphaStation, Docking.availableStation(world, alpha, onTheRing)?.id)
    }

    @Test
    fun `a ship just outside the dock ring is not in range`() {
        val justOutside = stationPos + Vec2(0f, -101f)
        assertNull(Docking.availableStation(world, alpha, justOutside))
    }

    @Test
    fun `the sector centre is out of every station's dock range`() {
        // Alpha Station sits 600 wu from the centre, well beyond its 100 wu dock radius.
        assertNull(Docking.availableStation(world, alpha, Vec2.ZERO))
    }

    @Test
    fun `when two stations overlap the ship the nearest wins`() {
        val w = twoStationWorld()
        // Closer to "near" (10 wu) than "far" (90 wu) — nearest wins.
        assertEquals(PoiId("near"), Docking.availableStation(w, TEST_SECTOR, Vec2(10f, 0f))?.id)
    }

    @Test
    fun `an exact distance tie breaks by authored POI order`() {
        val w = twoStationWorld()
        // Equidistant from both (50 wu): the first station in authored order wins (deterministic).
        assertEquals(PoiId("near"), Docking.availableStation(w, TEST_SECTOR, Vec2(50f, 0f))?.id)
    }

    // --- resolve ---

    @Test
    fun `DOCK while undocked and in range docks the in-range station`() {
        val next = Docking.resolve(world, alpha, dockedStation = null, shipPosition = stationPos, action = DockAction.DOCK)
        assertEquals(alphaStation, next)
    }

    @Test
    fun `DOCK while undocked but out of range stays undocked`() {
        val next = Docking.resolve(world, alpha, dockedStation = null, shipPosition = Vec2.ZERO, action = DockAction.DOCK)
        assertNull(next)
    }

    @Test
    fun `UNDOCK while docked returns to flight`() {
        val next = Docking.resolve(world, alpha, dockedStation = alphaStation, shipPosition = stationPos, action = DockAction.UNDOCK)
        assertNull(next)
    }

    @Test
    fun `UNDOCK while already undocked is a no-op`() {
        val next = Docking.resolve(world, alpha, dockedStation = null, shipPosition = Vec2.ZERO, action = DockAction.UNDOCK)
        assertNull(next)
    }

    @Test
    fun `DOCK while already docked holds the current station (no re-dock)`() {
        // Even sitting on a *different* station, an already-docked ship doesn't switch (DOCK is a no-op).
        val next =
            Docking.resolve(world, alpha, dockedStation = PoiId("beta-station"), shipPosition = stationPos, action = DockAction.DOCK)
        assertEquals(PoiId("beta-station"), next)
    }

    @Test
    fun `NONE leaves the dock state unchanged (idempotent), docked or not`() {
        assertNull(
            "NONE while undocked stays undocked",
            Docking.resolve(world, alpha, dockedStation = null, shipPosition = stationPos, action = DockAction.NONE),
        )
        assertEquals(
            "NONE while docked stays docked",
            alphaStation,
            Docking.resolve(world, alpha, dockedStation = alphaStation, shipPosition = Vec2.ZERO, action = DockAction.NONE),
        )
    }

    @Test
    fun `resolution is a pure function — identical inputs yield an identical result`() {
        val a = Docking.resolve(world, alpha, dockedStation = null, shipPosition = stationPos, action = DockAction.DOCK)
        val b = Docking.resolve(world, alpha, dockedStation = null, shipPosition = stationPos, action = DockAction.DOCK)
        assertEquals(a, b)
    }

    private companion object {
        val TEST_SECTOR = SectorId("test")

        /** A bespoke single-sector world with two overlapping stations, for nearest/tie-break tests. */
        fun twoStationWorld(): SectorWorld =
            SectorWorld(
                listOf(
                    Sector(
                        id = TEST_SECTOR,
                        displayName = "Test",
                        contentExtent = 1000f,
                        pois =
                            listOf(
                                Station(PoiId("near"), Vec2(0f, 0f), "Near", dockingRadius = 200f),
                                Station(PoiId("far"), Vec2(100f, 0f), "Far", dockingRadius = 200f),
                            ),
                    ),
                ),
            )
    }
}
