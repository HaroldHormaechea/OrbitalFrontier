package com.orbitalfrontier.ship

import com.orbitalfrontier.outfit.SlotCategory

/**
 * Stable identity of a [ShipType] (UC09). A value class over a [String] slug, persisted in the
 * `ship.ship_type` column — diffable and save-stable, so the roster can grow without renumbering.
 * Blank ids are rejected (an authoring error — fail fast).
 */
@JvmInline
value class ShipTypeId(val value: String) {
    init {
        require(value.isNotBlank()) { "ShipTypeId must not be blank" }
    }
}

/** The broad gameplay role a [ShipType] is built for (UC09 AC#1). A closed `enum` (coding-guidelines § O). */
enum class ShipRole {
    /** A balanced all-rounder — the starter ship. */
    GENERALIST,

    /** Cargo- and utility-focused (more hold, mining slots). */
    MINER,

    /** Speed- and cargo-focused courier. */
    COURIER,
}

/**
 * Per-ship multiplicative movement tuning (UC09) — how a ship type scales the **base**
 * [ShipMovementParams] before any engine upgrade is applied. Lets a courier hull be intrinsically
 * faster and a miner hull slower without re-authoring the base params.
 *
 * Only forward max-speed and acceleration are scaled (matching [com.orbitalfrontier.outfit.ShipStats
 * .effectiveMovementParams], which never touches the cone/​deadzone params). [IDENTITY] (×1.0) leaves
 * the base untouched, which the starter ship uses to honor the byte-identical contract.
 */
data class MovementProfile(
    val maxSpeedMultiplier: Float = 1f,
    val maxAccelerationMultiplier: Float = 1f,
) {
    init {
        require(maxSpeedMultiplier > 0f) { "maxSpeedMultiplier must be positive: $maxSpeedMultiplier" }
        require(maxAccelerationMultiplier > 0f) { "maxAccelerationMultiplier must be positive: $maxAccelerationMultiplier" }
    }

    companion object {
        /** The no-op profile (×1.0 on both axes); the starter ship's profile. */
        val IDENTITY: MovementProfile = MovementProfile()
    }
}

/**
 * A purchasable ship type — a **role with a fixed per-category slot layout** and baseline stats
 * (UC09 AC#1; docs/design/upgrades-and-progression.md "Ships as roles/classes").
 *
 * Pure authored data (no engine types), catalogued in [ShipRoster]. An owned ship ([OwnedShip])
 * references its type by value and derives its effective cargo/fuel/scan/crew/movement stats from
 * `type baseline + loadout deltas` via [com.orbitalfrontier.outfit.ShipStats] — so a ship type
 * change retunes every ship of that type and capacity is never a stale saved number.
 *
 * [slotCounts] is the data-driven slot layout: how many outfitting slots the ship exposes per
 * [SlotCategory] (a category absent from the map has zero slots). [baseCargoCapacity] /
 * [baseFuelCapacity] are the stats an **empty** fit derives; for the starter type they are pinned to
 * today's constants so the Fleet refactor is byte-identical (see [ShipRoster.STARTER]).
 */
data class ShipType(
    val id: ShipTypeId,
    val displayName: String,
    val role: ShipRole,
    val slotCounts: Map<SlotCategory, Int>,
    val baseCargoCapacity: Int,
    val baseFuelCapacity: Float,
    val baseScanRange: Float,
    val baseCrewCapacity: Int,
    val movement: MovementProfile = MovementProfile.IDENTITY,
    /**
     * Dealer purchase price in credits (UC09 AC#5) — what a shipyard charges for this hull. The
     * analogue of [com.orbitalfrontier.outfit.Upgrade.price]. Defaults to 0 (the starter ship is never
     * for sale, so its price is unused); a purchasable type sets a positive [TUNE] price.
     */
    val price: Long = 0L,
    /**
     * Minimum standing the player must hold with the **docked station's faction** to buy this hull
     * (UC48 AC#1) — the shipyard analogue of [com.orbitalfrontier.outfit.Upgrade.unlockThreshold].
     * Default **0 = ungated**, so every pre-UC48 hull stays buyable everywhere and the roster is
     * byte-identical; a positive value gates the hull behind reputation at league shipyards. The
     * required faction is implicit (the docked station's), so this stays pure authored data — never
     * persisted (only the owned [ShipTypeId] persists), so it carries no schema/DTO impact. Evaluated at
     * read time by [com.orbitalfrontier.faction.StandingGate]. [TUNE]
     */
    val unlockThreshold: Int = 0,
) {
    init {
        require(displayName.isNotBlank()) { "ShipType ${id.value} displayName must not be blank" }
        require(price >= 0) { "ShipType ${id.value} price must not be negative: $price" }
        require(unlockThreshold >= 0) { "ShipType ${id.value} unlockThreshold must not be negative: $unlockThreshold" }
        require(baseCargoCapacity >= 0) { "ShipType ${id.value} baseCargoCapacity must not be negative" }
        require(baseFuelCapacity > 0f) { "ShipType ${id.value} baseFuelCapacity must be positive" }
        require(baseScanRange >= 0f) { "ShipType ${id.value} baseScanRange must not be negative" }
        require(baseCrewCapacity >= 0) { "ShipType ${id.value} baseCrewCapacity must not be negative" }
        require(slotCounts.values.all { it >= 0 }) { "ShipType ${id.value} slot counts must not be negative" }
    }

    /** The number of outfitting slots this ship exposes in [category] (0 if it has none). */
    fun slotCount(category: SlotCategory): Int = slotCounts[category] ?: 0
}
