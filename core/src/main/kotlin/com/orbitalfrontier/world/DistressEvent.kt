package com.orbitalfrontier.world

import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.EncounterSpawner
import com.orbitalfrontier.combat.HostileArchetypeId
import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType

/**
 * The authored tuning of a distress-signal mini-event (UC54 AC#2). A reward grants [rewardCredits] plus the
 * [rewardResources] poured into the hold; an ambush spawns [ambushCount] hostiles of [ambushArchetypeId].
 * Pinned per playthrough like every other `*Params`, so a later balance change cannot silently invalidate a
 * recorded distress fixture. All numbers are `[TUNE]`.
 */
data class DistressParams(
    val rewardCredits: Long = DEFAULT_REWARD_CREDITS,
    val rewardResources: Map<ResourceType, Int> = DEFAULT_REWARD_RESOURCES,
    val ambushArchetypeId: HostileArchetypeId = HostileArchetypes.SCAVENGER.id,
    val ambushCount: Int = DEFAULT_AMBUSH_COUNT,
) {
    init {
        require(rewardCredits >= 0L) { "DistressParams rewardCredits must not be negative: $rewardCredits" }
        require(ambushCount >= 1) { "DistressParams ambushCount must be >= 1: $ambushCount" }
        require(rewardResources.values.all { it >= 0 }) { "DistressParams rewardResources must not be negative: $rewardResources" }
    }

    companion object {
        const val DEFAULT_REWARD_CREDITS: Long = 120L
        const val DEFAULT_AMBUSH_COUNT: Int = 1
        val DEFAULT_REWARD_RESOURCES: Map<ResourceType, Int> = mapOf(ResourceType.TITANIUM to 3)
    }
}

/** Which branch a triggered distress signal resolved to (UC54 AC#2) — surfaced for the UI / the AC#5 fixture. */
enum class DistressOutcome { REWARD, AMBUSH }

/**
 * The result of [DistressEvent.resolve] (UC54 AC#2/#4): the [combat] after a possible ambush spawn, the
 * [cargo] + [credits] after a possible reward, the updated [consumedPois], the [triggered] signal id (null on
 * a no-op), its [outcome], and whether a reward [overflow]ed the hold. A no-op returns the SAME instances.
 */
data class DistressResult(
    val combat: CombatState,
    val cargo: Cargo,
    val credits: Long,
    val consumedPois: Set<PoiId>,
    val triggered: PoiId?,
    val outcome: DistressOutcome?,
    val overflow: Boolean,
)

/**
 * **The** shared, pure distress-signal resolver (UC54 AC#2) — the single source of truth the device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the headless replay mirror
 * ([com.orbitalfrontier.sim.Simulation]) both call, so live and replayed distress events are byte-identical
 * (project rule #1, the lockstep contract). Engine-free and seed-deterministic, so it is JVM-testable and
 * replay-stable (UC54 AC#4).
 *
 * **Edge-triggered**, mirroring [EncounterSpawner.naturalSpawn]: it fires once on the **outside→inside**
 * crossing of an un-consumed [DistressSignal]'s [DistressSignal.triggerRadius] ([previousPosition] outside,
 * [newPosition] inside), and only while no fight is active (suppressed during combat). The branch is decided
 * by a fresh [DeterministicRng] namespace `"distress:$id"` — independent of every existing stream (the
 * zero-fixture-regen lever) — splitting **[DistressOutcome.REWARD]** (fold credits + resources via the shared
 * [Salvage.fillCargo] helper) XOR **[DistressOutcome.AMBUSH]** (spawn hostiles via
 * [EncounterSpawner.missionSpawn] keyed `zoneId="distress:$id"`, so the ambush is NOT added to the authored
 * `ENCOUNTER_ZONES` — UC42's `encounterZones(alpha).single()` stays intact). Either branch marks the signal
 * **consumed** so it never fires again (deterministic + persisted, AC#4). No crossing — or a suppressed /
 * already-consumed signal — is a strict **no-op** returning the SAME instances (byte-identical).
 */
object DistressEvent {
    /** Resolve a distress crossing for this tick. [spawnTick] seeds an ambush spawn (sim tick / device spawn tick). */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        previousPosition: Vec2,
        newPosition: Vec2,
        consumedPois: Set<PoiId>,
        combat: CombatState,
        cargo: Cargo,
        credits: Long,
        spawnTick: Int,
        combatParams: CombatParams,
        params: DistressParams,
    ): DistressResult {
        val noop = DistressResult(combat, cargo, credits, consumedPois, triggered = null, outcome = null, overflow = false)
        // Suppressed while a fight is already active (a distress ambush can't stack on a live encounter).
        if (combat.active) return noop

        for (signal in world.sector(currentSector).distressSignals) {
            if (signal.id in consumedPois) continue
            val wasOutside = (previousPosition - signal.position).length > signal.triggerRadius
            val isInside = (newPosition - signal.position).length <= signal.triggerRadius
            if (!wasOutside || !isInside) continue

            val nextConsumed = consumedPois + signal.id
            // Branch on a fresh RNG namespace: one LCG step off the FNV-1a seed, then a 2-bucket draw.
            val rngState = DeterministicRng.lcgAdvance(DeterministicRng.fnv1a("distress:${signal.id.value}"))
            val isAmbush = DeterministicRng.boundedInt(rngState, 2) == 0
            return if (isAmbush) {
                val spawned =
                    EncounterSpawner.missionSpawn(
                        combat,
                        "distress:${signal.id.value}",
                        params.ambushArchetypeId,
                        params.ambushCount,
                        newPosition,
                        spawnTick,
                        combatParams,
                    )
                DistressResult(spawned, cargo, credits, nextConsumed, signal.id, DistressOutcome.AMBUSH, overflow = false)
            } else {
                val fill = Salvage.fillCargo(cargo, params.rewardResources)
                DistressResult(
                    combat = combat,
                    cargo = fill.cargo,
                    credits = credits + params.rewardCredits,
                    consumedPois = nextConsumed,
                    triggered = signal.id,
                    outcome = DistressOutcome.REWARD,
                    overflow = fill.leftover.isNotEmpty(),
                )
            }
        }
        return noop
    }
}
