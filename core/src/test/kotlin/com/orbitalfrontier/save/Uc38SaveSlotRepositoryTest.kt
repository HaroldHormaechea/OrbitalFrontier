package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.Clock
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UC38 save-slot tests for [SqlDelightGameStateRepository] in its [SaveSlotRepository] role, exercised
 * against an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code the Android driver runs on
 * device). These tests drive the **slot-taking** members directly (not the legacy back-compat shims), so
 * they pin the new per-slot contract:
 *  - **AC#4 slot isolation** — saving / loading / deleting one slot never reads or mutates another.
 *  - **AC#5 per-slot round-trip** — each slot saves and reloads value-equal, independently.
 *  - **AC#1 slot list** — [SaveSlotRepository.listSlots] returns every configured slot index in order, an
 *    `Occupied` row (name, last-saved, credits, sector, play time) where a save exists and `Empty` elsewhere.
 *  - **AC#2 rename / delete + the name-clobber guard** — a rename sticks and a later autosave never
 *    overwrites the player's chosen name.
 *  - **AC#3 active-slot pointer** — defaults to the legacy slot and is settable.
 *
 * "App restart" is simulated by constructing a fresh repository over the same live in-memory driver, so a
 * reload genuinely goes back through SQL rather than reading an in-process field.
 */
class Uc38SaveSlotRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier
    private val clock = MutableClock(FIRST_SAVE_MILLIS)

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
        // Seed the single meta row, as the bootstrap (ensureInitialized) does on every real launch — the
        // active-slot pointer is a targeted UPDATE that relies on that row already existing.
        database.orbitalFrontierQueries.initMeta(OrbitalFrontier.Schema.version)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
    }

    private fun newRepository() = SqlDelightGameStateRepository(database, NoOpLogger, clock)

    private fun stateOf(
        sector: String,
        credits: Long,
        playTimeSeconds: Long = 0L,
    ) = WorldState(currentSector = SectorId(sector), credits = credits, playTimeSeconds = playTimeSeconds)

    // --- AC#4 / AC#5: slots are isolated and each round-trips independently ---

    @Test
    fun `two slots round-trip independently with no cross-contamination`() {
        val repo = newRepository()
        val a = stateOf("alpha", credits = 100L, playTimeSeconds = 30L)
        val b = stateOf("beta", credits = 9_999L, playTimeSeconds = 7_200L)
        assertNotEquals("precondition: the two slot states differ", a, b)

        repo.saveGameState(SlotId(1), a)
        repo.saveGameState(SlotId(2), b)

        // Each slot reloads exactly its own state (fresh repo == app restart).
        assertEquals("slot 1 reloads its own save", a, newRepository().loadGameState(SlotId(1)))
        assertEquals("slot 2 reloads its own save", b, newRepository().loadGameState(SlotId(2)))
    }

    @Test
    fun `saving one slot leaves the others empty`() {
        val repo = newRepository()
        repo.saveGameState(SlotId(1), stateOf("alpha", 100L))

        assertTrue("the written slot reports a save", repo.hasSave(SlotId(1)))
        assertFalse("the legacy slot is untouched", repo.hasSave(SlotId.LEGACY))
        assertFalse("a sibling slot is untouched", repo.hasSave(SlotId(2)))
        assertNull("an unwritten slot loads as no save", repo.loadGameState(SlotId(2)))
    }

    @Test
    fun `deleting one slot never corrupts another`() {
        val repo = newRepository()
        val a = stateOf("alpha", credits = 100L, playTimeSeconds = 30L)
        val b = stateOf("beta", credits = 555L, playTimeSeconds = 60L)
        repo.saveGameState(SlotId(1), a)
        repo.saveGameState(SlotId(2), b)

        repo.deleteSlot(SlotId(1))

        assertNull("the deleted slot is gone", newRepository().loadGameState(SlotId(1)))
        assertFalse("the deleted slot reports no save", repo.hasSave(SlotId(1)))
        assertEquals("the sibling slot survives intact (AC#4)", b, newRepository().loadGameState(SlotId(2)))
        assertTrue("the sibling slot still reports a save", repo.hasSave(SlotId(2)))
    }

    @Test
    fun `deleteSlot is idempotent on an already-empty slot`() {
        val repo = newRepository()
        repo.deleteSlot(SlotId(3)) // no-op, must not throw
        assertFalse(repo.hasSave(SlotId(3)))
    }

    // --- AC#1: the slot list ---

    @Test
    fun `listSlots returns every configured slot in order, occupied and empty`() {
        val repo = newRepository()
        clock.now = FIRST_SAVE_MILLIS
        repo.saveGameState(SlotId.LEGACY, stateOf("alpha", credits = 1_000L, playTimeSeconds = 3_661L))
        repo.saveGameState(SlotId(2), stateOf("gamma", credits = 42L, playTimeSeconds = 5L))

        val slots = repo.listSlots()

        assertEquals("one summary per configured slot", SaveSlots.COUNT, slots.size)
        assertEquals("summaries are in ascending slot order", SaveSlots.ALL, slots.map { it.slotId })

        val legacy = slots[0]
        assertTrue("slot 0 is occupied", legacy is SaveSlotSummary.Occupied)
        legacy as SaveSlotSummary.Occupied
        assertEquals("the legacy slot's default name is 'Autosave'", "Autosave", legacy.name)
        assertEquals(1_000L, legacy.credits)
        assertEquals(SectorId("alpha"), legacy.sector)
        assertEquals(3_661L, legacy.playTimeSeconds)
        assertEquals("last-saved is stamped from the clock", FIRST_SAVE_MILLIS, legacy.lastSavedEpochMillis)

        assertTrue("an unwritten slot is Empty", slots[1] is SaveSlotSummary.Empty)
        val occupied2 = slots[2]
        assertTrue(occupied2 is SaveSlotSummary.Occupied)
        assertEquals("a non-legacy slot's default name is 'Save N+1'", "Save 3", (occupied2 as SaveSlotSummary.Occupied).name)
        assertTrue("the remaining slots are Empty", slots[3] is SaveSlotSummary.Empty && slots[4] is SaveSlotSummary.Empty)
    }

    // --- AC#2: rename + the name-clobber guard ---

    @Test
    fun `renameSlot sets the player-facing name and a later autosave never clobbers it`() {
        val repo = newRepository()
        repo.saveGameState(SlotId(1), stateOf("alpha", credits = 100L))
        assertEquals("the default name before rename", "Save 2", occupiedName(repo, SlotId(1)))

        repo.renameSlot(SlotId(1), "Hero Run")
        assertEquals("the rename takes effect", "Hero Run", occupiedName(repo, SlotId(1)))

        // An autosave updates gameplay columns (credits) but MUST NOT reset the chosen name.
        repo.saveGameState(SlotId(1), stateOf("beta", credits = 250L))
        assertEquals("the autosave keeps the chosen name (name-clobber guard)", "Hero Run", occupiedName(repo, SlotId(1)))
        assertEquals("the autosave still updated gameplay state", 250L, newRepository().loadGameState(SlotId(1))?.credits)
    }

    @Test
    fun `renameSlot on an empty slot is a no-op`() {
        val repo = newRepository()
        repo.renameSlot(SlotId(3), "Nope")
        assertTrue("renaming an empty slot must not create it", repo.listSlots()[3] is SaveSlotSummary.Empty)
    }

    // --- AC#1: last-saved stamping via the injected clock ---

    @Test
    fun `last-saved is stamped from the clock and refreshes on re-save`() {
        val repo = newRepository()
        clock.now = FIRST_SAVE_MILLIS
        repo.saveGameState(SlotId(1), stateOf("alpha", 100L))
        assertEquals(FIRST_SAVE_MILLIS, (repo.listSlots()[1] as SaveSlotSummary.Occupied).lastSavedEpochMillis)

        clock.now = SECOND_SAVE_MILLIS
        repo.saveGameState(SlotId(1), stateOf("alpha", 150L))
        assertEquals(
            "a later save refreshes the slot's last-saved timestamp",
            SECOND_SAVE_MILLIS,
            (repo.listSlots()[1] as SaveSlotSummary.Occupied).lastSavedEpochMillis,
        )
    }

    // --- AC#1: play time persists ---

    @Test
    fun `accumulated play time persists and round-trips`() {
        val repo = newRepository()
        repo.saveGameState(SlotId(1), stateOf("alpha", credits = 100L, playTimeSeconds = 12_345L))

        assertEquals("play time survives a reload", 12_345L, newRepository().loadGameState(SlotId(1))?.playTimeSeconds)
        assertEquals(
            "play time is shown on the slot summary",
            12_345L,
            (repo.listSlots()[1] as SaveSlotSummary.Occupied).playTimeSeconds,
        )
    }

    // --- AC#3: the active-slot pointer ---

    @Test
    fun `the active slot defaults to legacy and is settable`() {
        val repo = newRepository()
        assertEquals("a fresh DB resumes the legacy slot", SlotId.LEGACY, repo.activeSlot())

        repo.setActiveSlot(SlotId(3))
        assertEquals("the active slot persists", SlotId(3), newRepository().activeSlot())
    }

    @Test
    fun `an out-of-range active slot degrades to the legacy slot`() {
        val repo = newRepository()
        repo.setActiveSlot(SlotId(SaveSlots.COUNT + 5)) // beyond the configured range
        assertEquals("an out-of-range pointer reads back as the legacy slot", SlotId.LEGACY, repo.activeSlot())
    }

    private fun occupiedName(
        repo: SqlDelightGameStateRepository,
        slot: SlotId,
    ): String = (repo.listSlots()[slot.value] as SaveSlotSummary.Occupied).name

    /** A [Clock] whose instant the test controls, so last-saved stamping is deterministic. */
    private class MutableClock(var now: Long) : Clock {
        override fun nowEpochMillis(): Long = now
    }

    private companion object {
        const val FIRST_SAVE_MILLIS = 1_700_000_000_000L
        const val SECOND_SAVE_MILLIS = 1_700_000_123_456L
    }
}
