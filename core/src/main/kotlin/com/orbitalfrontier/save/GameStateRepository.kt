package com.orbitalfrontier.save

import com.orbitalfrontier.world.WorldState

/**
 * Persistence boundary for the full game state — the single-slot autosave (UC04).
 *
 * Small, focused interface (ISP), deliberately separate from [SettingsRepository]: a consumer that
 * only needs the world snapshot does not depend on settings methods and vice-versa. `core` and the
 * autosave controller depend on this abstraction; the SQLDelight implementation is injected (DIP),
 * so the save/load logic is exercised in JVM tests against an in-memory JDBC driver (ADR 0003).
 *
 * Reads degrade to "no save" rather than throwing; writes are transactional and corruption-safe —
 * a failed save leaves the last good save intact and is logged, never thrown (coding-guidelines §
 * "Error handling", UC04 AC#3).
 */
interface GameStateRepository {
    /**
     * The persisted [WorldState], or `null` when there is no save yet (fresh install or a v1 DB
     * migrated to v2 with no game data) — the caller starts a New Game in that case (UC04 AC#5).
     * Never throws; an unreadable/corrupt save is logged and treated as `null`.
     */
    fun loadGameState(): WorldState?

    /**
     * Persist [state] atomically (ship kinematics + save header in ONE transaction, UC04 AC#3). On
     * failure the last good save is left intact and the error is logged; this call does not throw
     * (autosave-style graceful degradation).
     */
    fun saveGameState(state: WorldState)

    /** Whether a save exists (Continue is available). Never throws; an error degrades to `false`. */
    fun hasSave(): Boolean
}
