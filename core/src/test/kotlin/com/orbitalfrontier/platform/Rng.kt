package com.orbitalfrontier.platform

import java.util.Random

/**
 * Injected randomness port (DIP — coding-guidelines "Dependency Inversion" / determinism).
 *
 * Core game logic that needs randomness depends on this abstraction, never on `Math.random`,
 * `kotlin.random.Random`, or a wall-clock-seeded source. The implementation is injected and the
 * seed is fixed **per playthrough**, which is what makes a recorded playthrough reproducible
 * (UC02 AC#1/#2): identical seed + identical input script ⇒ identical end state.
 *
 * No core sim system consumes RNG yet (the movement model is purely deterministic). This port is
 * established now so every later system (spawn tables, loot rolls, encounter selection) draws from
 * the injected, seeded source from day one rather than retrofitting determinism later.
 *
 * The method surface mirrors [java.util.Random] so [SeededRng] is a thin, JDK-spec-deterministic
 * adapter.
 */
interface Rng {
    /** Uniformly distributed `Int` across the full 32-bit range. */
    fun nextInt(): Int

    /** Uniformly distributed `Int` in `0 until bound`; [bound] must be positive. */
    fun nextInt(bound: Int): Int

    /** Uniformly distributed `Long` across the full 64-bit range. */
    fun nextLong(): Long

    /** Uniformly distributed `Float` in `[0, 1)`. */
    fun nextFloat(): Float

    /** Uniformly distributed `Double` in `[0, 1)`. */
    fun nextDouble(): Double
}

/**
 * [Rng] backed by [java.util.Random], seeded deterministically.
 *
 * `java.util.Random` is specified to produce an identical sequence for a given seed across every
 * conformant JVM (its algorithm is fixed by the JDK spec), so a playthrough recorded with a seed
 * replays bit-identically anywhere. We deliberately use `java.util.Random` rather than
 * `kotlin.random.Random` to keep `core` on plain JDK types and pin the determinism contract to the
 * documented JDK algorithm.
 */
class SeededRng(seed: Long) : Rng {
    private val random = Random(seed)

    override fun nextInt(): Int = random.nextInt()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextLong(): Long = random.nextLong()

    override fun nextFloat(): Float = random.nextFloat()

    override fun nextDouble(): Double = random.nextDouble()
}
