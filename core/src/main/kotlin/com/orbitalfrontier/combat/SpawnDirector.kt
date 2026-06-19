package com.orbitalfrontier.combat

/**
 * The **spawn director** (UC45 AC#3): scales an [EncounterZone]'s base hostile composition by the player's
 * progression, per the zone's [SpawnScaling] policy. A pure function — no RNG, no engine types, no wall
 * clock — so the device and the replay harness compute the identical scaled composition (AC#4), which the
 * [EncounterSpawner] then realizes into hostiles with its existing one-`nextFloat`-per-hostile draw order.
 *
 * **Backward-compat guarantee.** [SpawnScaling.None] returns the **same** base list instance unchanged for
 * any progression value, so every pre-UC45 zone (and every bounty zone) spawns exactly as before — the
 * RNG-consumption proof behind the "existing fixtures stay byte-identical" condition.
 */
object SpawnDirector {
    /**
     * The composition to actually spawn for a zone whose authored [base] composition is scaled by [scaling]
     * given the player's [progression] level (≥ 0; see [ProgressionLevel]). For [SpawnScaling.None] this is
     * [base] unchanged; for [SpawnScaling.ByProgression] it is [base] plus a bounded reinforcement entry.
     */
    fun scale(
        base: List<HostileSpawn>,
        scaling: SpawnScaling,
        progression: Int,
    ): List<HostileSpawn> =
        when (scaling) {
            is SpawnScaling.None -> base
            is SpawnScaling.ByProgression -> {
                val bonus = (progression.coerceAtLeast(0) * scaling.hostilesPerLevel).coerceAtMost(scaling.maxBonusHostiles)
                if (bonus <= 0) base else base + HostileSpawn(scaling.reinforcementArchetypeId, bonus)
            }
        }
}
