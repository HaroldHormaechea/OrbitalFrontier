package com.orbitalfrontier.combat

/**
 * Picks the player's **weakest hull section** for an opt-in weakest-section-targeting hostile (UC45 AC#2).
 * A pure function of `(current section damage, derived max HP)` — no engine types, no RNG — so it is fully
 * deterministic and JVM-testable, mirroring [DamageModel]'s ordering discipline.
 *
 * **Weakest = lowest *current-HP fraction*** among the sections the ship actually has (positive max HP),
 * tie-broken by **ascending [ShipSection.name]** (the stable string, never the ordinal or `hashCode`) — the
 * same stable convention [DamageModel] uses for its weighted pick, so two equally-damaged sections always
 * resolve to the same deterministic choice. Returns `null` only when the ship has no damageable section
 * (no positive-max-HP section), in which case the caller falls back to the RNG-weighted hit path.
 *
 * The result is stamped onto the projectile at **fire time** ([Projectile.targetSection]); it is not
 * re-evaluated mid-flight (the documented "weakest-at-fire-time" semantic — see ADR 0033).
 */
object WeakestSection {
    /**
     * The section of the ship described by [sectionDamage] (current) + [maxSectionHp] (derived) with the
     * lowest current-HP fraction, tie-broken by ascending [ShipSection.name]; `null` if the ship has no
     * positive-max-HP section.
     */
    fun of(
        sectionDamage: SectionDamage,
        maxSectionHp: Map<ShipSection, Int>,
    ): ShipSection? {
        var best: ShipSection? = null
        var bestFraction = Float.MAX_VALUE
        // Iterate by the stable name order so the strict `<` below yields an ascending-name tie-break.
        for (section in ShipSection.entries.sortedBy { it.name }) {
            val maxHp = maxSectionHp[section] ?: 0
            if (maxHp <= 0) continue
            val fraction = SectionDamages.currentHp(sectionDamage, section, maxHp).toFloat() / maxHp
            if (fraction < bestFraction) {
                bestFraction = fraction
                best = section
            }
        }
        return best
    }
}
