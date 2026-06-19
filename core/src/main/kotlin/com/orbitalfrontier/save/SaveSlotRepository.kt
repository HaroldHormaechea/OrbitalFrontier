package com.orbitalfrontier.save

/**
 * Save-slot management boundary (UC38) — the slot list, rename, delete, and the active-slot pointer.
 *
 * Deliberately separate from [GameStateRepository] (ISP): the autosave controller and the play screen
 * only need to load/save/clear a slot ([GameStateRepository]); the save/load **UI** and the composition
 * root additionally need to enumerate slots, rename / delete them, and read/advance the active-slot
 * pointer that the autosave targets and `Continue` resumes (UC38 AC#2/#3). The SQLDelight implementation
 * realises both interfaces; consumers depend on whichever they need (DIP).
 *
 * Every method is corruption-safe and never throws — a read degrades to an empty list / a safe default
 * and a write is logged on failure, matching [GameStateRepository]'s graceful-degradation contract.
 */
interface SaveSlotRepository {
    /**
     * A summary of **every** slot index in `0 until SaveSlots.COUNT` (UC38 AC#1), in ascending slot order:
     * a [SaveSlotSummary.Occupied] (name, last-saved time, credits, sector, play time) for a slot that
     * holds a save, and a [SaveSlotSummary.Empty] for one that does not. Built from a single header query
     * (no full ship/cargo load). Never throws; a read error degrades to all-empty.
     */
    fun listSlots(): List<SaveSlotSummary>

    /**
     * Delete [slot]'s save entirely (UC38 AC#2) — the same per-slot wipe as [GameStateRepository.clearSave],
     * surfaced under the slot-management capability so the save UI can remove a slot. Other slots are
     * untouched (UC38 AC#4). Idempotent; never throws.
     */
    fun deleteSlot(slot: SlotId)

    /**
     * Rename [slot] to [name] (UC38 AC#2). Targeted single-column write — never touches gameplay state.
     * A no-op for an empty slot (nothing to rename). Never throws.
     */
    fun renameSlot(
        slot: SlotId,
        name: String,
    )

    /**
     * The slot the autosave currently writes to and `Continue` resumes (UC38 AC#3). Defaults to
     * [SlotId.LEGACY] (slot 0) — the legacy autosave — on a fresh / migrated DB. Never throws; a read
     * error degrades to [SlotId.LEGACY].
     */
    fun activeSlot(): SlotId

    /**
     * Point the autosave / `Continue` at [slot] (UC38 AC#3) — set on new-game-into-slot and load-slot so
     * the autosave and the resumed slot stay coherent. Never throws.
     */
    fun setActiveSlot(slot: SlotId)
}
