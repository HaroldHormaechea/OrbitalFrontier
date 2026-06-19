package com.orbitalfrontier.save

import com.orbitalfrontier.world.SectorId

/**
 * A read-only summary of one save slot, for the save/load slot list (UC38 AC#1).
 *
 * A `sealed` value (coding-guidelines § O) with exactly two shapes so the UI never has to reason about
 * nullable metadata: an [Occupied] slot carries everything the list row shows (name, last-saved time,
 * and the short state summary — credits, sector, play time); an [Empty] slot carries only its id. The
 * repository returns one of these per slot index, so a "—" / "Empty" row and a populated row are
 * distinct types, not a single struct with half its fields null.
 *
 * Pure (only domain types — [SlotId], [SectorId]) and libGDX-free, so the slot list, the model
 * ([SaveSlotModel]) and the round-trip are fully JVM-unit-testable (ADR 0001, UC38 AC#5).
 */
sealed interface SaveSlotSummary {
    /** Which slot this summarises. */
    val slotId: SlotId

    /** True for [Occupied], false for [Empty] — convenience for the view's enable/disable logic. */
    val hasSave: Boolean

    /**
     * An occupied slot — it has a saved game (a `game_state` row, UC38). Carries the displayable
     * metadata: the player-chosen [name], the wall-clock [lastSavedEpochMillis] the slot was last
     * written (a `0` sentinel means "unknown", rendered as "—"), and the short state summary the row
     * shows — [credits], current [sector], and accumulated [playTimeSeconds].
     */
    data class Occupied(
        override val slotId: SlotId,
        val name: String,
        val lastSavedEpochMillis: Long,
        val credits: Long,
        val sector: SectorId,
        val playTimeSeconds: Long,
    ) : SaveSlotSummary {
        override val hasSave: Boolean get() = true
    }

    /** An empty slot — no saved game yet. "New game into an empty slot" targets one of these (AC#2). */
    data class Empty(
        override val slotId: SlotId,
    ) : SaveSlotSummary {
        override val hasSave: Boolean get() = false
    }
}
