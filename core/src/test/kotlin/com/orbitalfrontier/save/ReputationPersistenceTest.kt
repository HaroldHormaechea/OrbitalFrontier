package com.orbitalfrontier.save

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reputation-persistence tests (UC14 AC#2) — the v11→v12 `reputation` table and the save/reload
 * round-trip of the player's per-faction standings.
 *
 * Each test runs against an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code path that
 * runs on the Android driver on device); "app restart" is a fresh repository over the same live DB.
 * The contract mirrors the cargo / field-depletion snapshots: only NON-NEUTRAL standings are stored
 * (a faction with no row is neutral 0), a stored value is coerced into the [ReputationParams] bounds on
 * load, and an unknown faction slug (an evolved/removed faction) is skipped — never stranded.
 */
class ReputationPersistenceTest {
    private val league = Factions.LEAGUE.id
    private val independents = Factions.INDEPENDENTS.id
    private val params = ReputationParams()

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

    private fun repo() = SqlDelightGameStateRepository(database, NoOpLogger, com.orbitalfrontier.platform.FixedClock)

    private fun reputationRowCount(): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM reputation",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
            binders = null,
        ).value

    @Test
    fun `the fresh v12 schema carries the reputation table`() {
        assertTrue(
            "a fresh Schema.create DB has the reputation table",
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'reputation'",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
                },
                parameters = 0,
                binders = null,
            ).value,
        )
    }

    @Test
    fun `non-neutral standings survive a save and reload exactly`() {
        val saved = WorldState(reputation = Reputation(mapOf(league to 40, independents to -25)))
        repo().saveGameState(saved)

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals("the league standing survives", 40, reloaded!!.reputation.valueFor(league))
        assertEquals("the independents standing survives", -25, reloaded.reputation.valueFor(independents))
    }

    @Test
    fun `a neutral standing is not stored and reads back as EMPTY`() {
        // An explicit 0 entry is neutral: the save writes only non-zero rows, so nothing is persisted.
        val saved = WorldState(reputation = Reputation(mapOf(league to 0)))
        repo().saveGameState(saved)

        assertEquals("a neutral standing writes no rows", 0L, reputationRowCount())
        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertTrue("a fully-neutral save reads back EMPTY", reloaded!!.reputation.byFaction.isEmpty())
    }

    @Test
    fun `a fresh game with no reputation reads back EMPTY`() {
        repo().saveGameState(WorldState())
        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals("a fresh save is neutral with every faction", Reputation.EMPTY, reloaded!!.reputation)
    }

    @Test
    fun `the save is a full-snapshot rewrite — a dropped faction does not linger`() {
        // First save two standings, then save again with only one: the delete-then-insert rewrite must not
        // leave the dropped faction behind (the cargo/mission snapshot contract).
        repo().saveGameState(WorldState(reputation = Reputation(mapOf(league to 40, independents to -25))))
        repo().saveGameState(WorldState(reputation = Reputation(mapOf(league to 40))))

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals("the kept standing survives", 40, reloaded!!.reputation.valueFor(league))
        assertEquals("the dropped faction is gone (neutral)", 0, reloaded.reputation.valueFor(independents))
        assertEquals("only the one kept row remains", 1L, reputationRowCount())
    }

    @Test
    fun `a stored value beyond the bounds is coerced into range on load`() {
        // Seed a save header first (so loadGameState returns the save), then insert rows directly that are
        // OUT of the params' bounds (e.g. from an older save with wider bounds, or a corrupted row): the
        // repository clamps them into [min, max] on load.
        repo().saveGameState(WorldState())
        database.orbitalFrontierQueries.insertReputation(slot_id = 0L, faction_id = league.value, value_ = 999L)
        database.orbitalFrontierQueries.insertReputation(slot_id = 0L, faction_id = independents.value, value_ = -999L)

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals("an over-max value clamps to the ceiling", params.max, reloaded!!.reputation.valueFor(league))
        assertEquals("an under-min value clamps to the floor", params.min, reloaded.reputation.valueFor(independents))
    }

    @Test
    fun `an unknown persisted faction slug is skipped on load (never stranded)`() {
        // A standing for a faction the catalog no longer knows (an evolved/removed faction) alongside a
        // known one: the unknown row is dropped with a WARN; the known one still loads.
        repo().saveGameState(WorldState())
        database.orbitalFrontierQueries.insertReputation(slot_id = 0L, faction_id = "pirates", value_ = 50L)
        database.orbitalFrontierQueries.insertReputation(slot_id = 0L, faction_id = league.value, value_ = 30L)

        val reloaded = repo().loadGameState()
        assertNotNull(reloaded)
        assertEquals("the known faction loads", 30, reloaded!!.reputation.valueFor(league))
        assertEquals("the unknown slug is skipped, not stranded", 0, reloaded.reputation.valueFor(FactionId("pirates")))
        assertEquals("only the known standing is in the map", setOf(league), reloaded.reputation.byFaction.keys)
    }
}
