package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.FixedClock
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence round-trip tests for the UC54 `consumedPois` save-wide delta (AC#4), exercised against an
 * in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code the Android driver runs on device).
 *
 * The contract:
 *  - a [WorldState.consumedPois] set (a scavenged derelict / triggered distress id) round-trips save→reload
 *    through the new `consumed_poi` table — proving a scavenged wreck stays empty across save/reload (AC#4);
 *  - consumption is **monotonic**, exactly like `revealedContacts`: a later save only adds ids;
 *  - the clear/overwrite path (`clearSave`) deletes the `consumed_poi` rows, so a fresh game starts with an
 *    empty consumed set (no stale rows survive a wipe).
 */
class ConsumedPoiPersistenceTest {
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

    private fun stateWithConsumed(consumed: Set<PoiId>): WorldState =
        WorldState(
            currentSector = SectorId("beta"),
            fleet = singleShipFleet(),
        ).copy(consumedPois = consumed)

    @Test
    fun `a consumed POI set round-trips exactly through a reload`() {
        val consumed = setOf(PoiId("beta-derelict"), PoiId("beta-distress"))
        newRepository().saveGameState(stateWithConsumed(consumed))

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = newRepository().loadGameState()

        assertEquals("the consumed-POI set survives a save/reload", consumed, reloaded?.consumedPois)
    }

    @Test
    fun `an empty consumed set reloads as empty (a fresh game has consumed nothing)`() {
        newRepository().saveGameState(stateWithConsumed(emptySet()))

        val reloaded = newRepository().loadGameState()

        assertTrue("no consumed rows reload as 'nothing consumed'", reloaded?.consumedPois?.isEmpty() == true)
    }

    @Test
    fun `consumption is monotonic — a later save adds ids and keeps the earlier ones`() {
        val repo = newRepository()
        repo.saveGameState(stateWithConsumed(setOf(PoiId("beta-derelict"))))
        // A second save with the derelict AND the distress: both end up persisted (insert-or-ignore).
        repo.saveGameState(stateWithConsumed(setOf(PoiId("beta-derelict"), PoiId("beta-distress"))))

        val reloaded = newRepository().loadGameState()

        assertEquals(
            "both consumed ids are persisted across the monotonic saves",
            setOf(PoiId("beta-derelict"), PoiId("beta-distress")),
            reloaded?.consumedPois,
        )
    }

    @Test
    fun `clearSave deletes the consumed_poi rows so a fresh game starts with an empty set`() {
        val repo = newRepository()
        repo.saveGameState(stateWithConsumed(setOf(PoiId("beta-derelict"), PoiId("beta-distress"))))

        // Wipe the slot (the UC21 new-game / clear path), then start a brand-new save with nothing consumed.
        repo.clearSave()
        repo.saveGameState(stateWithConsumed(emptySet()))

        val reloaded = newRepository().loadGameState()

        assertTrue(
            "no stale consumed_poi rows survive a clear — the new game has consumed nothing",
            reloaded?.consumedPois?.isEmpty() == true,
        )
    }
}
