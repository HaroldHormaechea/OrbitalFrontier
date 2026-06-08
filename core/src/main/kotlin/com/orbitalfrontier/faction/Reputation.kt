package com.orbitalfrontier.faction

/**
 * The player's per-faction reputation (UC14 AC#2) — a pure, immutable value over a
 * `Map<FactionId, Int>`. Like the player's `credits`, reputation is **save-wide** (not per-ship): it is
 * one player-level standing with each faction, carried on [com.orbitalfrontier.world.WorldState].
 *
 * **Neutral is the default.** A faction the player has never interacted with has reputation 0 — so the
 * map stores **only non-neutral** standings ([valueFor] returns 0 for an absent faction). This keeps a
 * fresh game's reputation [EMPTY] (byte-identical to pre-UC14 saves, which had no reputation at all) and
 * the persisted snapshot compact (the repository stores only the non-zero rows, like cargo / field
 * depletion).
 *
 * Every mutation returns a **new** [Reputation] (nothing mutates in place), so it composes into the
 * immutable world snapshot the autosave thread reads and stays fully JVM-testable (UC14 AC#5). The
 * clamp range is supplied by the caller from [ReputationParams] (`min`..`max`) rather than baked in, so
 * tuning the bounds is data, not code.
 */
data class Reputation(
    val byFaction: Map<FactionId, Int> = emptyMap(),
) {
    /** This faction's standing, or 0 (neutral) when the player has no recorded standing with it. */
    fun valueFor(faction: FactionId): Int = byFaction[faction] ?: 0

    /**
     * Apply [delta] to [faction]'s standing, clamping the result into `[min, max]` (UC14 AC#4). Returns
     * **this same instance unchanged** when the clamped result equals the current value (a no-op delta,
     * or a delta that can't move a value already pinned at a bound) — so a caller can cheaply detect
     * "nothing changed" with a reference check and the no-op step stays byte-identical. A result that
     * lands back on neutral (0) drops the faction's entry, keeping the map canonical (only non-neutral
     * standings stored), so `with(f, +1).with(f, -1)` round-trips to exactly [EMPTY].
     */
    fun with(
        faction: FactionId,
        delta: Int,
        min: Int,
        max: Int,
    ): Reputation {
        require(min <= max) { "reputation bounds invalid: $min..$max" }
        val current = valueFor(faction)
        val next = (current + delta).coerceIn(min, max)
        if (next == current) return this
        val updated = LinkedHashMap(byFaction)
        if (next == 0) {
            updated.remove(faction)
        } else {
            updated[faction] = next
        }
        return Reputation(updated)
    }

    companion object {
        /** No recorded standing with any faction — every faction reads back neutral (0). The default. */
        val EMPTY: Reputation = Reputation(emptyMap())
    }
}
