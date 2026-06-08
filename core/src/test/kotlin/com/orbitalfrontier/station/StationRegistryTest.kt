package com.orbitalfrontier.station

import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [StationRegistry] (UC15 AC#3). Covers the sorted-unique invariant, the pure
 * deterministic [StationRegistry.nextStationId] (`max(id) + 1`, including the empty → 0 case), and the
 * grow-only mutation paths (`addStation` / `withStation`).
 */
class StationRegistryTest {
    private val sector = SectorId("alpha")

    private fun station(id: Long): OwnedStation =
        OwnedStation.founded(StationId(id), sector, StationModuleCatalog.COMMERCE_HUB)

    @Test
    fun `EMPTY owns nothing`() {
        assertTrue(StationRegistry.EMPTY.isEmpty)
        assertEquals(0, StationRegistry.EMPTY.size)
    }

    @Test
    fun `nextStationId is max plus 1, and 0 when empty`() {
        assertEquals("empty registry allocates id 0", StationId(0), StationRegistry.EMPTY.nextStationId())
        val twoStations = StationRegistry.EMPTY.addStation(station(0)).addStation(station(1))
        assertEquals("max(0,1)+1 = 2", StationId(2), twoStations.nextStationId())
    }

    @Test
    fun `nextStationId is max plus 1 even with a gap in the id sequence`() {
        // Stations 0 and 5 owned (e.g. a future remove path leaves a gap); next is still max+1, never reused.
        val registry = StationRegistry(listOf(station(0), station(5)))
        assertEquals(StationId(6), registry.nextStationId())
    }

    @Test
    fun `addStation keeps the station list sorted by id`() {
        val registry =
            StationRegistry.EMPTY
                .addStation(station(2))
                .addStation(station(0))
                .addStation(station(1))
        assertEquals("stored sorted by id", listOf(0L, 1L, 2L), registry.stations.map { it.id.value })
    }

    @Test
    fun `addStation rejects a duplicate id`() {
        val registry = StationRegistry.EMPTY.addStation(station(0))
        assertThrows(IllegalArgumentException::class.java) { registry.addStation(station(0)) }
    }

    @Test
    fun `the registry constructor rejects unsorted or duplicate ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            StationRegistry(listOf(station(1), station(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationRegistry(listOf(station(0), station(0)))
        }
    }

    @Test
    fun `station looks up an owned station by id and returns null for an unowned id`() {
        val registry = StationRegistry.EMPTY.addStation(station(0)).addStation(station(1))
        assertEquals(StationId(1), registry.station(StationId(1))?.id)
        assertNull("an unowned id resolves to null", registry.station(StationId(9)))
    }

    @Test
    fun `withStation replaces the same-id station, preserving order and size`() {
        val registry = StationRegistry.EMPTY.addStation(station(0)).addStation(station(1))
        val updated = registry.station(StationId(0))!!.addModule(StationModuleCatalog.RETROFIT_BAY)
        val after = registry.withStation(updated)

        assertEquals("size unchanged (only grows in modules, not count)", 2, after.size)
        assertEquals("still sorted by id", listOf(0L, 1L), after.stations.map { it.id.value })
        assertEquals("station 0 now has the retrofit bay", 2, after.station(StationId(0))!!.moduleCount)
    }

    @Test
    fun `withStation rejects a station that is not owned`() {
        val registry = StationRegistry.EMPTY.addStation(station(0))
        assertThrows(IllegalArgumentException::class.java) { registry.withStation(station(9)) }
    }
}
