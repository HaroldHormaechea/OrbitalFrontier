package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType

/**
 * A hostile that was destroyed this tick (UC42) — the inputs [Salvage.spawn] needs to mint its wreck.
 * The [CombatEvent.HostileDestroyed] event carries only the [id], so the caller looks the hostile up in
 * the **pre-step** combat state to recover its kill [position] and [archetypeId] (the loot key) before
 * the cull removes it.
 */
data class DestroyedHostile(
    val id: HostileId,
    val archetypeId: HostileArchetypeId,
    val position: Vec2,
)

/** The result of [Salvage.spawn]: the new drop list and the advanced monotonic id allocator. */
data class SpawnResult(
    val drops: List<SalvageDrop>,
    val nextSalvageId: Long,
)

/**
 * The result of [Salvage.collect] (UC42 AC#2/#3): the remaining drops, the cargo + credits after pickup,
 * whether anything moved this tick ([collectedAny]) and whether a hold-full overflow left resources
 * behind ([overflow], drives the UC35 "CARGO FULL" notification).
 */
data class CollectResult(
    val drops: List<SalvageDrop>,
    val cargo: Cargo,
    val credits: Long,
    val collectedAny: Boolean,
    val overflow: Boolean,
)

/**
 * **The** shared, pure salvage logic (UC42) — the single source of truth the device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the headless replay mirror
 * ([com.orbitalfrontier.sim.Simulation]) both call, so live and replayed salvage are byte-identical
 * (project rule #1, the lockstep contract). Engine-free and seed-deterministic (loot via [LootTable]),
 * so the whole flow is JVM-testable and replay-stable (UC42 AC#4).
 *
 * Two operations:
 *  - [spawn] — mint one [SalvageDrop] per kill at the kill position, loot rolled from [LootTable].
 *  - [collect] — proximity pickup: credits to the wallet, resources to [Cargo] (capacity-respecting
 *    partial fill), deterministic overflow when the hold fills (UC42 AC#3).
 *
 * Both are **no-ops returning the same instances** when there is nothing to do (no kills / nothing in
 * range), so a quiet tick allocates nothing and stays byte-identical.
 */
object Salvage {
    /**
     * Mint one wreck per destroyed hostile (UC42 AC#1), appending to [existing] in monotonic
     * [SalvageId] order from [nextSalvageId]. Each wreck's loot is rolled purely from
     * `"salvage:$zoneId:${hostileId.value}"` via [LootTable.roll] — independent of the combat RNG, so
     * combat fixtures stay byte-identical. Returns the SAME [existing] list (no-op) when [destroyed] is
     * empty.
     */
    fun spawn(
        existing: List<SalvageDrop>,
        nextSalvageId: Long,
        zoneId: String,
        destroyed: List<DestroyedHostile>,
    ): SpawnResult {
        if (destroyed.isEmpty()) return SpawnResult(existing, nextSalvageId)
        var id = nextSalvageId
        val drops = ArrayList(existing)
        for (hostile in destroyed) {
            val loot = LootTable.roll(hostile.archetypeId, "salvage:$zoneId:${hostile.id.value}")
            drops.add(SalvageDrop(SalvageId(id), hostile.position, loot.credits, loot.resources))
            id++
        }
        return SpawnResult(drops, id)
    }

    /**
     * Collect every drop within [pickupRadius] of [playerPos] (UC42 AC#2/#3), walking [drops] in their
     * [SalvageId] order (they are appended in id order, so already sorted) so pickup is deterministic.
     *
     * For each in-range drop: its credits are always taken (credits need no space), then its resources
     * are offered to [cargo] in [ResourceType] declaration order via the capacity-respecting
     * [Cargo.add] partial fill. If everything fits the drop is removed; if the hold fills, the leftover
     * resources are **kept on the drop** (so they can be picked up later once space frees) with the
     * drop's credits zeroed (already collected) and [CollectResult.overflow] set — the deterministic
     * partial-pickup of AC#3.
     *
     * Returns the SAME [drops]/[cargo]/[credits] instances (a true no-op) when nothing was collected —
     * no drop in range, or only an already-credit-less drop whose resources still cannot fit.
     */
    fun collect(
        drops: List<SalvageDrop>,
        playerPos: Vec2,
        cargo: Cargo,
        credits: Long,
        pickupRadius: Float,
    ): CollectResult {
        if (drops.isEmpty()) return CollectResult(drops, cargo, credits, collectedAny = false, overflow = false)

        var workingCargo = cargo
        var workingCredits = credits
        var collectedAny = false
        var overflow = false
        val remaining = ArrayList<SalvageDrop>(drops.size)

        for (drop in drops) {
            if ((drop.position - playerPos).length > pickupRadius) {
                remaining.add(drop)
                continue
            }
            // In range: take the credits (no space needed).
            if (drop.credits != 0L) {
                workingCredits += drop.credits
                collectedAny = true
            }
            // Offer the resources to the hold in ResourceType declaration order (the deterministic
            // contract, matching mining). A partial fill keeps the leftover on the drop (AC#3).
            val leftover = LinkedHashMap<ResourceType, Int>()
            for (resource in ResourceType.entries) {
                val units = drop.resources[resource] ?: 0
                if (units <= 0) continue
                val transfer = workingCargo.add(resource, units)
                workingCargo = transfer.cargo
                if (transfer.acceptedUnits > 0) collectedAny = true
                val left = units - transfer.acceptedUnits
                if (left > 0) leftover[resource] = left
            }
            if (leftover.isNotEmpty()) {
                overflow = true
                remaining.add(drop.copy(credits = 0L, resources = leftover))
            }
        }

        // Nothing actually moved — return the original instances so a quiet tick is byte-identical.
        if (!collectedAny) return CollectResult(drops, cargo, credits, collectedAny = false, overflow = overflow)
        return CollectResult(remaining, workingCargo, workingCredits, collectedAny = true, overflow = overflow)
    }
}
