package com.orbitalfrontier.combat

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams

/**
 * The result of [CombatReputation.applyKills] (UC43): the new [reputation] after this tick's
 * faction-affiliated kills, plus the **actual** per-faction standing change ([deltas]) that landed
 * (post-clamp `new - old`). A faction whose standing was already pinned at the floor contributes no
 * entry, so [deltas] keys exactly the factions that moved — the caller (device only) enqueues one
 * notification per entry. Empty [deltas] ⇒ nothing moved.
 */
data class CombatReputationResult(
    val reputation: Reputation,
    val deltas: Map<FactionId, Int>,
) {
    /** True when at least one faction's standing actually changed this tick. */
    val changed: Boolean get() = deltas.isNotEmpty()
}

/**
 * **The** shared, pure combat→reputation resolver (UC43) — the single source of truth the device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the headless replay mirror
 * ([com.orbitalfrontier.sim.Simulation]) both call, so live and replayed standings are byte-identical
 * (project rule #1, the lockstep contract). It is the combat call site ADR 0013 predicted: the
 * [Reputation.with] seam already exists; this fills the empty combat side without building a parallel
 * reputation system.
 *
 * Engine-free and fully deterministic (no RNG, no wall-clock): the same `(destroyed, reputation,
 * params)` always yields the same [CombatReputationResult], so the whole flow is JVM-testable and
 * replay-stable (UC43 AC#5).
 *
 * **Per kill:** resolve the hostile's archetype via [HostileArchetypes.byId]; read its
 * [HostileArchetype.factionId]; a `null` faction (an unaligned raider/scavenger) is **skipped** (the
 * neutral-hostile rule); otherwise the standing moves by [ReputationParams.combatKillDelta] through
 * [Reputation.with], clamped to `[min, max]`. Single-faction MVP — there is no faction relationship
 * graph yet, so an affiliated kill only ever moves its own faction (UC43 AC#2; the allied/rival
 * propagation seam is noted in ADR 0031 for a future UC).
 *
 * **No-op ⇒ same instance.** When [destroyed] is empty, no kill is faction-affiliated, or every change
 * clamps to nothing, the **input** [reputation] is returned unchanged with empty [deltas], so the
 * caller cheaply detects "nothing changed" with a reference check (mirrors [Salvage]'s contract) and
 * unaligned-only kills keep pre-UC43 fixtures byte-identical.
 */
object CombatReputation {
    /**
     * Fold this tick's [destroyed] hostiles into the player's [reputation] (UC43 AC#1): every
     * faction-affiliated kill applies [ReputationParams.combatKillDelta] to that faction's standing via
     * [Reputation.with]. Returns the new reputation and the actual per-faction deltas that landed; the
     * SAME [reputation] instance with empty deltas when nothing moved.
     */
    fun applyKills(
        destroyed: List<DestroyedHostile>,
        reputation: Reputation,
        params: ReputationParams,
    ): CombatReputationResult {
        if (destroyed.isEmpty()) return CombatReputationResult(reputation, emptyMap())

        var working = reputation
        val deltas = LinkedHashMap<FactionId, Int>()
        for (hostile in destroyed) {
            val archetype = HostileArchetypes.byId(hostile.archetypeId) ?: continue
            val factionId = archetype.factionId ?: continue
            val before = working.valueFor(factionId)
            working = working.with(factionId, params.combatKillDelta, params.min, params.max)
            val applied = working.valueFor(factionId) - before
            if (applied != 0) {
                deltas[factionId] = (deltas[factionId] ?: 0) + applied
            }
        }

        if (deltas.isEmpty()) return CombatReputationResult(reputation, emptyMap())
        return CombatReputationResult(working, deltas)
    }
}
