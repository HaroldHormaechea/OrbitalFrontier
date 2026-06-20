package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * An environmental **hazard zone** POI (UC54 AC#1/#2) — a debris / radiation field that applies a defined
 * effect to the ship while it is traversed (docs/adr/0042-additional-poi-types.md;
 * docs/design/world-and-sector.md).
 *
 * A hazard is a [Poi] and a [Transponder] ([ContactKind.HAZARD]): it broadcasts so the player can see — and
 * choose to avoid — it without a scan (the detection split of UC54 AC#3).
 *
 * Its one MVP effect (UC54 AC#2) is a **per-second fuel drain** of [fuelDrainPerSecond] applied every tick
 * the ship is inside [radius], by the pure [com.orbitalfrontier.world.HazardEffect] (no RNG, no persistence
 * — purely an each-tick state effect, like fuel burn). The drain is clamped at an empty tank ([Fuel.consume])
 * and the [com.orbitalfrontier.economy.Fuel.speedFactor] floor (`floorSpeedFraction > 0`) guarantees the ship
 * can always limp out — a hazard slows you but **never bricks** the ship (UC54 AC#2, the no-strand invariant).
 *
 * Pure data — no engine types — so hazard zones are part of the JVM-testable world model (ADR 0001).
 */
data class HazardZone(
    override val id: PoiId,
    override val position: Vec2,
    /** Radius (world-units) of the hazard field around [position] within which the effect applies. */
    val radius: Float = DEFAULT_RADIUS,
    /** Fuel units drained per second while inside [radius] (multiplied by the tick's `dt`, like fuel burn). [TUNE] */
    val fuelDrainPerSecond: Float = DEFAULT_FUEL_DRAIN_PER_SECOND,
) : Poi, Transponder {
    override val contactKind: ContactKind get() = ContactKind.HAZARD

    init {
        require(radius > 0f) { "HazardZone $id radius must be positive: $radius" }
        require(fuelDrainPerSecond >= 0f) { "HazardZone $id fuelDrainPerSecond must not be negative: $fuelDrainPerSecond" }
    }

    companion object {
        /** Default hazard radius (world-units). [TUNE] */
        const val DEFAULT_RADIUS: Float = 240f

        /**
         * Default per-second fuel drain inside a hazard. Sized so a traversal noticeably bites the tank but,
         * with the [com.orbitalfrontier.economy.FuelParams.floorSpeedFraction] speed floor, never strands the
         * ship — it can always limp clear. [TUNE]
         */
        const val DEFAULT_FUEL_DRAIN_PER_SECOND: Float = 2.0f
    }
}
