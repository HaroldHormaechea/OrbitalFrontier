package com.orbitalfrontier.world

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.ship.ShipKinematics

/**
 * Immutable snapshot of the player's place in the world: which sector they are in, the ship's
 * spatial state, and whether the ship is docked (UC03 AC#5; UC05 AC#4).
 *
 * This is the production game-state value that persistence serializes — UC04 writes/reads it and
 * UC05 extends it with [dockedStation]. It is intentionally small and pure (only domain types), so
 * it composes into a larger save snapshot later without dragging in engine or serialization concerns.
 *
 * The [SectorWorld] graph itself is fixed authored data (rebuilt from [MvpSectorMap]); only the
 * mutable, per-player fields live here, so a save stays compact (store the position in the graph,
 * not a copy of the graph).
 *
 * [dockedStation] is the [PoiId] of the station the ship is currently docked at, or null when in
 * flight (the common case). It defaults to null so existing call sites and a migrated save with no
 * dock column read back as "in flight" (UC05 AC#4).
 *
 * [cargo] is the active ship's hold and [fieldDepletion] the per-field remaining deposits, both
 * added by UC06. Each defaults (empty cargo at [Cargo.DEFAULT_CAPACITY]; no depletion) so existing
 * call sites and a v3 save migrated to v4 read back as "empty hold, all fields pristine".
 * [fieldDepletion] stores *remaining* units per [AsteroidField] id; an **absent** field is pristine.
 * Cargo capacity is a ship stat reconstructed on load, not persisted (see [Cargo]).
 *
 * [fuel] is the active ship's fuel tank (UC07). It defaults to a full tank ([Fuel.full]) so existing
 * call sites and a v4 save migrated to v5 read back fully fuelled (the migration backfills the new
 * `ship.fuel` column with a full tank — never stranded). Like cargo capacity, the tank's capacity is
 * a ship stat reconstructed on load (see [Fuel]); only the level is persisted.
 *
 * [credits] is the player's single-currency wallet (UC08 AC#1) — the balance earned by selling
 * resources / mission rewards and spent on buying goods, upgrades, and refuelling. It is save-wide
 * (not per-ship), persisted on `game_state` (v6). It defaults to 0L so existing call sites and a v5
 * save migrated to v6 read back broke (the migration backfills the new `game_state.credits` column
 * with 0); a *new game* seeds a starting balance separately (see the game bootstrap), so the 0L
 * default is correct only for migrated saves, never a fresh one.
 */
data class WorldState(
    val currentSector: SectorId = MvpSectorMap.START_SECTOR,
    val ship: ShipKinematics = ShipKinematics(),
    val dockedStation: PoiId? = null,
    val cargo: Cargo = Cargo.empty(),
    val fieldDepletion: Map<PoiId, Map<ResourceType, Int>> = emptyMap(),
    val fuel: Fuel = Fuel.full(),
    val credits: Long = 0L,
)
