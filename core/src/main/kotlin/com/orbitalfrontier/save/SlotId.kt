package com.orbitalfrontier.save

/**
 * Identifies one save slot (UC38). A small, fixed range of indices `0 until [SaveSlots.COUNT]` — there
 * is **no dynamic allocation**: every slot index always exists, an *empty* one simply has no
 * `game_state` row (see [SaveSlotRepository]). Slot 0 is the legacy single-autosave slot every pre-UC38
 * save is migrated into (UC38 AC#3).
 *
 * A pure, libGDX-free [value class] so it threads through the persistence boundary and the pure slot
 * model with zero allocation and full JVM-testability (ADR 0001). The persistence layer owns the single
 * `Int -> Long` conversion to the SQLite `slot_id` column (slot indices are tiny, so `Int` is ample).
 */
@JvmInline
value class SlotId(val value: Int) {
    init {
        require(value >= 0) { "SlotId must be >= 0: $value" }
    }

    companion object {
        /** The legacy autosave slot — every pre-UC38 save is migrated into this index (UC38 AC#3). */
        val LEGACY: SlotId = SlotId(0)
    }
}

/**
 * Fixed save-slot configuration (UC38). The slot count is a single authored constant — there is no
 * dynamic slot creation/removal, so every index in `0 until [COUNT]` is always a valid [SlotId] that is
 * either empty or occupied.
 */
object SaveSlots {
    /**
     * How many save slots the game exposes. Slot 0 is the legacy autosave; 1..[COUNT]-1 start empty.
     * Five is enough for a few parallel playthroughs without crowding the slot list on a phone. [TUNE]
     */
    const val COUNT: Int = 5

    /** Every slot id, ascending (`0 until [COUNT]`) — the canonical, deterministic slot ordering. */
    val ALL: List<SlotId> = (0 until COUNT).map { SlotId(it) }

    /** Whether [id] is within the configured slot range (`0 until [COUNT]`). */
    fun isValid(id: SlotId): Boolean = id.value in 0 until COUNT
}
