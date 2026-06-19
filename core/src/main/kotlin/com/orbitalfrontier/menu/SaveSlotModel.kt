package com.orbitalfrontier.menu

import com.orbitalfrontier.save.SlotId

/**
 * Pure, libGDX-free state machine for the save/load slot screen (UC38), mirroring [MainMenuModel]'s
 * phase-machine shape.
 *
 * The screen runs in one of two [Mode]s — opened from the main menu to **LOAD** an existing slot (or
 * start a new game into an empty one), or from the in-flight pause overlay to **SAVE** the current game
 * into a slot (UC38 AC#2). The model owns the transition table — when a tap acts immediately vs. when it
 * needs a confirmation (deleting a slot, overwriting an occupied slot) — so the [SaveSlotScreen] view
 * holds no decision logic (SRP) and the whole machine is JVM-unit-testable (ADR 0001, UC38 AC#5).
 *
 * The view drives it by calling [onSelect] (the primary tap on a slot row), [onDeleteRequest] (the row's
 * delete affordance), [onConfirm] / [onCancel] (the confirmation step), and acts on the returned [Action];
 * the actual load / save / delete / new-game happen in the bootstrap wiring (the screen owner), never here.
 */
class SaveSlotModel(
    val mode: Mode,
) {
    /** Whether the screen loads a slot (from the main menu) or saves into one (from the pause overlay). */
    enum class Mode { LOAD, SAVE }

    /** Where the screen is: browsing the list, or one of the two confirmation steps. */
    enum class Phase { LIST, CONFIRM_DELETE, CONFIRM_OVERWRITE }

    /** The transition each input may yield. A committing action names the [SlotId] it targets. */
    sealed interface Action {
        /** Nothing to navigate — the view just redraws for the (possibly changed) phase. */
        data object None : Action

        /** Resume the existing save in [slot] (LOAD mode, occupied slot). */
        data class Load(val slot: SlotId) : Action

        /** Delete [slot]'s save (after the delete confirmation). */
        data class Delete(val slot: SlotId) : Action

        /** Persist the current game into [slot] (SAVE mode — directly for an empty slot, or after the overwrite warning). */
        data class Save(val slot: SlotId) : Action

        /** Start a brand-new game into the empty [slot] (LOAD mode, empty slot). */
        data class NewGameInto(val slot: SlotId) : Action
    }

    /** The current phase; starts on the list and advances into a confirmation when one is required. */
    var phase: Phase = Phase.LIST
        private set

    /** The slot a pending confirmation (delete / overwrite) is about; null on the list. */
    var pendingSlot: SlotId? = null
        private set

    /**
     * Primary tap on a slot row. The action depends on [mode] and whether the slot is [occupied]:
     *  - LOAD + occupied  -> [Action.Load] immediately (resume that save, AC#2).
     *  - LOAD + empty     -> [Action.NewGameInto] immediately (start a new game there, AC#2).
     *  - SAVE + empty     -> [Action.Save] immediately (write into the free slot, AC#2).
     *  - SAVE + occupied  -> enter [Phase.CONFIRM_OVERWRITE] and wait for [onConfirm] (the overwrite
     *    warning UC21 introduced — the slot already holds a save, AC edge case).
     */
    fun onSelect(
        slot: SlotId,
        occupied: Boolean,
    ): Action =
        when (mode) {
            Mode.LOAD -> if (occupied) Action.Load(slot) else Action.NewGameInto(slot)
            Mode.SAVE ->
                if (occupied) {
                    pendingSlot = slot
                    phase = Phase.CONFIRM_OVERWRITE
                    Action.None
                } else {
                    Action.Save(slot)
                }
        }

    /**
     * Player asked to delete an occupied [slot]. Deleting always passes a confirmation (AC#2): enter
     * [Phase.CONFIRM_DELETE] and wait for [onConfirm]. (Only the view's occupied rows offer the affordance.)
     */
    fun onDeleteRequest(slot: SlotId): Action {
        pendingSlot = slot
        phase = Phase.CONFIRM_DELETE
        return Action.None
    }

    /**
     * Player confirmed the pending warning: commit the delete ([Phase.CONFIRM_DELETE]) or the overwrite
     * save ([Phase.CONFIRM_OVERWRITE]), then reset to the list (the view navigates away on a committing
     * action, but the model is left reusable). A confirm on the list is a no-op.
     */
    fun onConfirm(): Action {
        val slot = pendingSlot
        return when (phase) {
            Phase.CONFIRM_DELETE -> {
                reset()
                slot?.let { Action.Delete(it) } ?: Action.None
            }
            Phase.CONFIRM_OVERWRITE -> {
                reset()
                slot?.let { Action.Save(it) } ?: Action.None
            }
            Phase.LIST -> Action.None
        }
    }

    /** Player cancelled a warning: back to the list with no change (the slot is untouched). */
    fun onCancel(): Action {
        reset()
        return Action.None
    }

    private fun reset() {
        phase = Phase.LIST
        pendingSlot = null
    }

    companion object {
        /**
         * Format an accumulated [playTimeSeconds] count as a compact, locale-free "Hh Mm" / "Mm" string for
         * the slot list (UC38 AC#1) — e.g. `0` -> "0m", `90` -> "1m", `3661` -> "1h 1m". Pure (no clock,
         * no locale), so it is JVM-unit-testable. A negative input is treated as 0.
         */
        fun formatPlayTime(playTimeSeconds: Long): String {
            val total = playTimeSeconds.coerceAtLeast(0)
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }
}
