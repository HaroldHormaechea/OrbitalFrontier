package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.FixedClock
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.station.OwnedStation
import com.orbitalfrontier.station.OwnedStationPlacement
import com.orbitalfrontier.station.OwnedStationProjection
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Challenger #2: a **save → reload round-trip** proving an owned station's placement persists (UC51 AC#4).
 *
 * UC51 derives placement ([OwnedStationPlacement]) from the station id rather than storing a column, so
 * "placement persists" is met by the id surviving the save. This test makes that explicit: build a
 * WorldState owning a station, persist it through the real [SqlDelightGameStateRepository] (the in-memory
 * JDBC driver, ADR 0003 — the same `core` code the device runs), reload it, and assert the reloaded
 * station **re-derives to the exact same projected position**. Run against two stations (ids 0 and 1) so a
 * distinct-per-id placement is also exercised across the cycle.
 *
 * An "app restart" is simulated by constructing a fresh repository over the same live driver.
 */
class Uc51OwnedStationPlacementSaveReloadTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
    }

    private fun newRepository() = SqlDelightGameStateRepository(database, NoOpLogger, FixedClock)

    @Test
    fun `an owned station re-derives to the same placement after a save and reload`() {
        val alpha = SectorId("alpha")
        val station0 = OwnedStation.founded(StationId(0), alpha, StationModuleCatalog.COMMERCE_HUB)
        val station1 = OwnedStation.founded(StationId(1), alpha, StationModuleCatalog.RETROFIT_BAY)
        val state =
            WorldState(
                currentSector = alpha,
                fleet = singleShipFleet(),
                stations = StationRegistry(listOf(station0, station1)),
            )

        // Placements derived BEFORE the persistence cycle.
        val placeBefore0 = OwnedStationProjection.stationFor(station0).position
        val placeBefore1 = OwnedStationProjection.stationFor(station1).position

        newRepository().saveGameState(state)
        val reloaded = newRepository().loadGameState()!!

        assertEquals("both owned stations survive the round-trip", 2, reloaded.stations.size)

        // The reloaded stations re-derive to the SAME positions — placement persisted via the id (AC#4).
        val reloaded0 = reloaded.stations.station(StationId(0))!!
        val reloaded1 = reloaded.stations.station(StationId(1))!!
        assertEquals("station 0's placement is identical after reload", placeBefore0, OwnedStationProjection.stationFor(reloaded0).position)
        assertEquals("station 1's placement is identical after reload", placeBefore1, OwnedStationProjection.stationFor(reloaded1).position)

        // And they remain distinct per id (the placement is injective, not a constant).
        assertEquals(OwnedStationPlacement.positionFor(StationId(0)), OwnedStationProjection.stationFor(reloaded0).position)
        assertEquals(OwnedStationPlacement.positionFor(StationId(1)), OwnedStationProjection.stationFor(reloaded1).position)
        org.junit.Assert.assertNotEquals(
            "distinct ids keep distinct placements across the cycle",
            OwnedStationProjection.stationFor(reloaded0).position,
            OwnedStationProjection.stationFor(reloaded1).position,
        )
    }
}
