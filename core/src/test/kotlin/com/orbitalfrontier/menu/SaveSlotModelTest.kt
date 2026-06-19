package com.orbitalfrontier.menu

import com.orbitalfrontier.save.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for [SaveSlotModel] (UC38 AC#2/#5) — the libGDX-free phase machine behind the
 * save/load slot screen. Every transition is exercised without a GL context (ADR 0001): the primary tap
 * in each [SaveSlotModel.Mode], the two confirmation flows (delete, overwrite), cancel, and the
 * [SaveSlotModel.Companion.formatPlayTime] helper that renders the per-slot play time (UC38 AC#1).
 */
class SaveSlotModelTest {
    private val slot = SlotId(2)

    // --- AC#2: LOAD mode — tap resumes an occupied slot, or starts a new game in an empty one ---

    @Test
    fun `LOAD mode on an occupied slot loads it immediately`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.LOAD)

        val action = model.onSelect(slot, occupied = true)

        assertEquals(SaveSlotModel.Action.Load(slot), action)
        assertEquals("a committing tap leaves the model on the list", SaveSlotModel.Phase.LIST, model.phase)
        assertNull("no pending confirmation after an immediate action", model.pendingSlot)
    }

    @Test
    fun `LOAD mode on an empty slot starts a new game into it immediately`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.LOAD)

        val action = model.onSelect(slot, occupied = false)

        assertEquals(SaveSlotModel.Action.NewGameInto(slot), action)
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
    }

    // --- AC#2: SAVE mode — tap writes an empty slot directly, warns before overwriting an occupied one ---

    @Test
    fun `SAVE mode on an empty slot saves immediately`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.SAVE)

        val action = model.onSelect(slot, occupied = false)

        assertEquals(SaveSlotModel.Action.Save(slot), action)
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
    }

    @Test
    fun `SAVE mode on an occupied slot warns before overwriting, then saves on confirm`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.SAVE)

        val first = model.onSelect(slot, occupied = true)
        assertEquals("overwriting an occupied slot must not commit yet", SaveSlotModel.Action.None, first)
        assertEquals(SaveSlotModel.Phase.CONFIRM_OVERWRITE, model.phase)
        assertEquals("the warning remembers which slot it is about", slot, model.pendingSlot)

        val confirmed = model.onConfirm()
        assertEquals(SaveSlotModel.Action.Save(slot), confirmed)
        assertEquals("confirming returns the model to the list", SaveSlotModel.Phase.LIST, model.phase)
        assertNull(model.pendingSlot)
    }

    @Test
    fun `cancelling an overwrite warning leaves the slot untouched`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.SAVE)
        model.onSelect(slot, occupied = true)

        val cancelled = model.onCancel()

        assertEquals(SaveSlotModel.Action.None, cancelled)
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
        assertNull("cancelling clears the pending slot", model.pendingSlot)
    }

    // --- AC#2: delete always passes a confirmation ---

    @Test
    fun `delete requires confirmation, then deletes the slot`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.LOAD)

        val request = model.onDeleteRequest(slot)
        assertEquals("a delete request must not commit yet", SaveSlotModel.Action.None, request)
        assertEquals(SaveSlotModel.Phase.CONFIRM_DELETE, model.phase)
        assertEquals(slot, model.pendingSlot)

        val confirmed = model.onConfirm()
        assertEquals(SaveSlotModel.Action.Delete(slot), confirmed)
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
        assertNull(model.pendingSlot)
    }

    @Test
    fun `cancelling a delete warning leaves the slot untouched`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.LOAD)
        model.onDeleteRequest(slot)

        assertEquals(SaveSlotModel.Action.None, model.onCancel())
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
        assertNull(model.pendingSlot)
    }

    @Test
    fun `confirming while on the list is a no-op`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.SAVE)

        assertEquals(SaveSlotModel.Action.None, model.onConfirm())
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
    }

    @Test
    fun `the model is reusable after a committed action`() {
        val model = SaveSlotModel(SaveSlotModel.Mode.SAVE)
        // Overwrite-confirm one slot…
        model.onSelect(SlotId(1), occupied = true)
        assertEquals(SaveSlotModel.Action.Save(SlotId(1)), model.onConfirm())
        // …then immediately drive another slot, proving the phase/pending state was reset.
        assertEquals(SaveSlotModel.Action.Save(SlotId(3)), model.onSelect(SlotId(3), occupied = false))
        assertEquals(SaveSlotModel.Phase.LIST, model.phase)
    }

    // --- AC#1: play-time formatting ---

    @Test
    fun `formatPlayTime renders compact H_M and M strings`() {
        assertEquals("0m", SaveSlotModel.formatPlayTime(0))
        assertEquals("zero-padded seconds still read as 0m", "0m", SaveSlotModel.formatPlayTime(59))
        assertEquals("1m", SaveSlotModel.formatPlayTime(60))
        assertEquals("rounds down to whole minutes", "1m", SaveSlotModel.formatPlayTime(90))
        assertEquals("59m", SaveSlotModel.formatPlayTime(3599))
        assertEquals("1h 0m", SaveSlotModel.formatPlayTime(3600))
        assertEquals("1h 1m", SaveSlotModel.formatPlayTime(3661))
        assertEquals("two-plus hours", "2h 30m", SaveSlotModel.formatPlayTime(2 * 3600 + 30 * 60))
    }

    @Test
    fun `formatPlayTime treats a negative input as zero`() {
        assertEquals("0m", SaveSlotModel.formatPlayTime(-123))
    }
}
