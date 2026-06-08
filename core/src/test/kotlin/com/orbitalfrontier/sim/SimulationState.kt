package com.orbitalfrontier.sim

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId

/**
 * Immutable snapshot of the whole simulated world at one tick — the value the pure [Simulation]
 * reads and produces each step, and the unit a replay asserts against (UC02 AC#5/#6).
 *
 * UC02 simulated ship movement only (the [tick] index and the ship's [ShipKinematics]); UC03 adds
 * [currentSector]; UC05 adds [dockedStation]; UC06 adds cargo + [fieldDepletion]; UC07 adds fuel;
 * UC08 adds [credits]; UC10 adds [revealedContacts]. Each new field has been **defaulted** so older
 * recorded playthroughs (whose serialized snapshots predate the field) still construct. Keep every
 * field a plain domain type (no serialization annotations) — the on-disk shape lives in a separate
 * DTO ([com.orbitalfrontier.playthrough.StateSnapshotDto]) so persistence concerns never leak in.
 *
 * **UC09 — fleet, not a singleton ship.** Where UC04–UC08 carried a single ship's kinematics, cargo
 * and fuel directly, those now live on the **active ship** inside [fleet] (mirroring the production
 * [com.orbitalfrontier.world.WorldState]). The player owns a list of
 * [com.orbitalfrontier.ship.OwnedShip]s, each with its own loadout/​cargo/​fuel; [fleet] also records
 * the active one (UC09 AC#5/#6). The [ship]/[cargo]/[fuel] accessors below delegate to `fleet.active.*`
 * so existing read sites (and the replay assertions) are unchanged, and the field defaults to a single
 * starter ship ([Fleet.starter]) — whose derived stats equal today's constants — so every pre-UC09
 * fixture constructs and **steps byte-identically** (the byte-identical contract, UC09 Stage A).
 */
data class SimulationState(
    val tick: Int = 0,
    val fleet: Fleet = Fleet.starter(),
    val currentSector: SectorId = MvpSectorMap.START_SECTOR,
    val dockedStation: PoiId? = null,
    val fieldDepletion: Map<PoiId, Map<ResourceType, Int>> = emptyMap(),
    val credits: Long = 0L,
    /**
     * The ids of no-transponder [com.orbitalfrontier.world.HiddenContact]s the player has uncovered by
     * active scanning (UC10 AC#4), mirroring the production [com.orbitalfrontier.world.WorldState
     * .revealedContacts]. Save-wide (a contact's [PoiId] is globally unique across the sector graph) and
     * **monotonic** — a scan only ever adds ids ([com.orbitalfrontier.world.Scanning.resolve]); revealed
     * contacts never re-hide. Defaults empty so every pre-UC10 fixture constructs and steps unchanged.
     */
    val revealedContacts: Set<PoiId> = emptySet(),
) {
    /** The active ship's kinematics (UC09: was the singleton `ship`). */
    val ship: ShipKinematics get() = fleet.active.kinematics

    /** The active ship's cargo hold (UC09: was the singleton `cargo`). */
    val cargo: Cargo get() = fleet.active.cargo

    /** The active ship's fuel tank (UC09: was the singleton `fuel`). */
    val fuel: Fuel get() = fleet.active.fuel
}
