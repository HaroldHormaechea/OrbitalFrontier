package com.orbitalfrontier.save

import com.orbitalfrontier.world.WorldState

/**
 * Persistence boundary for the full game state — now **per save slot** (UC04, generalized by UC38).
 *
 * Small, focused interface (ISP), deliberately separate from [SettingsRepository]: a consumer that
 * only needs the world snapshot does not depend on settings methods and vice-versa. `core` and the
 * autosave controller depend on this abstraction; the SQLDelight implementation is injected (DIP),
 * so the save/load logic is exercised in JVM tests against an in-memory JDBC driver (ADR 0003).
 *
 * **UC38 — save slots.** Where UC04..UC37 were a single autosave, every method now takes a [SlotId]: a
 * save is partitioned by slot in the single DB (ADR 0026), so loading / saving / wiping one slot never
 * touches another (slot isolation, UC38 AC#4). The legacy single autosave is the [SlotId.LEGACY] slot
 * (slot 0). The companion **active-slot** pointer + the slot list / rename / delete live on the separate
 * [SaveSlotRepository] capability (ISP — the autosave controller only needs this interface).
 *
 * Reads degrade to "no save" rather than throwing; writes are transactional and corruption-safe —
 * a failed save leaves the last good save intact and is logged, never thrown (coding-guidelines §
 * "Error handling", UC04 AC#3).
 */
interface GameStateRepository {
    /**
     * The [WorldState] persisted in [slot], or `null` when that slot is empty (no save) — the caller
     * starts a New Game in that case (UC04 AC#5). Never throws; an unreadable/corrupt save is logged
     * and treated as `null`. Scoped to [slot] only (UC38 AC#4).
     */
    fun loadGameState(slot: SlotId): WorldState?

    /**
     * Persist [state] into [slot] atomically (header + every ship/cargo/… row in ONE transaction, UC04
     * AC#3). Stamps the slot's last-saved wall-clock time (via the injected clock) and persists the
     * accumulated play time, but **never overwrites the slot's player-chosen name** (the name-clobber
     * guard, UC38). On failure the last good save is left intact and the error is logged; does not throw.
     */
    fun saveGameState(
        slot: SlotId,
        state: WorldState,
    )

    /** Whether [slot] holds a save (Continue / Load is available). Never throws; an error degrades to `false`. */
    fun hasSave(slot: SlotId): Boolean

    /**
     * Wipe ONE [slot]'s save (UC21 / UC38): remove all durable game-state rows for that slot — the header,
     * the whole fleet, and every per-ship / world / mission / reputation / station row — while keeping
     * `settings` (global preferences) and `meta` (version + active-slot pointer) intact, and **every other
     * slot untouched** (UC38 AC#4). Idempotent: a no-op on an already-empty slot. Runs in one transaction
     * and is corruption-safe — a failure is logged and **never thrown**.
     */
    fun clearSave(slot: SlotId)
}
