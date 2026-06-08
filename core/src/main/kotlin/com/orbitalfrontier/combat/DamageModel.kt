package com.orbitalfrontier.combat

/**
 * The RNG-weighted per-section hit-location model (UC13 AC#3/#7) — given a hit lands, *which* section
 * takes the damage. A pure function of `(current damage, max HP, hit damage, weights, rng)` returning
 * the new [SectionDamage], the advanced [CombatRng] and the struck [ShipSection], so it is fully
 * deterministic and JVM-testable.
 *
 * **Determinism (HARD).** The weighted pick is built over a cumulative-weight list whose candidates are
 * ordered by **[ShipSection.name]** (the stable string, **never** the ordinal or `hashCode`), so adding
 * or reordering enum constants can't shift the draw. Only sections the target actually has (positive max
 * HP) and with a positive weight are candidates. The RNG is the functional [CombatRng]; one bounded draw
 * over the total weight selects the section.
 */
object DamageModel {
    /** The outcome of one [applyHit]: the new section damage, the advanced RNG, and the struck section. */
    data class HitResult(
        val sectionDamage: SectionDamage,
        val rng: CombatRng,
        val section: ShipSection,
    )

    /**
     * Apply a [hitDamage] hit to the ship described by [sectionDamage] (current) + [maxSectionHp]
     * (derived), choosing the struck section by [weights] via [rng]. Returns the updated damage, the
     * advanced rng and the section hit. When the target has **no** damageable section (no positive-HP,
     * positive-weight candidate) the hit is wasted: damage and rng are returned **unchanged** and the
     * section defaults to [ShipSection.HULL].
     */
    fun applyHit(
        sectionDamage: SectionDamage,
        maxSectionHp: Map<ShipSection, Int>,
        hitDamage: Int,
        weights: Map<ShipSection, Int>,
        rng: CombatRng,
    ): HitResult {
        // Candidates: sections the target has (maxHp > 0) AND that carry a positive weight, ordered by
        // the stable ShipSection.name string (never ordinal/hashCode) so the cumulative list is stable.
        val candidates =
            ShipSection.entries
                .filter { (maxSectionHp[it] ?: 0) > 0 && (weights[it] ?: 0) > 0 }
                .sortedBy { it.name }

        if (candidates.isEmpty()) {
            return HitResult(sectionDamage, rng, ShipSection.HULL)
        }

        val totalWeight = candidates.sumOf { weights.getValue(it) }
        val (roll, advanced) = rng.nextInt(totalWeight)

        var cumulative = 0
        var chosen = candidates.last()
        for (section in candidates) {
            cumulative += weights.getValue(section)
            if (roll < cumulative) {
                chosen = section
                break
            }
        }

        val updated = SectionDamages.applyDamage(sectionDamage, chosen, hitDamage, maxSectionHp.getValue(chosen))
        return HitResult(updated, advanced, chosen)
    }
}
