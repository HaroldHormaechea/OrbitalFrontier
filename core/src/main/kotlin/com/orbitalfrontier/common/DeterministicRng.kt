package com.orbitalfrontier.common

/**
 * The project's single deterministic random-number **primitive** (UC13; ADR 0011/0012).
 *
 * Every procedural / seeded choice in the game — mission instancing (UC12), combat damage / targeting
 * / enemy AI (UC13) — is derived from an explicit **string-hash → LCG** built ONLY from stable String
 * primitives and pure 64-bit integer arithmetic. No `enum`/data-class/identity `hashCode()`, no
 * `java.util.Random`, no floating point in the state transition, no wall clock: identical seed ⇒
 * identical stream on any JVM, which is exactly what byte-for-byte record/replay (UC02) rests on.
 *
 * This object holds the four stateless primitives that the old `MissionGenerator.MissionRng` carried
 * privately, extracted **byte-for-byte unchanged** so UC12's golden fixtures keep replaying:
 *  - [fnv1a] — FNV-1a 64-bit hash of a String, the deterministic seed source.
 *  - [lcgAdvance] — one step of the 64-bit LCG (Knuth MMIX constants), `state' = state*M + C`.
 *  - [boundedInt] — a uniform `[0, bound)` int drawn from an (already-advanced) state, via the top
 *    31 bits (`state ushr 33`) so the value is always non-negative.
 *  - [floatFromState] — a uniform `[0, 1)` float from the top 24 bits of an (already-advanced) state
 *    (`/ 2^24`), the precision an IEEE-754 `Float` mantissa can represent exactly.
 *
 * Stateful callers (mission instancing) keep a `var state` and call [lcgAdvance] then [boundedInt];
 * functional callers ([com.orbitalfrontier.combat.CombatRng]) thread the state through an immutable
 * value class. Both share these primitives so the two RNG surfaces can never silently diverge.
 */
object DeterministicRng {
    // FNV-1a 64-bit constants (offset basis 0xcbf29ce484222325, prime 0x100000001b3) as signed Longs.
    const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
    const val FNV_PRIME: Long = 1099511628211L

    // Knuth MMIX 64-bit LCG constants.
    const val LCG_MULTIPLIER: Long = 6364136223846793005L
    const val LCG_INCREMENT: Long = 1442695040888963407L

    /**
     * Explicit FNV-1a 64-bit hash of [s] over its UTF-16 code units — a deterministic, well-specified
     * string hash used to seed the LCG. Deliberately NOT [String.hashCode] (and certainly not
     * enum/data-class/identity hashCode), so the seed is self-contained and stable across JVMs and
     * refactors. Long multiplication wraps mod 2^64 (two's complement), which is exactly the FNV
     * mixing step.
     */
    fun fnv1a(s: String): Long {
        var h = FNV_OFFSET_BASIS
        for (c in s) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME
        }
        return h
    }

    /** Advance the 64-bit LCG one step: `state' = state * M + C` (wraps mod 2^64). */
    fun lcgAdvance(state: Long): Long = state * LCG_MULTIPLIER + LCG_INCREMENT

    /**
     * A uniform non-negative int in `[0, bound)` drawn from [state] (typically an already-advanced LCG
     * state). [bound] must be positive. Uses the top 31 bits via a logical shift, which is always
     * non-negative and fits a positive Int, then reduces mod [bound].
     */
    fun boundedInt(
        state: Long,
        bound: Int,
    ): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        val r = (state ushr 33).toInt()
        return r % bound
    }

    /**
     * A uniform `[0, 1)` float drawn from the **top 24 bits** of [state] (typically an already-advanced
     * LCG state), divided by `2^24`. 24 bits is exactly the IEEE-754 single-precision mantissa width,
     * so every representable value is hit without rounding bias and the result is stable across JVMs.
     */
    fun floatFromState(state: Long): Float = (state ushr 40).toInt() / TWO_POW_24

    /** `2^24` as a Float — the divisor giving [floatFromState] its `[0, 1)` range. */
    private const val TWO_POW_24: Float = 16_777_216f
}
