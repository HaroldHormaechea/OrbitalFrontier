package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure combat→reputation resolver [CombatReputation] (UC43).
 *
 * Every case is a pure function of its inputs (no engine types, no RNG, no clock), so the whole
 * combat-reputation seam is JVM-unit-testable and replay-stable (UC43 AC#1/#5). The no-op cases assert
 * **reference equality** of the returned reputation — the lockstep contract the device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the headless replay mirror
 * ([com.orbitalfrontier.sim.Simulation]) rely on to keep an unaligned-only tick byte-identical (so the
 * pre-UC43 fixtures never drift).
 */
class CombatReputationTest {
    private val params = ReputationParams()
    private val independents = Factions.INDEPENDENTS.id

    /** A destroyed hostile of [archetypeId] — the only field [CombatReputation] reads is the archetype. */
    private fun kill(
        idValue: Long,
        archetypeId: HostileArchetypeId,
    ): DestroyedHostile = DestroyedHostile(HostileId(idValue), archetypeId, Vec2(0f, 0f))

    // --- AC#1: a faction-affiliated kill applies the delta to that faction via the Reputation.with seam --

    @Test
    fun `destroying a faction ship sours the player's standing by the combat-kill delta`() {
        val destroyed = listOf(kill(0, HostileArchetypes.INDEPENDENT_MARAUDER.id))

        val result = CombatReputation.applyKills(destroyed, Reputation.EMPTY, params)

        assertTrue("a faction kill is reported as a change", result.changed)
        // Derived from the params (no magic literal): the standing dropped by exactly combatKillDelta.
        assertEquals(
            "Independents standing dropped by combatKillDelta",
            params.combatKillDelta,
            result.reputation.valueFor(independents),
        )
        assertEquals("the reported delta is exactly the applied (post-clamp) change", params.combatKillDelta, result.deltas[independents])
        assertEquals("only the affiliated faction moved (single-faction MVP, AC#2)", setOf(independents), result.deltas.keys)
    }

    @Test
    fun `the marauder is the only authored archetype that moves reputation`() {
        // Defends the AC#1 attribution model: only INDEPENDENT_MARAUDER carries a non-null factionId.
        val affiliated = HostileArchetypes.all.filter { it.factionId != null }
        assertEquals("exactly one archetype is faction-affiliated", listOf(HostileArchetypes.INDEPENDENT_MARAUDER), affiliated)
        assertEquals("and it flies the Independents' colours", independents, HostileArchetypes.INDEPENDENT_MARAUDER.factionId)
    }

    // --- Neutral pitfall: an unaligned / unknown kill has no faction effect (same instance) -------------

    @Test
    fun `destroying an unaligned raider has no reputation effect`() {
        val destroyed = listOf(kill(0, HostileArchetypes.RAIDER.id))

        val result = CombatReputation.applyKills(destroyed, Reputation.EMPTY, params)

        assertFalse("an unaligned kill reports no change", result.changed)
        assertTrue("no faction moved", result.deltas.isEmpty())
        assertSame(
            "the SAME reputation instance is returned (byte-identical, pre-UC43 fixtures unperturbed)",
            Reputation.EMPTY,
            result.reputation,
        )
    }

    @Test
    fun `destroying an unaligned scavenger has no reputation effect`() {
        val result = CombatReputation.applyKills(listOf(kill(0, HostileArchetypes.SCAVENGER.id)), Reputation.EMPTY, params)
        assertFalse(result.changed)
        assertSame(Reputation.EMPTY, result.reputation)
    }

    @Test
    fun `an unknown archetype id is skipped (graceful degradation), no reputation effect`() {
        val result = CombatReputation.applyKills(listOf(kill(0, HostileArchetypeId("ghost-archetype"))), Reputation.EMPTY, params)
        assertFalse("an uncatalogued archetype cannot move any standing", result.changed)
        assertSame(Reputation.EMPTY, result.reputation)
    }

    @Test
    fun `a mixed tick credits only the faction-affiliated kill`() {
        val destroyed =
            listOf(
                kill(0, HostileArchetypes.RAIDER.id),
                kill(1, HostileArchetypes.INDEPENDENT_MARAUDER.id),
                kill(2, HostileArchetypes.SCAVENGER.id),
            )

        val result = CombatReputation.applyKills(destroyed, Reputation.EMPTY, params)

        assertEquals("only the marauder moved Independents", params.combatKillDelta, result.reputation.valueFor(independents))
        assertEquals("exactly one faction moved", setOf(independents), result.deltas.keys)
    }

    // --- Floor clamp: the loss is clamped at min; a standing already pinned at the floor cannot move -----

    @Test
    fun `a faction kill clamps the standing at the hostile floor`() {
        // Start one delta-step above the floor so a single kill lands exactly on min, then a further kill
        // is a no-op (already pinned). Derived from params, no literal.
        val justAboveFloor = Reputation.EMPTY.with(independents, params.min - params.combatKillDelta, params.min, params.max)

        val atFloor = CombatReputation.applyKills(listOf(kill(0, HostileArchetypes.INDEPENDENT_MARAUDER.id)), justAboveFloor, params)
        assertEquals("the kill clamps the standing to the floor", params.min, atFloor.reputation.valueFor(independents))

        val pinned = CombatReputation.applyKills(listOf(kill(1, HostileArchetypes.INDEPENDENT_MARAUDER.id)), atFloor.reputation, params)
        assertFalse("a kill against a floor-pinned faction reports no change", pinned.changed)
        assertSame("a floor-pinned standing returns the same instance", atFloor.reputation, pinned.reputation)
    }

    @Test
    fun `the reported delta is the actual post-clamp change, not the nominal delta`() {
        // One step from the floor: only part of the nominal combatKillDelta can land before the clamp.
        val oneAboveFloor = Reputation.EMPTY.with(independents, params.min + 1, params.min, params.max)
        val result = CombatReputation.applyKills(listOf(kill(0, HostileArchetypes.INDEPENDENT_MARAUDER.id)), oneAboveFloor, params)
        assertEquals("the standing is at the floor", params.min, result.reputation.valueFor(independents))
        assertEquals("the reported delta is the ACTUAL applied change (-1), not the nominal -5", -1, result.deltas[independents])
    }

    // --- Same-instance no-op when nothing was destroyed / no faction kill -------------------------------

    @Test
    fun `an empty kill list is a same-instance no-op`() {
        val rep = Reputation(mapOf(independents to 20))
        val result = CombatReputation.applyKills(emptyList(), rep, params)
        assertFalse(result.changed)
        assertSame("no kills means the same reputation instance back", rep, result.reputation)
        assertTrue(result.deltas.isEmpty())
    }

    // --- Accumulation: two affiliated kills in one tick stack the delta ---------------------------------

    @Test
    fun `two faction kills in one tick stack the delta and report the summed change`() {
        val destroyed =
            listOf(
                kill(0, HostileArchetypes.INDEPENDENT_MARAUDER.id),
                kill(1, HostileArchetypes.INDEPENDENT_MARAUDER.id),
            )

        val result = CombatReputation.applyKills(destroyed, Reputation.EMPTY, params)

        assertEquals("two kills stack the per-kill delta", params.combatKillDelta * 2, result.reputation.valueFor(independents))
        assertEquals("the reported delta sums both kills", params.combatKillDelta * 2, result.deltas[independents])
    }
}
