package com.orbitalfrontier.world

import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.station.StationRegistry

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
    /**
     * The live combat encounter (UC13). **Transient** — hostiles, projectiles and the combat RNG are
     * regenerated from the seeded encounter and are **never row-persisted** (a mid-combat save reloads
     * with combat cleared, ADR 0012). Defaults to [CombatState.NONE] so a fresh game, a pre-UC13 save
     * and every replay step that isn't fighting reads back with no encounter — and the default keeps
     * the snapshot byte-identical. Only the player's durable combat state ([lastDockedStation] here, and
     * each ship's [com.orbitalfrontier.ship.OwnedShip.sectionDamage]) survives a save.
     */
    val combat: CombatState = CombatState.NONE,
    /**
     * The [PoiId] of the station the player most recently docked at (UC13 AC#5) — the respawn point on
     * destruction. **Persisted** (unlike [dockedStation], which is null while in flight): it is the
     * *last* dock, set on each dock and retained after undocking, so a ship destroyed mid-flight knows
     * where to respawn. Null until the player's first dock (a brand-new game); on respawn
     * [com.orbitalfrontier.combat.Respawn] relocates the ship here with a cargo-loss penalty.
     */
    val lastDockedStation: PoiId? = null,
    /**
     * The player's per-faction reputation (UC14 AC#2) — save-wide standing with each faction, like
     * [credits]. Defaults to [Reputation.EMPTY] (every faction neutral) so a fresh game, and a pre-UC14
     * save with no reputation rows, reads back neutral and replays byte-identically. Only non-neutral
     * standings are persisted (the repository stores the non-zero rows, like cargo / field depletion);
     * mission turn-ins / courier failures fold the pure [com.orbitalfrontier.mission.Missions] result
     * back in, and reputation gates which mission offers are surfaced
     * ([com.orbitalfrontier.faction.ReputationGate]).
     */
    val reputation: Reputation = Reputation.EMPTY,
    /**
     * The player-owned stations (UC15 AC#1/#3) — save-wide, the station analogue of [fleet]. Defaults
     * to [StationRegistry.EMPTY] (no owned stations) so a fresh game, and every pre-UC15 save with no
     * `owned_station` rows, reads back with zero stations and replays byte-identically — the default
     * keeps the snapshot byte-identical for a pre-UC15 playthrough. The pure
     * [com.orbitalfrontier.station.StationBuilder] folds a build result back in; persistence stores one
     * `owned_station` row per station plus its `station_module` rows (additive v13 — ADR 0014). In the
     * MVP the registry only ever grows (stations are never removed, AC#3).
     */
    val stations: StationRegistry = StationRegistry.EMPTY,
    /**
     * Total wall-of-play time accumulated in this save, in whole seconds (UC38 AC#1) — shown per slot in
     * the save/load list. **Additive and defaulted to 0** so a fresh game, and every pre-UC38 save (whose
     * header has no play-time column until the v16 -> v17 migration backfills 0), reads back at 0 and the
     * snapshot stays byte-identical for a pre-UC38 playthrough. It is accumulated on the render thread from
     * the same per-frame `dt` the simulation advances by (so it tracks *play* time, not the time-free
     * deterministic sim), folded onto the autosave snapshot in [com.orbitalfrontier.screen.PlayScreen], and
     * persisted as `game_state.play_time_seconds`. It is **not** part of the pure deterministic state — the
     * record/replay harness (ADR 0006) never reads it — so it stays out of replay equality.
     */
    val playTimeSeconds: Long = 0L,
) {
    /** The active ship's kinematics (UC09: was the singleton `ship`). */
    val ship: ShipKinematics get() = fleet.active.kinematics

    /** The active ship's cargo hold (UC09: was the singleton `cargo`). */
    val cargo: Cargo get() = fleet.active.cargo

    /** The active ship's fuel tank (UC09: was the singleton `fuel`). */
    val fuel: Fuel get() = fleet.active.fuel
}
