package com.orbitalfrontier.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DamageModel] (UC13 AC#3/#7) — the RNG-weighted per-section hit-location model.
 *
 * Pins: the weighted pick is deterministic given a seed, keyed by [ShipSection.name] (not ordinal /
 * map-iteration order), only positive-HP + positive-weight sections are candidates, a single candidate
 * forces that section, and a hit with no candidate is a no-op that returns the SAME instances.
 */
class DamageModelTest {
    private val maxHp =
        mapOf(
            ShipSection.HULL to 30,
            ShipSection.ENGINE to 15,
            ShipSection.TURRET to 10,
            ShipSection.WEAPON to 10,
        )

    @Test
    fun `applyHit is deterministic given the same seed`() {
        val weights = mapOf(ShipSection.HULL to 5, ShipSection.ENGINE to 2, ShipSection.TURRET to 2, ShipSection.WEAPON to 2)
        val rng = CombatRng.seeded("dmg")
        val a = DamageModel.applyHit(SectionDamages.PRISTINE, maxHp, hitDamage = 4, weights = weights, rng = rng)
        val b = DamageModel.applyHit(SectionDamages.PRISTINE, maxHp, hitDamage = 4, weights = weights, rng = rng)

        assertEquals("same seed picks the same section", a.section, b.section)
        assertEquals("same seed yields the same damage map", a.sectionDamage, b.sectionDamage)
        assertEquals("same seed advances the rng identically", a.rng, b.rng)
        assertNotEquals("a hit advances the rng", rng, a.rng)
    }

    @Test
    fun `the pick is keyed by section name, not map insertion order`() {
        // Two weight maps with the SAME weights but DIFFERENT insertion order. Because the cumulative list
        // is ordered by ShipSection.name, the chosen section must be identical for the same roll.
        val ordered = linkedMapOf(ShipSection.HULL to 1, ShipSection.WEAPON to 1)
        val reversed = linkedMapOf(ShipSection.WEAPON to 1, ShipSection.HULL to 1)
        val rng = CombatRng.seeded("name-key")

        val a = DamageModel.applyHit(SectionDamages.PRISTINE, maxHp, hitDamage = 3, weights = ordered, rng = rng)
        val b = DamageModel.applyHit(SectionDamages.PRISTINE, maxHp, hitDamage = 3, weights = reversed, rng = rng)
        assertEquals("map insertion order must not change the chosen section", a.section, b.section)
    }

    @Test
    fun `a single-candidate weight set always strikes that section`() {
        val weights = mapOf(ShipSection.ENGINE to 1)
        var rng = CombatRng.seeded("single")
        repeat(50) {
            val hit = DamageModel.applyHit(SectionDamages.PRISTINE, maxHp, hitDamage = 5, weights = weights, rng = rng)
            assertEquals("only ENGINE is a candidate", ShipSection.ENGINE, hit.section)
            assertEquals("ENGINE drops by the hit damage", 10, SectionDamages.currentHp(hit.sectionDamage, ShipSection.ENGINE, 15))
            rng = hit.rng
        }
    }

    @Test
    fun `a section with no max HP is never chosen`() {
        // TURRET absent from maxHp (0) -> not a candidate even though it carries weight.
        val partialMax = mapOf(ShipSection.HULL to 30)
        val weights = mapOf(ShipSection.HULL to 1, ShipSection.TURRET to 50)
        var rng = CombatRng.seeded("no-hp")
        repeat(50) {
            val hit = DamageModel.applyHit(SectionDamages.PRISTINE, partialMax, hitDamage = 2, weights = weights, rng = rng)
            assertEquals("a zero-HP section is not a candidate; HULL takes every hit", ShipSection.HULL, hit.section)
            rng = hit.rng
        }
    }

    @Test
    fun `a hit with no damageable candidate is a no-op returning the same instances`() {
        val damage = mapOf(ShipSection.HULL to 7)
        val rng = CombatRng.seeded("wasted")
        // No candidate: every weight is zero.
        val zeroWeights = mapOf(ShipSection.HULL to 0, ShipSection.ENGINE to 0, ShipSection.TURRET to 0, ShipSection.WEAPON to 0)
        val hit = DamageModel.applyHit(damage, maxHp, hitDamage = 5, weights = zeroWeights, rng = rng)

        assertSame("damage map is returned unchanged (same instance)", damage, hit.sectionDamage)
        assertEquals("rng is not advanced on a wasted hit", rng, hit.rng)
        assertEquals("section defaults to HULL on a wasted hit", ShipSection.HULL, hit.section)
    }

    @Test
    fun `over many draws a two-section weight set covers both sections`() {
        val weights = mapOf(ShipSection.HULL to 1, ShipSection.ENGINE to 1)
        var rng = CombatRng.seeded("coverage")
        var hull = 0
        var engine = 0
        repeat(400) {
            val hit = DamageModel.applyHit(SectionDamages.PRISTINE, maxHp, hitDamage = 1, weights = weights, rng = rng)
            when (hit.section) {
                ShipSection.HULL -> hull++
                ShipSection.ENGINE -> engine++
                else -> throw AssertionError("only HULL/ENGINE are candidates, got ${hit.section}")
            }
            rng = hit.rng
        }
        assertTrue("HULL is chosen sometimes", hull > 0)
        assertTrue("ENGINE is chosen sometimes", engine > 0)
    }
}
