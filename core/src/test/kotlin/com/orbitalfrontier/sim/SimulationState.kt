package com.orbitalfrontier.sim

import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.SectorId

/**
 * Immutable snapshot of the whole simulated world at one tick — the value the pure [Simulation]
 * reads and produces each step, and the unit a replay asserts against (UC02 AC#5/#6).
 *
 * UC02 simulated ship movement only (the [tick] index and the ship's [ShipKinematics]); UC03 adds
 * [currentSector] — which sector the ship occupies (UC03 AC#5), advanced by [Simulation] when the
 * ship flies through a jump gate. It is **defaulted** to [MvpSectorMap.START_SECTOR] so older
 * recorded playthroughs (whose serialized snapshots predate the field) still construct. This is the
 * documented **extension point**: as later use cases add simulated systems (economy, missions, world
 * entities), add their immutable state here as new `val`s with sensible defaults so older recorded
 * playthroughs still construct. Keep every field a plain domain type (no serialization annotations) —
 * the on-disk shape lives in a separate DTO ([com.orbitalfrontier.playthrough.StateSnapshotDto]) so
 * persistence concerns never leak into the sim's value types.
 */
data class SimulationState(
    val tick: Int = 0,
    val ship: ShipKinematics = ShipKinematics(),
    val currentSector: SectorId = MvpSectorMap.START_SECTOR,
)
