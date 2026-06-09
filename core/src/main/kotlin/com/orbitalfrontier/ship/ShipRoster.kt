package com.orbitalfrontier.ship

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.outfit.SlotCategory

/**
 * The data-driven catalog of purchasable [ShipType]s in the MVP (UC09 AC#1).
 *
 * Authored constant data, reconstructed at runtime (never row-persisted): a saved ship stores only
 * its [ShipTypeId] (`ship.ship_type`) and resolves the full type back here on load, so a stat/​layout
 * retune lives in one place and an unknown saved type degrades gracefully to [STARTER] with a WARN
 * (never stranded — see [com.orbitalfrontier.save.SqlDelightGameStateRepository]).
 *
 * **[STARTER]'s empty-fit stats are pinned to today's constants** ([Cargo.DEFAULT_CAPACITY],
 * [FuelParams.DEFAULT_TANK_CAPACITY], identity movement) so the Fleet refactor lands byte-identical
 * (UC09 Stage A). The alternates are provisional **[TUNE]** placeholders for balancing.
 */
object ShipRoster {
    /** The starter ship's type id; also the `ship.ship_type` column default in schema v7. */
    val STARTER_TYPE_ID: ShipTypeId = ShipTypeId("starter")

    /**
     * The starter ship: a generalist whose **empty-fit** stats equal exactly today's constants — its
     * base cargo/fuel capacities reference [Cargo.DEFAULT_CAPACITY] / [FuelParams.DEFAULT_TANK_CAPACITY]
     * directly (single source of truth) and its movement profile is the identity, so
     * [com.orbitalfrontier.outfit.ShipStats] derives the pre-UC09 numbers unchanged.
     *
     * The Wayfarer is also the **fuel-duration calibration reference** (UC16): fuel draw is tuned in
     * [com.orbitalfrontier.power.PowerParams] so its full tank drains in ~30 min of continuous
     * thrust, and every other ship shares that per-second draw against its own tank size.
     */
    val STARTER: ShipType =
        ShipType(
            id = STARTER_TYPE_ID,
            displayName = "Wayfarer",
            role = ShipRole.GENERALIST,
            slotCounts =
                mapOf(
                    SlotCategory.ENGINES to 2,
                    SlotCategory.CARGO to 2,
                    SlotCategory.FUEL_TANK to 1,
                    SlotCategory.SENSORS to 1,
                    SlotCategory.HULL_PLATING to 1,
                    SlotCategory.CREW_QUARTERS to 1,
                ),
            baseCargoCapacity = Cargo.DEFAULT_CAPACITY,
            baseFuelCapacity = FuelParams.DEFAULT_TANK_CAPACITY,
            baseScanRange = DEFAULT_STARTER_SCAN_RANGE,
            baseCrewCapacity = DEFAULT_STARTER_CREW,
            movement = MovementProfile.IDENTITY,
        )

    /** A cargo-hauler role: bigger hold + extra cargo slots, intrinsically slower hull. [TUNE] */
    val PROSPECTOR: ShipType =
        ShipType(
            id = ShipTypeId("prospector"),
            displayName = "Prospector",
            role = ShipRole.MINER,
            slotCounts =
                mapOf(
                    SlotCategory.ENGINES to 1,
                    SlotCategory.CARGO to 4,
                    SlotCategory.FUEL_TANK to 2,
                    SlotCategory.SENSORS to 2,
                    SlotCategory.HULL_PLATING to 2,
                    SlotCategory.CREW_QUARTERS to 2,
                ),
            baseCargoCapacity = 90,
            baseFuelCapacity = 140f,
            baseScanRange = 700f,
            baseCrewCapacity = 4,
            movement = MovementProfile(maxSpeedMultiplier = 0.85f, maxAccelerationMultiplier = 0.8f),
            price = 2500,
        )

    /** A courier role: speed + cargo, fewer utility slots, intrinsically faster hull. [TUNE] */
    val SWIFT: ShipType =
        ShipType(
            id = ShipTypeId("swift"),
            displayName = "Swift",
            role = ShipRole.COURIER,
            slotCounts =
                mapOf(
                    SlotCategory.ENGINES to 3,
                    SlotCategory.CARGO to 2,
                    SlotCategory.FUEL_TANK to 1,
                    SlotCategory.SENSORS to 1,
                    SlotCategory.HULL_PLATING to 1,
                    SlotCategory.CREW_QUARTERS to 1,
                ),
            baseCargoCapacity = 40,
            baseFuelCapacity = 90f,
            baseScanRange = 600f,
            baseCrewCapacity = 2,
            movement = MovementProfile(maxSpeedMultiplier = 1.25f, maxAccelerationMultiplier = 1.2f),
            price = 1800,
        )

    /** Every ship type in the MVP roster, in authored order ([STARTER] first). */
    val all: List<ShipType> = listOf(STARTER, PROSPECTOR, SWIFT)

    private val byId: Map<ShipTypeId, ShipType> = all.associateBy { it.id }

    /** The ship type with [id], or null if it is not in the roster (e.g. a saved id since removed). */
    fun byId(id: ShipTypeId): ShipType? = byId[id]

    /** Default starter scan range (world-units). An authored tunable; no live consumer yet (sensors). [TUNE] */
    private const val DEFAULT_STARTER_SCAN_RANGE: Float = 500f

    /** Default starter crew capacity. An authored tunable; gates turrets in a later combat UC. [TUNE] */
    private const val DEFAULT_STARTER_CREW: Int = 2
}
