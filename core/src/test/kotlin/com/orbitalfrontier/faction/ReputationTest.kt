package com.orbitalfrontier.faction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure per-faction [Reputation] value (UC14 AC#2/#4/#5).
 *
 * Every case is a pure function of its inputs (no engine types, no RNG, no clock), so the whole
 * reputation system is JVM-unit-testable (AC#5). The no-op cases assert **reference equality** of the
 * returned value — the contract the simulation relies on to keep a no-op tick byte-identical and skip
 * an autosave.
 */
class ReputationTest {
    private val league = FactionId("league")
    private val independents = FactionId("independents")

    // Default clamp bounds, matching ReputationParams' defaults.
    private val min = -100
    private val max = 100

    @Test
    fun `valueFor returns neutral 0 for a faction with no recorded standing`() {
        assertEquals("an un-met faction reads back neutral", 0, Reputation.EMPTY.valueFor(league))
        assertEquals(0, Reputation(mapOf(league to 30)).valueFor(independents))
    }

    @Test
    fun `EMPTY stores no rows and reads every faction as neutral`() {
        assertTrue("EMPTY holds no standings", Reputation.EMPTY.byFaction.isEmpty())
        assertEquals(0, Reputation.EMPTY.valueFor(league))
    }

    @Test
    fun `with applies the delta to a faction standing`() {
        val after = Reputation.EMPTY.with(league, 10, min, max)
        assertEquals("the league standing rose by the delta", 10, after.valueFor(league))
        assertEquals("other factions are untouched", 0, after.valueFor(independents))
    }

    @Test
    fun `with accumulates across calls`() {
        val after =
            Reputation.EMPTY
                .with(league, 10, min, max)
                .with(league, 25, min, max)
        assertEquals(35, after.valueFor(league))
    }

    @Test
    fun `with clamps the result to the max bound`() {
        val after = Reputation(mapOf(league to 95)).with(league, 50, min, max)
        assertEquals("the gain is clamped to the allied ceiling", max, after.valueFor(league))
    }

    @Test
    fun `with clamps the result to the min bound`() {
        val after = Reputation(mapOf(league to -95)).with(league, -50, min, max)
        assertEquals("the loss is clamped to the hostile floor", min, after.valueFor(league))
    }

    @Test
    fun `with drops a faction back to neutral, keeping the map canonical`() {
        val gained = Reputation.EMPTY.with(league, 10, min, max)
        val back = gained.with(league, -10, min, max)
        assertEquals("the standing is exactly neutral again", 0, back.valueFor(league))
        assertTrue("a neutral standing is not stored (canonical map)", back.byFaction.isEmpty())
        assertEquals("with(+d).with(-d) round-trips to EMPTY", Reputation.EMPTY, back)
    }

    @Test
    fun `a no-op delta returns the same instance`() {
        val rep = Reputation(mapOf(league to 40))
        val same = rep.with(league, 0, min, max)
        assertSame("a zero delta cannot move the value, so the same instance is returned", rep, same)
    }

    @Test
    fun `a delta that cannot move a pinned value returns the same instance`() {
        val pinned = Reputation(mapOf(league to max))
        val same = pinned.with(league, 25, min, max)
        assertSame("already at the ceiling, a further gain is a no-op (same instance)", pinned, same)
    }

    @Test
    fun `a real change returns a new instance`() {
        val rep = Reputation(mapOf(league to 40))
        val changed = rep.with(league, 5, min, max)
        assertNotSame(rep, changed)
        assertEquals(45, changed.valueFor(league))
    }

    @Test
    fun `with rejects inverted bounds`() {
        try {
            Reputation.EMPTY.with(league, 1, min = 10, max = -10)
            throw AssertionError("expected an IllegalArgumentException for min > max")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
