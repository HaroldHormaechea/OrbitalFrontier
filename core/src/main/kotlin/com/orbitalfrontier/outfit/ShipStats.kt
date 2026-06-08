package com.orbitalfrontier.outfit

import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipType

/**
 * Pure derivation of a ship's **effective stats** from its [ShipType] baseline plus the [StatDelta]s
 * of everything in its [Loadout] (UC09 AC#2). The one place "ship type + fit → stat" lives, so device
 * and headless replay derive identical numbers (UC09 AC#7).
 *
 * Effective stat = `type baseline + Σ installed-upgrade deltas`. Deltas are resolved through the
 * [UpgradeCatalog] (defaulted to [UpgradeCatalog.MVP]); an [UpgradeId] the catalog no longer knows
 * contributes [StatDelta.NONE] (skipped, never crashes — "never stranded"). Because addition is
 * commutative and [Loadout] equality is order-insensitive, the derived stats are independent of
 * install order.
 *
 * **Byte-identical contract (HARD invariant, UC09):** for the starter ship type with an empty
 * loadout the derived stats equal exactly today's constants —
 * `cargoCapacity(STARTER, EMPTY) == Cargo.DEFAULT_CAPACITY (50)`,
 * `fuelCapacity(STARTER, EMPTY) == FuelParams.DEFAULT_TANK_CAPACITY (100)`, and
 * `effectiveMovementParams(params, STARTER, EMPTY) === params` (returned unchanged). This is what
 * lets the Fleet refactor land with zero behavior change and keeps the pre-UC09 fixtures replaying
 * bit-for-bit.
 */
object ShipStats {
    /** The summed [StatDelta] of every installed upgrade in [loadout], resolved via [catalog]. */
    private fun totalDelta(
        loadout: Loadout,
        catalog: UpgradeCatalog,
    ): StatDelta =
        loadout.allInstalled().fold(StatDelta.NONE) { acc, id ->
            acc + (catalog.upgrade(id)?.statDeltas ?: StatDelta.NONE)
        }

    /** Effective cargo capacity (units): the type's base plus cargo deltas, never below 0. */
    fun cargoCapacity(
        type: ShipType,
        loadout: Loadout,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): Int = (type.baseCargoCapacity + totalDelta(loadout, catalog).cargoCapacity).coerceAtLeast(0)

    /**
     * Effective fuel-tank capacity (fuel units): the type's base plus fuel deltas. Coerced to be
     * strictly positive (the [com.orbitalfrontier.economy.Fuel] invariant requires `capacity > 0`),
     * so a pathological negative delta can never produce an invalid tank.
     */
    fun fuelCapacity(
        type: ShipType,
        loadout: Loadout,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): Float = (type.baseFuelCapacity + totalDelta(loadout, catalog).fuelCapacity).coerceAtLeast(MIN_FUEL_CAPACITY)

    /** Effective scan range (world-units): the type's base plus sensor deltas, never below 0. */
    fun scanRange(
        type: ShipType,
        loadout: Loadout,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): Float = (type.baseScanRange + totalDelta(loadout, catalog).scanRange).coerceAtLeast(0f)

    /** Effective crew capacity (crew): the type's base plus crew-quarters deltas, never below 0. */
    fun crewCapacity(
        type: ShipType,
        loadout: Loadout,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): Int = (type.baseCrewCapacity + totalDelta(loadout, catalog).crew).coerceAtLeast(0)

    /**
     * [base] movement params with forward [ShipMovementParams.maxSpeed] and
     * [ShipMovementParams.maxAcceleration] adjusted by the ship type's [com.orbitalfrontier.ship
     * .MovementProfile] (multiplicative) and the loadout's engine deltas (additive). **Only** those
     * two fields change — the cone half-angles and the input deadzone are copied through unchanged, so
     * handling stays consistent as a ship is tuned for speed.
     *
     * Returns [base] **itself** (same instance) when neither field moves — the byte-identical guarantee
     * for the starter ship with an empty loadout (identity profile ×1.0, zero deltas), so existing
     * movement fixtures replay bit-for-bit.
     */
    fun effectiveMovementParams(
        base: ShipMovementParams,
        type: ShipType,
        loadout: Loadout,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): ShipMovementParams {
        val delta = totalDelta(loadout, catalog)
        val profile = type.movement
        val newMaxSpeed = base.maxSpeed * profile.maxSpeedMultiplier + delta.maxSpeed
        val newMaxAcceleration = base.maxAcceleration * profile.maxAccelerationMultiplier + delta.maxAcceleration
        if (newMaxSpeed == base.maxSpeed && newMaxAcceleration == base.maxAcceleration) return base
        return base.copy(maxSpeed = newMaxSpeed, maxAcceleration = newMaxAcceleration)
    }

    /** Smallest legal fuel capacity, guarding the `Fuel(capacity > 0)` invariant against bad deltas. */
    private const val MIN_FUEL_CAPACITY: Float = 1f
}
