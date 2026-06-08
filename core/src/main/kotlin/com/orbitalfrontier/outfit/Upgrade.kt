package com.orbitalfrontier.outfit

/**
 * Stable identity of an [Upgrade] (UC09). A value class over a [String] slug so an upgrade is keyed
 * by a diffable, save-stable name (`ship_upgrade.upgrade_id`) rather than an ordinal — appending or
 * reordering the catalog never invalidates a saved loadout. Blank ids are rejected (an authoring
 * error — fail fast).
 */
@JvmInline
value class UpgradeId(val value: String) {
    init {
        require(value.isNotBlank()) { "UpgradeId must not be blank" }
    }
}

/**
 * The additive stat changes one installed [Upgrade] contributes (UC09 AC#2).
 *
 * Every field is a **delta** applied on top of the ship type's baseline by [ShipStats]; a value of 0
 * means "this upgrade does not touch that stat". Deltas compose by simple addition ([plus]), so a
 * ship's effective stats are `type baseline + Σ installed upgrade deltas` regardless of install order
 * (order-insensitive, matching [Loadout]'s equality). Movement deltas affect **only** forward
 * [maxSpeed] and [maxAcceleration]; the cone/​deadzone movement params are never altered by an upgrade
 * (handling stays consistent — see [ShipStats.effectiveMovementParams]).
 *
 * Pure value, no engine types, so the whole stat model stays JVM-testable (UC09 AC#7).
 */
data class StatDelta(
    /** Added to the ship's forward max speed (world-units/s). [TUNE] */
    val maxSpeed: Float = 0f,
    /** Added to the ship's forward thrust acceleration (world-units/s²). [TUNE] */
    val maxAcceleration: Float = 0f,
    /** Added to the ship's cargo-hold capacity (units). [TUNE] */
    val cargoCapacity: Int = 0,
    /** Added to the ship's fuel-tank capacity (fuel units). [TUNE] */
    val fuelCapacity: Float = 0f,
    /** Added to the ship's scan range (world-units). [TUNE] */
    val scanRange: Float = 0f,
    /** Added to the ship's crew capacity (crew). [TUNE] */
    val crew: Int = 0,
) {
    /** Sum two deltas component-wise (composition is order-insensitive — addition is commutative). */
    operator fun plus(other: StatDelta): StatDelta =
        StatDelta(
            maxSpeed = maxSpeed + other.maxSpeed,
            maxAcceleration = maxAcceleration + other.maxAcceleration,
            cargoCapacity = cargoCapacity + other.cargoCapacity,
            fuelCapacity = fuelCapacity + other.fuelCapacity,
            scanRange = scanRange + other.scanRange,
            crew = crew + other.crew,
        )

    companion object {
        /** The identity delta (changes nothing); the sum-fold seed and the "unknown upgrade" fallback. */
        val NONE: StatDelta = StatDelta()
    }
}

/**
 * One purchasable, installable upgrade (UC09 AC#2/#3) — a part that occupies one free slot of its
 * [category] and contributes [statDeltas] to the ship's effective stats while installed.
 *
 * Pure authored data (no engine types), catalogued in [UpgradeCatalog]; a [Loadout] stores only the
 * [id] per slot and resolves the full [Upgrade] through the catalog, so the same part is never
 * duplicated across loadouts and a price/stat retune lives in exactly one place. [price] is the
 * dealer buy cost in credits ([Long], matching the wallet, so a large balance never overflows);
 * removing/selling a used part at a junkyard refunds against this price (UC09 AC#4 — see
 * [Outfitting]).
 */
data class Upgrade(
    val id: UpgradeId,
    val category: SlotCategory,
    val displayName: String,
    val price: Long,
    val statDeltas: StatDelta,
) {
    init {
        require(displayName.isNotBlank()) { "Upgrade ${id.value} displayName must not be blank" }
        require(price >= 0) { "Upgrade ${id.value} price must not be negative: $price" }
    }
}
