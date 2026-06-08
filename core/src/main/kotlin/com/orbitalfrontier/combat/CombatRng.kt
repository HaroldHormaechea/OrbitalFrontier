package com.orbitalfrontier.combat

import com.orbitalfrontier.common.DeterministicRng

/**
 * The combat model's **functional** seeded RNG (UC13 AC#7; ADR 0012) — a `@JvmInline value class` over
 * a single `Long` LCG state, threaded immutably through [CombatState.rngState].
 *
 * Where the mission generator keeps a private `var state`, combat must stay a **pure value**: every
 * draw returns a `(value, nextCombatRng)` pair so the whole [CombatState] is an immutable snapshot a
 * record/replay round-trip can compare by structural equality. The state advances via the shared
 * [DeterministicRng] primitive, so combat and mission RNG can never silently diverge.
 *
 * Seed it from **replayed state only** — never the wall clock — e.g.
 * `CombatRng.seeded("encounter:${zoneId}:${spawnTick}")`, so the same encounter replays bit-for-bit.
 */
@JvmInline
value class CombatRng(val state: Long) {
    /**
     * Draw a uniform int in `[0, bound)` and the advanced RNG. [bound] must be positive. The state is
     * advanced once (MMIX LCG) and the bounded value taken from the **new** state, matching the mission
     * generator's `advance-then-draw` order.
     */
    fun nextInt(bound: Int): Pair<Int, CombatRng> {
        val advanced = DeterministicRng.lcgAdvance(state)
        return DeterministicRng.boundedInt(advanced, bound) to CombatRng(advanced)
    }

    /**
     * Draw a uniform int in the inclusive range `[minInclusive, maxInclusive]` and the advanced RNG.
     */
    fun nextIntInRange(
        minInclusive: Int,
        maxInclusive: Int,
    ): Pair<Int, CombatRng> {
        require(maxInclusive >= minInclusive) { "empty range: $minInclusive..$maxInclusive" }
        val (raw, next) = nextInt(maxInclusive - minInclusive + 1)
        return (minInclusive + raw) to next
    }

    /**
     * Draw a uniform float in `[0, 1)` (top 24 bits of the advanced state / 2^24) and the advanced RNG.
     */
    fun nextFloat(): Pair<Float, CombatRng> {
        val advanced = DeterministicRng.lcgAdvance(state)
        return DeterministicRng.floatFromState(advanced) to CombatRng(advanced)
    }

    companion object {
        /** A [CombatRng] seeded by the FNV-1a hash of [key] — the only sanctioned seeding path. */
        fun seeded(key: String): CombatRng = CombatRng(DeterministicRng.fnv1a(key))
    }
}
