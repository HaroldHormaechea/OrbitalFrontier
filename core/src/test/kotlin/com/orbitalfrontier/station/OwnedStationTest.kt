package com.orbitalfrontier.station

import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [OwnedStation] value (UC15 AC#2/#3). Covers gap-tolerant module placement
 * ([OwnedStation.addModule] always fills the lowest free slot) and the derived
 * [OwnedStation.availableFunctions] (the set of [StationFunction]s the installed modules expose,
 * resolved through the catalog — never stored).
 */
class OwnedStationTest {
    private val sector = SectorId("alpha")
    private val commerceHub = StationModuleCatalog.COMMERCE_HUB
    private val retrofitBay = StationModuleCatalog.RETROFIT_BAY

    @Test
    fun `founded carries the first module in slot 0`() {
        val station = OwnedStation.founded(StationId(3), sector, commerceHub)
        assertEquals(StationId(3), station.id)
        assertEquals(sector, station.sector)
        assertEquals("first module in slot 0", commerceHub, station.moduleAt(0))
        assertEquals(1, station.moduleCount)
    }

    @Test
    fun `addModule fills the lowest free slot`() {
        val station =
            OwnedStation.founded(StationId(0), sector, commerceHub)
                .addModule(retrofitBay)
        assertEquals("commerce hub stays in slot 0", commerceHub, station.moduleAt(0))
        assertEquals("retrofit bay lands in slot 1", retrofitBay, station.moduleAt(1))
        assertEquals(2, station.moduleCount)
    }

    @Test
    fun `addModule is gap-tolerant — a hole in the slot map is filled before extending`() {
        // Hand-build a station with slots 0 and 2 filled (slot 1 is a gap, like a removed/absent module).
        val gapped =
            OwnedStation(
                id = StationId(0),
                sector = sector,
                modules = mapOf(0 to commerceHub, 2 to retrofitBay),
            )
        val grown = gapped.addModule(commerceHub)
        assertEquals("the gap at slot 1 is filled first (lowest free index)", commerceHub, grown.moduleAt(1))
        assertEquals(3, grown.moduleCount)
    }

    @Test
    fun `availableFunctions derives the function set from the installed modules`() {
        val station =
            OwnedStation.founded(StationId(0), sector, commerceHub)
                .addModule(retrofitBay)
        assertEquals(
            "both module functions are exposed (AC#2)",
            setOf(StationFunction.COMMERCE, StationFunction.RETROFIT),
            station.availableFunctions(),
        )
    }

    @Test
    fun `a commerce-only station exposes only COMMERCE`() {
        val station = OwnedStation.founded(StationId(0), sector, commerceHub)
        assertEquals(setOf(StationFunction.COMMERCE), station.availableFunctions())
        assertTrue("COMMERCE present (AC#2/#6)", StationFunction.COMMERCE in station.availableFunctions())
    }

    @Test
    fun `a module slug the catalog no longer knows contributes no function`() {
        val station =
            OwnedStation(
                id = StationId(0),
                sector = sector,
                modules = mapOf(0 to commerceHub, 1 to StationModuleId("removed-module")),
            )
        assertEquals(
            "only the catalogued commerce hub contributes; the unknown id is skipped",
            setOf(StationFunction.COMMERCE),
            station.availableFunctions(),
        )
    }

    @Test
    fun `an empty slot reads back as null`() {
        val station = OwnedStation.founded(StationId(0), sector, commerceHub)
        assertNull("slot 1 is empty on a one-module station", station.moduleAt(1))
    }

    @Test
    fun `two stations with the same modules in the same slots compare equal regardless of build order`() {
        val a =
            OwnedStation.founded(StationId(0), sector, commerceHub)
                .addModule(retrofitBay)
        val b =
            OwnedStation(
                id = StationId(0),
                sector = sector,
                modules = mapOf(1 to retrofitBay, 0 to commerceHub),
            )
        assertEquals("value equality is order-insensitive (record/replay stability)", a, b)
    }
}
