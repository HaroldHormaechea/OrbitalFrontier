package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType

/**
 * Stable identity of a [SalvageDrop] within the live world (UC42). A `value class` over a `Long`,
 * allocated from [com.orbitalfrontier.world.WorldState.nextSalvageId] so ids are **monotonic** and
 * never reused — exactly like [HostileId]. This gives drops a deterministic **total order** (ascending
 * [SalvageId]), so proximity pickup and iteration are byte-stable across a replay (UC42 AC#4).
 */
@JvmInline
value class SalvageId(val value: Long) : Comparable<SalvageId> {
    override fun compareTo(other: SalvageId): Int = value.compareTo(other.value)
}

/**
 * One collectible salvage wreck dropped by a destroyed hostile (UC42 AC#1) — its stable [id], the
 * world-space [position] it floats at (the hostile's kill position), the [credits] it carries (scrap
 * value, distinct from any bounty payout, UC42 dependency note) and the [resources] (units per
 * [ResourceType]) it yields into cargo on pickup.
 *
 * Pure, immutable value data (no engine types) so the salvage list composes into the immutable world
 * snapshot and is fully JVM-testable. **Transient world state** (mirrors [CombatState]/ADR 0012): it is
 * regenerated from combat, never row-persisted — a mid-flight save reloads with no pending salvage, so
 * there is no schema bump (UC42 design). [resources] holds only positive counts (an empty map is a
 * credits-only drop); the loot generator never authors a zero-unit entry.
 */
data class SalvageDrop(
    val id: SalvageId,
    val position: Vec2,
    val credits: Long,
    val resources: Map<ResourceType, Int>,
) {
    init {
        require(credits >= 0L) { "SalvageDrop ${id.value} credits must not be negative: $credits" }
        require(resources.values.all { it > 0 }) {
            "SalvageDrop ${id.value} resource counts must be positive: $resources"
        }
    }
}
