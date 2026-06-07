package com.orbitalfrontier.save

import com.orbitalfrontier.settings.Handedness

/**
 * Persistence boundary for player settings (currently just handedness, AC#8).
 *
 * Small, focused interface (ISP). `core` and the UI depend on this abstraction; the SQLDelight
 * implementation is injected. Reads degrade to a sensible default rather than throwing on a
 * missing/corrupt row; writes are transactional and corruption-safe (coding-guidelines §
 * "Error handling").
 */
interface SettingsRepository {
    /**
     * Ensure the save metadata exists (seeds the `save_version` row on first run). Idempotent;
     * safe to call on every launch.
     */
    fun ensureInitialized()

    /** Current handedness, or [Handedness.DEFAULT] if none is stored yet or the row is unreadable. */
    fun loadHandedness(): Handedness

    /**
     * Persist [handedness] atomically. On failure the last good value is left intact and the
     * error is logged; this call does not throw (autosave-style graceful degradation).
     */
    fun saveHandedness(handedness: Handedness)
}
