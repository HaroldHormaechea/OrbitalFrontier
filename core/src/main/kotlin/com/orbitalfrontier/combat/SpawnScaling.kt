package com.orbitalfrontier.combat

/**
 * The per-zone difficulty-scaling policy applied by the [SpawnDirector] (UC45 AC#3). A `sealed` hierarchy
 * (coding-guidelines § O) of pure value data so the spawn director stays branch-data-driven, deterministic
 * and JVM-testable.
 *
 * **[None] is the byte-identical default** carried by every pre-UC45 [EncounterZone] (and every bounty
 * zone): the director returns the base composition unchanged regardless of the progression input, so no
 * existing encounter is ever perturbed. Only a zone that opts into [ByProgression] scales.
 */
sealed interface SpawnScaling {
    /** No scaling — the base composition is spawned as-authored, whatever the player's progression. */
    data object None : SpawnScaling

    /**
     * Scale the encounter by the player's progression level (UC45 AC#3). For a progression level `p`,
     * [SpawnDirector] appends `min(p * hostilesPerLevel, maxBonusHostiles)` extra hostiles of
     * [reinforcementArchetypeId] to the base composition — so a more-progressed player meets a larger
     * pack. All numbers are `[TUNE]` placeholders.
     */
    data class ByProgression(
        /** Archetype the scaled-in reinforcement hostiles use (must be a catalogued, **unaligned** id). */
        val reinforcementArchetypeId: HostileArchetypeId,
        /** Extra hostiles added per progression level. [TUNE] */
        val hostilesPerLevel: Int = 1,
        /** Hard cap on scaled-in reinforcements so a maxed player can't face an unbounded swarm. [TUNE] */
        val maxBonusHostiles: Int = 2,
    ) : SpawnScaling {
        init {
            require(hostilesPerLevel >= 0) { "hostilesPerLevel must not be negative: $hostilesPerLevel" }
            require(maxBonusHostiles >= 0) { "maxBonusHostiles must not be negative: $maxBonusHostiles" }
        }
    }
}
