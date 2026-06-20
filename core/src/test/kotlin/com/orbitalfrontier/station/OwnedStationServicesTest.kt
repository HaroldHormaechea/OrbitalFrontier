package com.orbitalfrontier.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OwnedStationServices] (UC51 AC#3, pitfall #4) — the COMMERCE→TRADE / RETROFIT→OUTFIT
 * mapping that decides which hub buttons an owned station shows.
 *
 * The contract: an owned station offers ONLY the services its installed modules expose (no duplicated
 * refuel / missions / crew / shipyard / fleet / disembark — pitfall #4); the caller adds UNDOCK, so this
 * mapping must NOT include it.
 */
class OwnedStationServicesTest {
    @Test
    fun `COMMERCE maps to TRADE`() {
        assertEquals(setOf(HubService.TRADE), OwnedStationServices.hubServices(setOf(StationFunction.COMMERCE)))
    }

    @Test
    fun `RETROFIT maps to OUTFIT`() {
        assertEquals(setOf(HubService.OUTFIT), OwnedStationServices.hubServices(setOf(StationFunction.RETROFIT)))
    }

    @Test
    fun `both functions map to both services`() {
        assertEquals(
            setOf(HubService.TRADE, HubService.OUTFIT),
            OwnedStationServices.hubServices(setOf(StationFunction.COMMERCE, StationFunction.RETROFIT)),
        )
    }

    @Test
    fun `no functions yields no services`() {
        assertTrue(OwnedStationServices.hubServices(emptySet()).isEmpty())
    }

    @Test
    fun `the mapping never includes UNDOCK (the caller adds it)`() {
        val all = OwnedStationServices.hubServices(setOf(StationFunction.COMMERCE, StationFunction.RETROFIT))
        assertFalse("UNDOCK is added by the caller, not by the mapping (pitfall #4)", HubService.UNDOCK in all)
        // …and none of the duplicated services leak in either.
        for (forbidden in listOf(
            HubService.REFUEL,
            HubService.MISSIONS,
            HubService.CREW,
            HubService.SHIPS,
            HubService.FLEET,
            HubService.DISEMBARK,
            HubService.BUILD,
        )) {
            assertFalse("an owned station must not offer $forbidden (pitfall #4)", forbidden in all)
        }
    }
}
