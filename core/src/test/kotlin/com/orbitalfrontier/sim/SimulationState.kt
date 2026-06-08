package com.orbitalfrontier.sim

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId

/**
 * Immutable snapshot of the whole simulated world at one tick — the value the pure [Simulation]
 * reads and produces each step, and the unit a replay asserts against (UC02 AC#5/#6).
 *
 * UC02 simulated ship movement only (the [tick] index and the ship's [ShipKinematics]); UC03 adds
 * [currentSector] — which sector the ship occupies (UC03 AC#5), advanced by [Simulation] when the
 * ship flies through a jump gate; UC05 adds [dockedStation] — the station the ship is docked at, or
 * null when in flight (UC05 AC#4/#6). Each new field is **defaulted** (here `null`) so older recorded
 * playthroughs (whose serialized snapshots predate the field) still construct. This is the documented
 * **extension point**: as later use cases add simulated systems (economy, missions, world entities),
 * add their immutable state here as new `val`s with sensible defaults so older recorded playthroughs
 * still construct. Keep every field a plain domain type (no serialization annotations) — the on-disk
 * shape lives in a separate DTO ([com.orbitalfrontier.playthrough.StateSnapshotDto]) so persistence
 * concerns never leak into the sim's value types.
 *
 * [dockedStation] mirrors the production [com.orbitalfrontier.world.WorldState.dockedStation] so a
 * replayed snapshot maps straight onto the saved world state (UC05 AC#4). While it is non-null the
 * [Simulation] freezes the ship (no movement, no gate traversal) — see [Simulation.step].
 *
 * UC06 adds [cargo] (the active ship's hold) and [fieldDepletion] (remaining deposits per asteroid
 * field, keyed by [PoiId]; an **absent** field is pristine). Both mirror the production
 * [com.orbitalfrontier.world.WorldState] fields so a replayed snapshot maps straight onto the saved
 * world state (UC06 AC#4/#5), and both are defaulted (empty hold at [Cargo.DEFAULT_CAPACITY]; no
 * depletion) so older recorded playthroughs — and the non-mining UC01/03/05 fixtures — construct and
 * step unchanged.
 *
 * UC07 adds [fuel] (the active ship's tank) — mirroring the production
 * [com.orbitalfrontier.world.WorldState.fuel] so a replayed snapshot maps straight onto the saved
 * world state (UC07 AC#6). It is defaulted to a full tank ([Fuel.full]) so older recorded playthroughs
 * — and the UC01/03/05/06 fixtures — construct unchanged; and because [Fuel.speedFactor] is exactly
 * `1.0f` at a full-enough tank, those fixtures also **step movement byte-identically** (the fuel-
 * limited speed cap is a no-op until the tank drops below the low-fuel threshold — UC07 composition
 * guarantee, enforced by [com.orbitalfrontier.ship.FuelLimitedMovement]).
 *
 * UC08 adds [credits] (the player's single-currency wallet) — mirroring the production
 * [com.orbitalfrontier.world.WorldState.credits] so a replayed snapshot maps straight onto the saved
 * world state (UC08 AC#1). It is defaulted to `0L` so older recorded playthroughs — and the
 * UC01/03/05/06/07 fixtures — construct unchanged, and because trading resolves only against a docked
 * station's market (a no-op everywhere else) those fixtures also **step byte-identically** (the default
 * [com.orbitalfrontier.economy.TradeOrder.None] never moves credits or cargo).
 */
data class SimulationState(
    val tick: Int = 0,
    val ship: ShipKinematics = ShipKinematics(),
    val currentSector: SectorId = MvpSectorMap.START_SECTOR,
    val dockedStation: PoiId? = null,
    val cargo: Cargo = Cargo.empty(),
    val fieldDepletion: Map<PoiId, Map<ResourceType, Int>> = emptyMap(),
    val fuel: Fuel = Fuel.full(),
    val credits: Long = 0L,
)
