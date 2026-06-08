package com.orbitalfrontier.world

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.ShipKinematics

/**
 * Immutable snapshot of the player's place in the world: which sector they are in, the **fleet** they
 * own (and which ship is active), and whether the active ship is docked (UC03 AC#5; UC05 AC#4; UC09).
 *
 * This is the production game-state value that persistence serializes. It is intentionally small and
 * pure (only domain types), so it composes into a larger save snapshot without dragging in engine or
 * serialization concerns.
 *
 * The [SectorWorld] graph itself is fixed authored data (rebuilt from [MvpSectorMap]); only the
 * mutable, per-player fields live here, so a save stays compact (store the position in the graph, not
 * a copy of the graph).
 *
 * **UC09 — fleet, not a singleton ship.** Where UC04–UC08 held a single ship's kinematics, cargo and
 * fuel directly, those now live on the **active ship** inside [fleet]: the player owns a list of
 * [com.orbitalfrontier.ship.OwnedShip]s, each with its own loadout/​cargo/​fuel, and [fleet] also
 * records the active one (UC09 AC#5/#6). The [ship]/[cargo]/[fuel] accessors below delegate to
 * `fleet.active.*` so existing read sites are unchanged; the field defaults to a single starter ship
 * ([Fleet.starter]) so an old call site or a migrated save reads back as the one starter ship.
 *
 * [dockedStation] is the [PoiId] of the station the active ship is docked at, or null when in flight
 * (the common case). [fieldDepletion] stores *remaining* units per [AsteroidField] id; an **absent**
 * field is pristine. [credits] is the player's single-currency wallet (UC08 AC#1) — save-wide (not
 * per-ship), defaulting to 0L (a *new game* seeds a starting balance separately in the bootstrap).
 *
 * Cargo and fuel **capacities** are ship stats reconstructed on load from the ship type + loadout
 * (see [com.orbitalfrontier.outfit.ShipStats]); only the contents/level are persisted.
 *
 * [revealedContacts] (UC10) is the set of [HiddenContact] ids the player has revealed by active
 * scanning. It is **save-wide** (not per-ship or per-sector — a contact's [PoiId] is globally unique
 * across the sector graph) and **monotonic**: a scan only ever adds ids ([Scanning.resolve]); revealed
 * contacts never re-hide, even on leaving range, and persist across save/reload (UC10 AC#4). Defaults
 * to empty — a fresh game has scanned nothing.
 *
 * [missions] (UC12) is the player's [MissionLog]. Persistence stores only its accepted/terminal
 * missions ([MissionLog.accepted]); the available offers are **not** persisted — they are regenerated
 * from the static authored world on load and filtered against the persisted ids (regenerate-and-filter,
 * ADR 0011). Defaults to [MissionLog.EMPTY] — a fresh game has no missions — so a pre-UC12 save reads
 * back with an empty log and replays byte-identically.
 */
data class WorldState(
    val currentSector: SectorId = MvpSectorMap.START_SECTOR,
    val fleet: Fleet = Fleet.starter(),
    val dockedStation: PoiId? = null,
    val fieldDepletion: Map<PoiId, Map<ResourceType, Int>> = emptyMap(),
    val credits: Long = 0L,
    val revealedContacts: Set<PoiId> = emptySet(),
    val missions: MissionLog = MissionLog.EMPTY,
) {
    /** The active ship's kinematics (UC09: was the singleton `ship`). */
    val ship: ShipKinematics get() = fleet.active.kinematics

    /** The active ship's cargo hold (UC09: was the singleton `cargo`). */
    val cargo: Cargo get() = fleet.active.cargo

    /** The active ship's fuel tank (UC09: was the singleton `fuel`). */
    val fuel: Fuel get() = fleet.active.fuel
}
