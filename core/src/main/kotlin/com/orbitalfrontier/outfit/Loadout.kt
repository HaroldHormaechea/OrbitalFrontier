package com.orbitalfrontier.outfit

/**
 * The outcome of a pure [Loadout.install] (UC09 AC#2) — an explicit result rather than a thrown
 * exception, because "no free slot" is a normal, recoverable case (coding-guidelines § error-handling).
 */
sealed interface InstallResult {
    /** The part was installed at [slotIndex] within its category; [loadout] is the new loadout. */
    data class Installed(val loadout: Loadout, val slotIndex: Int) : InstallResult

    /** Every slot of the category is full at the ship's slot count — nothing changed. */
    data object NoFreeSlot : InstallResult
}

/**
 * The outcome of a pure [Loadout.remove] (UC09 AC#2/#4) — explicit, for the same reason as
 * [InstallResult].
 */
sealed interface RemoveResult {
    /** The part [removed] was cleared from its slot; [loadout] is the new loadout with a real gap. */
    data class Removed(val loadout: Loadout, val removed: UpgradeId) : RemoveResult

    /** No part occupied that (category, slotIndex) — nothing changed. */
    data object EmptySlot : RemoveResult
}

/**
 * A ship's installed upgrades, addressed by **(category, slot index)** (UC09 AC#1/#2).
 *
 * Modelled as a nested, **gap-tolerant** map `category → (slotIndex → UpgradeId)`: removing a part
 * from a mid-list slot leaves a real **gap** (the index is simply absent) rather than compacting the
 * indices, so slot identity is stable across a junkyard removal (UC09 AC#4) and persistence is a
 * plain `(category, slot_index)` row set. The map carries no slot *counts* — how many slots a
 * category has is the ship type's data ([com.orbitalfrontier.ship.ShipType.slotCounts]) and is passed
 * to [install] per call, so the same loadout type works for every ship.
 *
 * **Equality is order-insensitive** (Kotlin `Map` equality is value-based), so two loadouts with the
 * same parts in the same slots compare equal regardless of how they were built — which keeps the
 * whole-[com.orbitalfrontier.sim.SimulationState] data-class equality stable across a record/replay
 * round-trip (UC09 determinism). Empty inner maps are never retained (an emptied category drops out),
 * so there is a single canonical representation of any loadout.
 *
 * Pure, immutable value with no engine types (UC09 AC#7): every change returns a new [Loadout].
 */
data class Loadout(
    val slots: Map<SlotCategory, Map<Int, UpgradeId>> = emptyMap(),
) {
    /** True when no slot is filled in any category. */
    val isEmpty: Boolean get() = slots.isEmpty()

    /** How many slots of [category] are currently filled. */
    fun installedCount(category: SlotCategory): Int = slots[category]?.size ?: 0

    /** The upgrade installed at ([category], [slotIndex]), or null if that slot is empty. */
    fun upgradeAt(
        category: SlotCategory,
        slotIndex: Int,
    ): UpgradeId? = slots[category]?.get(slotIndex)

    /** Every installed [UpgradeId] across all categories/slots (order unspecified — used for summation). */
    fun allInstalled(): List<UpgradeId> = slots.values.flatMap { it.values }

    /**
     * Install [upgradeId] into the **lowest free slot index** of [category], bounded by [slotCount]
     * (the ship type's slot count for that category). Indices run `0 until slotCount`; the first index
     * not already occupied is used, so a gap left by an earlier [remove] is refilled before growing.
     * Returns [InstallResult.NoFreeSlot] (loadout unchanged) when all [slotCount] slots are taken.
     *
     * @throws IllegalArgumentException if [slotCount] is negative (a programmer error — fail fast).
     */
    fun install(
        category: SlotCategory,
        slotCount: Int,
        upgradeId: UpgradeId,
    ): InstallResult {
        require(slotCount >= 0) { "slotCount must not be negative: $slotCount" }
        val current = slots[category].orEmpty()
        val freeIndex = (0 until slotCount).firstOrNull { it !in current } ?: return InstallResult.NoFreeSlot
        val updatedCategory = current + (freeIndex to upgradeId)
        return InstallResult.Installed(copy(slots = slots + (category to updatedCategory)), freeIndex)
    }

    /**
     * Remove whatever part occupies ([category], [slotIndex]), leaving a **gap** at that index (the
     * remaining slots keep their indices — never compacted). Returns [RemoveResult.EmptySlot]
     * (loadout unchanged) when that slot was already empty. An emptied category is dropped entirely so
     * the representation stays canonical.
     */
    fun remove(
        category: SlotCategory,
        slotIndex: Int,
    ): RemoveResult {
        val current = slots[category] ?: return RemoveResult.EmptySlot
        val removed = current[slotIndex] ?: return RemoveResult.EmptySlot
        val updatedCategory = current - slotIndex
        val updatedSlots =
            if (updatedCategory.isEmpty()) slots - category else slots + (category to updatedCategory)
        return RemoveResult.Removed(copy(slots = updatedSlots), removed)
    }

    companion object {
        /** The empty loadout (nothing installed); a fresh ship's starting fit. */
        val EMPTY: Loadout = Loadout()
    }
}
