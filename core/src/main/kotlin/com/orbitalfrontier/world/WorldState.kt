package com.orbitalfrontier.world

import com.orbitalfrontier.ship.ShipKinematics

/**
 * Immutable snapshot of the player's place in the world: which sector they are in and the ship's
 * spatial state (UC03 AC#5).
 *
 * This is the production game-state value that persistence will serialize — UC04 writes/reads it;
 * UC03 only **exposes** it (no save logic here). It is intentionally small and pure (only domain
 * types), so it composes into a larger save snapshot later without dragging in engine or
 * serialization concerns.
 *
 * The [SectorWorld] graph itself is fixed authored data (rebuilt from [MvpSectorMap]); only the
 * mutable, per-player fields live here, so a save stays compact (store the position in the graph,
 * not a copy of the graph).
 */
data class WorldState(
    val currentSector: SectorId = MvpSectorMap.START_SECTOR,
    val ship: ShipKinematics = ShipKinematics(),
)
