package com.orbitalfrontier.station

import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.ship.Shipyard
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Sector
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.StationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OwnedStationProjection] (UC51 AC#2/#3) — "project, don't author": owned stations
 * become synthetic [com.orbitalfrontier.world.Station] POIs at render/dock time.
 *
 * Pins the determinism guarantee (a no-owned-station sector projects to `sector.pois` **unchanged** —
 * the byte-identity anchor), the id ↔ station round-trip, the module→desk composition (COMMERCE desk
 * iff a commerce module, RETROFIT desk iff a retrofit module, **all other capabilities off** so the
 * owned hub offers only its modules' functions — pitfall #4), and the authored-first dock resolution.
 */
class OwnedStationProjectionTest {
    private val alpha = SectorId("alpha")
    private val beta = SectorId("beta")
    private val world = MvpSectorMap.build()

    private fun commerceStation(
        id: Long,
        sector: SectorId = alpha,
    ): OwnedStation = OwnedStation.founded(StationId(id), sector, StationModuleCatalog.COMMERCE_HUB)

    private fun registryOf(vararg stations: OwnedStation): StationRegistry = StationRegistry(stations.sortedBy { it.id.value })

    // --- id ↔ station round-trip + display name -------------------------------------------------------

    @Test
    fun `poiId round-trips back to the station id`() {
        val id = StationId(5)
        val poiId = OwnedStationProjection.poiIdFor(id)
        assertEquals(id, OwnedStationProjection.ownedStationIdOf(poiId))
    }

    @Test
    fun `an authored poi id is not an owned-station id`() {
        assertNull(OwnedStationProjection.ownedStationIdOf(PoiId("alpha-station")))
        assertNull(OwnedStationProjection.ownedStationIdOf(PoiId("owned-station-notanumber")))
    }

    @Test
    fun `display name is the 1-based outpost number`() {
        assertEquals("Outpost 1", OwnedStationProjection.displayNameFor(StationId(0)))
        assertEquals("Outpost 8", OwnedStationProjection.displayNameFor(StationId(7)))
    }

    // --- stationFor composition (pitfall #4: ONLY the modules' functions) ------------------------------

    @Test
    fun `a commerce-only station carries the commerce desk and nothing else`() {
        val station = OwnedStationProjection.stationFor(commerceStation(0))

        assertEquals(OwnedStationProjection.poiIdFor(StationId(0)), station.id)
        assertEquals(OwnedStationPlacement.positionFor(StationId(0)), station.position)
        assertEquals("Outpost 1", station.displayName)
        assertEquals("the commerce module surfaces the commerce trade desk", OwnedStationMarkets.COMMERCE_DESK, station.market)
        assertEquals("no retrofit module ⇒ no outfit desk", OutfitMarket.EMPTY, station.outfitMarket)
        // pitfall #4: every other station capability is OFF — no shipyard, crew, faction, build, default kind.
        assertEquals(Shipyard.EMPTY, station.shipyard)
        assertFalse("an owned station never hires crew", station.hiresCrew)
        assertNull("an owned station is unaligned", station.factionId)
        assertFalse("an owned station is not itself build-capable", station.buildsStations)
        assertEquals(StationKind.DEALER, station.kind)
    }

    @Test
    fun `a retrofit-only station carries the retrofit desk and an empty trade desk`() {
        val retrofit = OwnedStation.founded(StationId(1), alpha, StationModuleCatalog.RETROFIT_BAY)
        val station = OwnedStationProjection.stationFor(retrofit)

        assertEquals("no commerce module ⇒ empty trade desk", StationMarket.EMPTY, station.market)
        assertEquals("the retrofit module surfaces the outfit desk", OwnedStationMarkets.RETROFIT_DESK, station.outfitMarket)
    }

    @Test
    fun `a station with both modules carries both desks`() {
        val both = OwnedStation.founded(StationId(2), alpha, StationModuleCatalog.COMMERCE_HUB).addModule(StationModuleCatalog.RETROFIT_BAY)
        val station = OwnedStationProjection.stationFor(both)
        assertEquals(OwnedStationMarkets.COMMERCE_DESK, station.market)
        assertEquals(OwnedStationMarkets.RETROFIT_DESK, station.outfitMarket)
    }

    // --- stationsIn -----------------------------------------------------------------------------------

    @Test
    fun `stationsIn returns a synthetic station per owned station in the sector in id order`() {
        val registry = registryOf(commerceStation(0), commerceStation(1), commerceStation(2, beta))
        val inAlpha = OwnedStationProjection.stationsIn(alpha, registry)
        assertEquals("only the two Alpha-anchored stations surface here", 2, inAlpha.size)
        assertEquals(
            listOf(OwnedStationProjection.poiIdFor(StationId(0)), OwnedStationProjection.poiIdFor(StationId(1))),
            inAlpha.map { it.id },
        )
    }

    @Test
    fun `stationsIn is empty when the player owns no station in the sector`() {
        assertTrue(OwnedStationProjection.stationsIn(alpha, StationRegistry.EMPTY).isEmpty())
        assertTrue(OwnedStationProjection.stationsIn(alpha, registryOf(commerceStation(0, beta))).isEmpty())
    }

    // --- poisIn (the byte-identity anchor) ------------------------------------------------------------

    @Test
    fun `poisIn returns the sector pois unchanged when no owned station is anchored here`() {
        val sector = world.sector(alpha)
        val result = OwnedStationProjection.poisIn(sector, StationRegistry.EMPTY)
        assertSame(
            "with no owned station the SAME list instance is returned (effectivePois == sector.pois, byte-identical)",
            sector.pois,
            result,
        )
    }

    @Test
    fun `poisIn appends the owned-station projections when present`() {
        val sector = world.sector(alpha)
        val registry = registryOf(commerceStation(0))
        val result: List<Poi> = OwnedStationProjection.poisIn(sector, registry)
        assertEquals(sector.pois.size + 1, result.size)
        assertTrue("the authored POIs are preserved", result.containsAll(sector.pois))
        assertTrue(
            "the owned-station projection is appended",
            result.any { it.id == OwnedStationProjection.poiIdFor(StationId(0)) },
        )
    }

    @Test
    fun `poisIn on a bespoke POI-less sector still returns its pois unchanged`() {
        val bare = Sector(SectorId("solo"), "Solo", pois = emptyList(), contentExtent = 100f)
        assertSame(bare.pois, OwnedStationProjection.poisIn(bare, StationRegistry.EMPTY))
    }

    // --- resolveDocked (authored-first, then owned projection) ----------------------------------------

    @Test
    fun `resolveDocked returns the authored station first`() {
        val authored =
            OwnedStationProjection.resolveDocked(world, alpha, StationRegistry.EMPTY, PoiId("alpha-station"))
        assertEquals("alpha-station", authored?.id?.value)
    }

    @Test
    fun `resolveDocked resolves an owned-station projection when docked at one`() {
        val registry = registryOf(commerceStation(0))
        val owned =
            OwnedStationProjection.resolveDocked(world, alpha, registry, OwnedStationProjection.poiIdFor(StationId(0)))
        assertEquals(OwnedStationProjection.poiIdFor(StationId(0)), owned?.id)
        assertEquals("the docked owned station yields its commerce desk", OwnedStationMarkets.COMMERCE_DESK, owned?.market)
    }

    @Test
    fun `resolveDocked is null when undocked, unowned, or anchored in another sector`() {
        val registry = registryOf(commerceStation(0))
        assertNull("undocked", OwnedStationProjection.resolveDocked(world, alpha, registry, null))
        assertNull(
            "owned but anchored elsewhere — not dockable from this sector",
            OwnedStationProjection.resolveDocked(world, beta, registry, OwnedStationProjection.poiIdFor(StationId(0))),
        )
        assertNull(
            "an owned id the registry does not contain",
            OwnedStationProjection.resolveDocked(world, alpha, registry, OwnedStationProjection.poiIdFor(StationId(99))),
        )
    }
}
