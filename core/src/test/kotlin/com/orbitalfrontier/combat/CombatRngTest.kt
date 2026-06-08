package com.orbitalfrontier.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CombatRng] (UC13 AC#7) — the combat model's **functional** seeded RNG.
 *
 * Pins the determinism contract every replay relies on: a draw returns `(value, nextRng)` and never
 * mutates in place, the same seed yields the same stream, and a draw stays in bounds. Seeding is keyed
 * by a string (the `"encounter:$zone:$tick"` convention), so the same encounter replays bit-for-bit.
 */
class CombatRngTest {
    @Test
    fun `the same seed produces the same stream (functional, value-based)`() {
        val a = CombatRng.seeded("encounter:alpha-raider-picket:0")
        val b = CombatRng.seeded("encounter:alpha-raider-picket:0")

        // Value-class equality: same seed == same state.
        assertEquals(a, b)

        // Drawing the same sequence of operations off both yields identical values AND identical next-states.
        var ra = a
        var rb = b
        repeat(50) {
            val (va, na) = ra.nextInt(11)
            val (vb, nb) = rb.nextInt(11)
            assertEquals("draw $it value matches", va, vb)
            assertEquals("draw $it advanced state matches", na, nb)
            ra = na
            rb = nb
        }
    }

    @Test
    fun `different seed keys diverge`() {
        val a = CombatRng.seeded("encounter:alpha-raider-picket:0")
        val b = CombatRng.seeded("encounter:alpha-raider-picket:1")
        assertNotEquals("a different spawnTick seeds a different stream", a, b)
    }

    @Test
    fun `a draw is pure - it returns the advanced rng and does not mutate the receiver`() {
        val start = CombatRng.seeded("k")
        val (v1, next) = start.nextInt(7)

        // Re-drawing off the ORIGINAL value yields the same first value (no hidden mutation).
        val (v1Again, _) = start.nextInt(7)
        assertEquals("the receiver is unchanged by a draw", v1, v1Again)

        // The advanced rng is a different state, and continues the stream.
        assertNotEquals("the draw advanced the state", start, next)
    }

    @Test
    fun `nextInt stays within bounds`() {
        var rng = CombatRng.seeded("bounds")
        repeat(1000) {
            val (v, n) = rng.nextInt(11)
            assertTrue("draw $v in [0,11)", v in 0 until 11)
            rng = n
        }
    }

    @Test
    fun `nextIntInRange stays within the inclusive range and rejects an empty range`() {
        var rng = CombatRng.seeded("range")
        repeat(1000) {
            val (v, n) = rng.nextIntInRange(3, 9)
            assertTrue("draw $v in 3..9", v in 3..9)
            rng = n
        }
        try {
            rng.nextIntInRange(5, 4)
            throw AssertionError("expected an empty-range rejection")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `nextFloat stays in 0 until 1 and is deterministic`() {
        val seed = CombatRng.seeded("float")
        val (f1, _) = seed.nextFloat()
        val (f2, _) = CombatRng.seeded("float").nextFloat()
        assertEquals("same seed, same float", f1, f2, 0f)
        assertTrue("float in [0,1)", f1 >= 0f && f1 < 1f)
    }
}
