package com.orbitalfrontier.save

import com.orbitalfrontier.platform.NoOpLogger
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.world.WorldState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Timing/trigger tests for [AutosaveController] (UC04 AC#2): it decides *when* to autosave and
 * drives the single-writer [SaveExecutor]. Exercised with an inline (run-immediately) executor and
 * a capturing fake [GameStateRepository], so each enqueue is observable as a recorded save.
 *
 * The controller owns timing only (SRP); the actual write lives in the repository (covered by
 * [SqlDelightGameStateRepositoryTest]). These tests assert the three triggers — discrete events,
 * the periodic interval, and pause/exit (which also flushes) — plus the accumulator reset on
 * resume.
 */
class AutosaveControllerTest {
    private val interval = 20f

    private fun controller(
        repo: GameStateRepository,
        executor: SaveExecutor,
        snapshot: WorldState = WorldState(),
        slotSupplier: () -> SlotId = { SlotId.LEGACY },
    ) = AutosaveController(
        repository = repo,
        saveExecutor = executor,
        logger = NoOpLogger,
        snapshotSupplier = { snapshot },
        slotSupplier = slotSupplier,
        intervalSeconds = interval,
    )

    // --- AC#2: discrete-event trigger ---

    @Test
    fun `onEvent enqueues exactly one write`() {
        val repo = FakeGameStateRepository()
        val executor = InlineSaveExecutor()
        val controller = controller(repo, executor)

        controller.onEvent("jump")

        assertEquals("a discrete event should enqueue one save", 1, repo.saved.size)
    }

    // --- AC#2: periodic trigger at the interval, and not before ---

    @Test
    fun `update enqueues a periodic save only once the interval has elapsed`() {
        val repo = FakeGameStateRepository()
        val controller = controller(repo, InlineSaveExecutor())

        controller.update(5f)
        controller.update(5f)
        controller.update(5f)
        assertEquals("no periodic save before the interval elapses", 0, repo.saved.size)

        controller.update(5f) // accumulated 20s == interval
        assertEquals("a periodic save fires once the interval is reached", 1, repo.saved.size)
    }

    @Test
    fun `the periodic accumulator resets after each periodic save`() {
        val repo = FakeGameStateRepository()
        val controller = controller(repo, InlineSaveExecutor())

        controller.update(20f) // 1st periodic save, accumulator resets
        controller.update(19f) // not yet
        assertEquals(1, repo.saved.size)
        controller.update(1f) // accumulated 20s again
        assertEquals("a second full interval triggers a second periodic save", 2, repo.saved.size)
    }

    // --- AC#2: pause/exit trigger — enqueues AND flushes ---

    @Test
    fun `onPauseOrExit enqueues a final save and flushes the executor`() {
        val repo = FakeGameStateRepository()
        val executor = InlineSaveExecutor()
        val controller = controller(repo, executor)

        controller.onPauseOrExit()

        assertEquals("pause/exit should enqueue one final save", 1, repo.saved.size)
        assertEquals("pause/exit must flush so the final save is durable", 1, executor.flushCount)
    }

    @Test
    fun `the accumulator resets on resume so flight does not immediately re-save`() {
        val repo = FakeGameStateRepository()
        val controller = controller(repo, InlineSaveExecutor())

        // Build up almost a full interval, then pause/exit (which resets the accumulator)...
        controller.update(19f)
        controller.onPauseOrExit()
        assertEquals("only the pause/exit save so far", 1, repo.saved.size)

        // ...on resume the periodic clock starts fresh: 19s is not enough to trigger again.
        controller.update(19f)
        assertEquals("resume restarts the interval; no immediate periodic save", 1, repo.saved.size)
        controller.update(1f)
        assertEquals("a fresh full interval after resume triggers the next periodic save", 2, repo.saved.size)
    }

    /** [SaveExecutor] that runs tasks synchronously and counts flushes (single-thread stand-in). */
    private class InlineSaveExecutor : SaveExecutor {
        var flushCount = 0

        override fun execute(task: () -> Unit) = task()

        override fun flush() {
            flushCount++
        }
    }

    // --- UC38: the autosave targets the slot the slotSupplier names, snapshotted at enqueue time ---

    @Test
    fun `the autosave writes to the slot supplied by the slotSupplier`() {
        val repo = FakeGameStateRepository()
        val controller = controller(repo, InlineSaveExecutor(), slotSupplier = { SlotId(3) })

        controller.onEvent("jump")

        assertEquals("the save must target the supplied slot", listOf(SlotId(3)), repo.savedSlots)
    }

    @Test
    fun `the active slot is read per save, so a later slot change retargets the next autosave`() {
        val repo = FakeGameStateRepository()
        // A mutable active slot the supplier reads live (mirrors the app's active-slot field, UC38 AC#3).
        var activeSlot = SlotId.LEGACY
        val controller = controller(repo, InlineSaveExecutor(), slotSupplier = { activeSlot })

        controller.onEvent("first") // targets the legacy slot
        activeSlot = SlotId(2) // the player loaded / saved into another slot
        controller.onEvent("second") // the next autosave follows the new active slot

        assertEquals(
            "each enqueue snapshots the live active slot",
            listOf(SlotId.LEGACY, SlotId(2)),
            repo.savedSlots,
        )
    }

    /**
     * Capturing fake repository: records each persisted snapshot (and the [SlotId] it was written to, UC38)
     * so triggers — and the slot the autosave targeted — are observable.
     */
    private class FakeGameStateRepository : GameStateRepository {
        val saved = mutableListOf<WorldState>()
        val savedSlots = mutableListOf<SlotId>()

        override fun loadGameState(slot: SlotId): WorldState? = saved.lastOrNull()

        override fun saveGameState(
            slot: SlotId,
            state: WorldState,
        ) {
            saved += state
            savedSlots += slot
        }

        override fun hasSave(slot: SlotId): Boolean = saved.isNotEmpty()

        override fun clearSave(slot: SlotId) {
            saved.clear()
        }
    }
}
