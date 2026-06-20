package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.StationMarket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the UC51 `extraStations` overloads of [Docking] (AC#3) — owned-station projections made
 * dockable alongside the sector's authored stations.
 *
 * Two contracts:
 *  - **byte-identity:** with an EMPTY `extraStations` list the new overloads are identical to the base
 *    [Docking.availableStation] / [Docking.resolve] (so a no-owned-station tick resolves unchanged), and
 *    the old (no-`extraStations`) signatures delegate to the new ones with an empty list;
 *  - **owned stations dock:** an in-range projection is dockable and a DOCK commits to its synthetic id;
 *    authored stations are still considered first; the nearest in-range station overall wins.
 */
class Uc51OwnedStationDockingTest {
    private val world = MvpSectorMap.build()
    private val alpha = SectorId("alpha")
    private val alphaStation = PoiId("alpha-station")
    private val alphaStationPos = world.sector(alpha).station(alphaStation)!!.position

    /** A synthetic owned-station projection placed in open space, far from Alpha Station. */
    private val ownedId = PoiId("owned-station-0")
    private val ownedPos = Vec2(-320f, -600f)
    private val ownedStation =
        Station(
            id = ownedId,
            position = ownedPos,
            displayName = "Outpost 1",
            market = StationMarket(emptyMap()),
        )

    // --- byte-identity with an empty extras list ------------------------------------------------------

    @Test
    fun `the extra-stations overload with an empty list equals the base availableStation`() {
        val pos = alphaStationPos + Vec2(0f, -50f)
        assertEquals(
            Docking.availableStation(world, alpha, pos),
            Docking.availableStation(world, alpha, pos, emptyList()),
        )
    }

    @Test
    fun `the old availableStation signature delegates byte-identically`() {
        // On-the-ring (inclusive) boundary — the exact behaviour the base overload documents.
        val onRing = alphaStationPos + Vec2(0f, -100f)
        assertEquals(alphaStation, Docking.availableStation(world, alpha, onRing)?.id)
        assertEquals(
            Docking.availableStation(world, alpha, onRing, emptyList())?.id,
            Docking.availableStation(world, alpha, onRing)?.id,
        )
    }

    @Test
    fun `the resolve overload with an empty list equals the base resolve`() {
        val pos = alphaStationPos
        assertEquals(
            Docking.resolve(world, alpha, dockedStation = null, shipPosition = pos, action = DockAction.DOCK),
            Docking.resolve(world, alpha, dockedStation = null, shipPosition = pos, action = DockAction.DOCK, extraStations = emptyList()),
        )
    }

    // --- owned stations become dockable ---------------------------------------------------------------

    @Test
    fun `an in-range owned projection is reported dockable`() {
        val available = Docking.availableStation(world, alpha, ownedPos, listOf(ownedStation))
        assertEquals(ownedId, available?.id)
    }

    @Test
    fun `out of range, an owned projection is not dockable`() {
        val far = ownedPos + Vec2(0f, -101f)
        assertNull(Docking.availableStation(world, alpha, far, listOf(ownedStation)))
    }

    @Test
    fun `DOCK commits to an in-range owned projection`() {
        val next =
            Docking.resolve(
                world,
                alpha,
                dockedStation = null,
                shipPosition = ownedPos,
                action = DockAction.DOCK,
                extraStations = listOf(ownedStation),
            )
        assertEquals(ownedId, next)
    }

    @Test
    fun `an authored station still docks when both are surfaced but only it is in range`() {
        val next =
            Docking.resolve(
                world,
                alpha,
                dockedStation = null,
                shipPosition = alphaStationPos,
                action = DockAction.DOCK,
                extraStations = listOf(ownedStation),
            )
        assertEquals(alphaStation, next)
    }
}
