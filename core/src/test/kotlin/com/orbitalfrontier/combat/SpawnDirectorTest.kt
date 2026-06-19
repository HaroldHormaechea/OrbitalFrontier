package com.orbitalfrontier.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [SpawnDirector] / [SpawnScaling] (UC45 AC#3/#4) — the pure progression-driven scaling of
 * an [EncounterZone]'s base composition.
 *
 * The load-bearing contracts:
 *  - [SpawnScaling.None] returns the **same base list instance** for any progression (the byte-identical
 *    proof that every pre-UC45 zone — and every bounty zone — spawns exactly as before);
 *  - [SpawnScaling.ByProgression] appends `min(progression * hostilesPerLevel, maxBonusHostiles)`
 *    reinforcement hostiles, so a more-progressed player meets a larger pack, bounded by the cap;
 *  - scaling is a pure function — identical inputs reproduce an identical (equal) result (AC#4).
 */
class SpawnDirectorTest {
    private val base = listOf(HostileSpawn(HostileArchetypes.RAIDER.id, 1))
    private val byProgression =
        SpawnScaling.ByProgression(
            reinforcementArchetypeId = HostileArchetypes.SCAVENGER.id,
            hostilesPerLevel = 1,
            maxBonusHostiles = 2,
        )

    @Test
    fun `None returns the same base instance unchanged for any progression`() {
        assertSame("None is byte-identical at progression 0", base, SpawnDirector.scale(base, SpawnScaling.None, 0))
        assertSame("None ignores a high progression too", base, SpawnDirector.scale(base, SpawnScaling.None, 99))
    }

    @Test
    fun `ByProgression at progression 0 adds nothing (same base instance)`() {
        // bonus = min(0 * 1, 2) = 0 ⇒ no reinforcement entry ⇒ the base list is returned unchanged.
        assertSame(base, SpawnDirector.scale(base, byProgression, 0))
    }

    @Test
    fun `ByProgression scales the reinforcement count by the progression level`() {
        val scaled = SpawnDirector.scale(base, byProgression, 1)
        assertEquals("base entry preserved plus one reinforcement entry", 2, scaled.size)
        assertEquals("the base composition is unchanged", base[0], scaled[0])
        assertEquals(
            "one reinforcement of the configured archetype",
            HostileSpawn(HostileArchetypes.SCAVENGER.id, 1),
            scaled[1],
        )
    }

    @Test
    fun `ByProgression caps reinforcements at maxBonusHostiles`() {
        // progression 5 * hostilesPerLevel 1 = 5, capped to maxBonusHostiles 2.
        val scaled = SpawnDirector.scale(base, byProgression, 5)
        assertEquals(HostileSpawn(HostileArchetypes.SCAVENGER.id, 2), scaled[1])
        assertEquals("total hostiles = base 1 + capped bonus 2", 3, scaled.sumOf { it.count })
    }

    @Test
    fun `a negative progression is coerced to zero (no reinforcement)`() {
        assertSame(base, SpawnDirector.scale(base, byProgression, -3))
    }

    @Test
    fun `scaling is deterministic for identical inputs`() {
        assertEquals(
            "same inputs reproduce an equal scaled composition (AC#4)",
            SpawnDirector.scale(base, byProgression, 3),
            SpawnDirector.scale(base, byProgression, 3),
        )
    }
}
