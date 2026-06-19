package com.orbitalfrontier.faction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation tests for the authored [ReputationParams] tunables (UC14 / UC43).
 *
 * The `init` invariants are the fail-fast guard against an authoring mistake silently shipping a
 * nonsensical balance (a positive "loss", inverted clamp bounds). UC43 adds [ReputationParams.combatKillDelta],
 * which — like [ReputationParams.courierFailDelta] — must be a loss (`<= 0`); these cases pin that contract.
 */
class ReputationParamsTest {
    @Test
    fun `the defaults are self-consistent (a combat kill is a non-positive loss within the clamp)`() {
        val p = ReputationParams()
        assertEquals("the default combat-kill delta is the documented loss", -5, p.combatKillDelta)
        assertTrue("a combat kill is a loss (<= 0)", p.combatKillDelta <= 0)
        assertTrue("the default min <= max", p.min <= p.max)
    }

    @Test
    fun `combatKillDelta of zero is allowed (a faction that tolerates kills)`() {
        // Zero is the boundary of the `<= 0` invariant — a valid "no reputation effect" tuning.
        val p = ReputationParams(combatKillDelta = 0)
        assertEquals(0, p.combatKillDelta)
    }

    @Test
    fun `a positive combatKillDelta is rejected (a kill cannot RAISE standing)`() {
        try {
            ReputationParams(combatKillDelta = 5)
            throw AssertionError("expected an IllegalArgumentException for a positive combatKillDelta")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the message names the offending field",
                expected.message?.contains("combatKillDelta") == true,
            )
        }
    }

    @Test
    fun `a negative combatKillDelta is accepted (the normal loss case)`() {
        val p = ReputationParams(combatKillDelta = -25)
        assertEquals(-25, p.combatKillDelta)
    }
}
