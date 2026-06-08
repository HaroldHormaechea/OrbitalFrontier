package com.orbitalfrontier.faction

import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the authored faction catalog [Factions] and station ownership (UC14 AC#1) — factions are
 * defined as data and each (faction) station belongs to a faction.
 *
 * Factions are fixed authored constants resolved at runtime, never row-persisted: a save stores only a
 * [FactionId] slug, resolved back here on load, and an unknown slug degrades gracefully ([Factions.byId]
 * returns null). These cases pin the catalog and the MVP map's station→faction wiring (the DEV CONSTANTS).
 */
class FactionTest {
    @Test
    fun `the catalog holds the two authored factions`() {
        assertEquals(listOf(FactionId("league"), FactionId("independents")), Factions.all.map { it.id })
        assertEquals("Trade League", Factions.LEAGUE.displayName)
        assertEquals("Independents", Factions.INDEPENDENTS.displayName)
    }

    @Test
    fun `byId resolves a known slug and returns null for an unknown one`() {
        assertEquals(Factions.LEAGUE, Factions.byId(FactionId("league")))
        assertEquals(Factions.INDEPENDENTS, Factions.byId(FactionId("independents")))
        assertNull("an evolved/removed slug degrades to null, never crashes", Factions.byId(FactionId("pirates")))
    }

    @Test
    fun `a blank faction id is rejected at construction`() {
        try {
            FactionId("")
            throw AssertionError("expected an IllegalArgumentException for a blank FactionId")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `the MVP map wires each station to its authored faction`() {
        val world = MvpSectorMap.build()

        fun factionOf(stationId: String): FactionId? {
            for (sector in world.sectors) {
                val station = sector.station(PoiId(stationId))
                if (station != null) return station.factionId
            }
            throw AssertionError("station $stationId not found in the MVP map")
        }

        assertEquals(Factions.LEAGUE.id, factionOf("alpha-station"))
        assertEquals(Factions.LEAGUE.id, factionOf("beta-station"))
        assertEquals(Factions.INDEPENDENTS.id, factionOf("gamma-junkyard"))
    }
}
