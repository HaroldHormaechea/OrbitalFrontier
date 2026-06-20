package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Fuel

/**
 * **The** shared, pure hazard-effect resolver (UC54 AC#2) — the single source of truth the device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the headless replay mirror
 * ([com.orbitalfrontier.sim.Simulation]) both call, so live and replayed hazard effects are byte-identical
 * (project rule #1, the lockstep contract). No RNG and no persistence — purely an each-tick state effect on
 * the tank, exactly like fuel burn — so it is JVM-testable and replay-stable (UC54 AC#4).
 *
 * The MVP effect (AC#2) is a **per-second fuel drain** applied every tick the ship is inside a
 * [HazardZone]'s [HazardZone.radius]: the zone's [HazardZone.fuelDrainPerSecond] × [dt], summed across every
 * overlapping hazard in the sector, then drained via [Fuel.consume] (clamped at an empty tank — never
 * negative). The [com.orbitalfrontier.economy.Fuel.speedFactor] floor (`floorSpeedFraction > 0`) guarantees
 * the ship can always limp out, so a hazard slows the player but **never bricks** the ship (the no-strand
 * invariant). Outside every hazard — and at a fixed-empty tank — [resolve] returns the **same [Fuel]
 * instance**, so a non-hazard tick is byte-identical and allocates nothing.
 */
object HazardEffect {
    /** Resolve this tick's hazard fuel drain against the post-movement [shipPosition] in [currentSector]. */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        fuel: Fuel,
        dt: Float,
    ): Fuel {
        var drain = 0f
        for (hazard in world.sector(currentSector).hazardZones) {
            if ((shipPosition - hazard.position).length <= hazard.radius) {
                drain += hazard.fuelDrainPerSecond * dt
            }
        }
        // Fuel.consume(0f) returns the same instance, so a no-hazard tick is byte-identical.
        return fuel.consume(drain)
    }
}
