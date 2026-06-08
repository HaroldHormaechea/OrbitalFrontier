package com.orbitalfrontier.economy

/**
 * The outcome of attempting to load resources into a [Cargo] (UC06 AC#3) — the new cargo plus how
 * many units were actually accepted.
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes): "cargo is full / only part fit" is a normal, recoverable case,
 * not an error, so [Cargo.add] reports the accepted amount rather than throwing or silently dropping
 * units. The caller (mining) threads [acceptedUnits] back into its extraction budget so a partial
 * load is exact.
 */
data class CargoTransfer(
    /** The cargo after accepting [acceptedUnits] (the original cargo unchanged when none fit). */
    val cargo: Cargo,
    /** How many units were actually accepted — 0 when the hold was already full. */
    val acceptedUnits: Int,
)

/**
 * The player's ship cargo hold: an immutable map of [ResourceType] → unit count, bounded by a
 * [capacity] (UC06 AC#3, AC#5).
 *
 * Pure, immutable value (coding-guidelines § immutability): every mutation returns a new [Cargo],
 * so it composes safely into the immutable [com.orbitalfrontier.world.WorldState] snapshot the
 * autosave thread reads. No engine types, so cargo + mining logic is fully JVM-testable (UC06 AC#6).
 *
 * **Capacity is a ship stat, not save data.** Only the [contents] are persisted; [capacity] is
 * reconstructed from [DEFAULT_CAPACITY] (later, from the ship's upgrade loadout) on load — so a save
 * never pins a stale capacity. [usedUnits]/[remainingCapacity]/[isFull] are derived, never stored.
 */
data class Cargo(
    val contents: Map<ResourceType, Int>,
    val capacity: Int,
) {
    init {
        require(capacity >= 0) { "Cargo capacity must not be negative: $capacity" }
        require(contents.values.all { it >= 0 }) { "Cargo unit counts must not be negative: $contents" }
    }

    /** Total units currently held across all resource types. */
    val usedUnits: Int get() = contents.values.sum()

    /** Free space remaining (never negative). */
    val remainingCapacity: Int get() = (capacity - usedUnits).coerceAtLeast(0)

    /** Whether the hold can accept no more units. */
    val isFull: Boolean get() = usedUnits >= capacity

    /**
     * Accept up to [units] of [resource], bounded by [remainingCapacity] (UC06 AC#3). Returns the
     * resulting cargo and the number of units actually accepted; when the hold is already full (or
     * [units] is 0) the original cargo is returned unchanged with `acceptedUnits = 0`.
     *
     * @throws IllegalArgumentException if [units] is negative (a programmer error — fail fast).
     */
    fun add(
        resource: ResourceType,
        units: Int,
    ): CargoTransfer {
        require(units >= 0) { "Cannot add a negative number of units: $units" }
        val accepted = units.coerceAtMost(remainingCapacity)
        if (accepted == 0) return CargoTransfer(this, 0)
        val updated = contents + (resource to ((contents[resource] ?: 0) + accepted))
        return CargoTransfer(Cargo(updated, capacity), accepted)
    }

    companion object {
        /**
         * Default cargo capacity (units) of a starter ship. An authored tunable; later UCs derive
         * capacity from cargo-hold upgrades (economy design note "Cargo & upgrade slots"). [TUNE]
         */
        const val DEFAULT_CAPACITY: Int = 50

        /** An empty hold with [capacity] (default [DEFAULT_CAPACITY]). */
        fun empty(capacity: Int = DEFAULT_CAPACITY): Cargo = Cargo(emptyMap(), capacity)
    }
}
