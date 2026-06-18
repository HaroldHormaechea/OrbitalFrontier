package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.Respawn
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * "Destruction respawn → save → reload" coverage for UC33 AC#4: the respawn state is persisted at the
 * moment of destruction so a crash/close on the consequence screen **does not duplicate or skip the
 * penalty**.
 *
 * Two facets are pinned:
 *  1. **Durable flush** — [AutosaveController.onCriticalEvent] (the trigger [PlayScreen.respawnPlayer]
 *     calls) enqueues a save AND flushes the single-writer executor, so the post-respawn state is durable
 *     before the player is handed the screen (the event-driven analogue of [onPauseOrExit]).
 *  2. **Exactly-once penalty** — a post-respawn [WorldState] (the active ship's hold already lightened by
 *     the floor-25% cargo loss) round-trips through [SqlDelightGameStateRepository] unchanged, and a
 *     second save→reload of the reloaded state is idempotent. The reload restores the *already-lightened*
 *     hold — it never re-runs the penalty (15 units, not 11) and never skips it (15, not the original 20).
 *
 * The pure respawn math is owned by [com.orbitalfrontier.combat.RespawnTest]; the consequence summary by
 * [com.orbitalfrontier.combat.DestructionSummaryTest]; here the focus is the durable persistence contract.
 */
class Uc33DestructionRespawnSaveTest {
    private val params = CombatParams() // respawnCargoLossFraction 0.25
    private val respawnStation = PoiId("alpha-station")

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

    /** A post-respawn world: the starter ship's hold, loaded then lightened by the destruction penalty. */
    private fun postRespawnWorld(): Pair<WorldState, Int> {
        val starter = Fleet.starter()
        // Load 20 units into the starter hold (within capacity), then drive a real destruction respawn.
        val loaded = starter.active.cargo.add(ResourceType.IRON_ORE, 20).cargo
        assertEquals("precondition: the hold carries 20 units before destruction", 20, loaded.usedUnits)
        val result = Respawn.respawn(Vec2.ZERO, loaded, params)
        assertEquals("precondition: floor(20*0.25)=5 units lost", 5, result.unitsLost)

        val postRespawn = starter.withActive(starter.active.copy(cargo = result.cargo, kinematics = result.kinematics))
        val world =
            WorldState(
                fleet = postRespawn,
                lastDockedStation = respawnStation,
                credits = 500L,
            )
        return world to result.cargo.usedUnits // 15 kept
    }

    // --- AC#4 facet 1: the critical event enqueues AND flushes (durable before the screen shows) ----

    @Test
    fun `onCriticalEvent enqueues a save and flushes the executor`() {
        val repo = CapturingRepository()
        val executor = InlineSaveExecutor()
        val controller =
            AutosaveController(
                repository = repo,
                saveExecutor = executor,
                logger = NoOpLogger,
                snapshotSupplier = { postRespawnWorld().first },
            )

        controller.onCriticalEvent("respawn")

        assertEquals("AC#4: a critical respawn event enqueues exactly one save", 1, repo.saved.size)
        assertEquals("AC#4: the critical event flushes so the respawn is durable before the screen shows", 1, executor.flushCount)
    }

    // --- AC#4 facet 2: the post-respawn state round-trips, penalty applied exactly once -------------

    @Test
    fun `the post-respawn state saves and reloads exactly`() {
        val (world, keptUnits) = postRespawnWorld()
        assertEquals("the lightened hold keeps 15 units (20 - 5)", 15, keptUnits)

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(world)

        // Fresh repository over the same DB == app restart.
        val reloaded = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        assertNotNull("the post-respawn save must reload", reloaded)
        assertEquals("AC#4: the post-respawn world reloads exactly", world, reloaded)
        assertEquals("AC#4: the respawn point survives the round-trip", respawnStation, reloaded!!.lastDockedStation)
        assertEquals(
            "AC#4: the lightened hold reloads with the penalty applied exactly once (15, not 20, not 11)",
            15,
            reloaded.fleet.active.cargo.usedUnits,
        )
    }

    @Test
    fun `reloading the post-respawn state is idempotent - the penalty is never re-applied`() {
        val (world, _) = postRespawnWorld()

        val repo = SqlDelightGameStateRepository(database, NoOpLogger)
        repo.saveGameState(world)
        val firstReload = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        // Persist the reloaded state again (simulating a crash/close-and-reload cycle on the screen) and
        // reload once more — the hold must NOT shrink further: the penalty was baked into the save, not
        // re-computed on load.
        SqlDelightGameStateRepository(database, NoOpLogger).saveGameState(firstReload!!)
        val secondReload = SqlDelightGameStateRepository(database, NoOpLogger).loadGameState()

        assertNotNull("the second reload must succeed", secondReload)
        assertEquals("AC#4: a re-save/reload cycle is idempotent (no duplicated penalty)", firstReload, secondReload)
        assertEquals(
            "AC#4: the hold stays at 15 units across reload cycles (penalty applied exactly once)",
            15,
            secondReload!!.fleet.active.cargo.usedUnits,
        )
    }

    /** [SaveExecutor] that runs tasks synchronously and counts flushes (single-thread stand-in). */
    private class InlineSaveExecutor : SaveExecutor {
        var flushCount = 0

        override fun execute(task: () -> Unit) = task()

        override fun flush() {
            flushCount++
        }
    }

    /** Capturing fake repository: records each persisted snapshot so triggers are observable. */
    private class CapturingRepository : GameStateRepository {
        val saved = mutableListOf<WorldState>()

        override fun loadGameState(): WorldState? = saved.lastOrNull()

        override fun saveGameState(state: WorldState) {
            saved += state
        }

        override fun hasSave(): Boolean = saved.isNotEmpty()

        override fun clearSave() {
            saved.clear()
        }
    }
}
