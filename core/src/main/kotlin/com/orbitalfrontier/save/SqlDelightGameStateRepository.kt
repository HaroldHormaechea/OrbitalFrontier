package com.orbitalfrontier.save

import com.orbitalfrontier.combat.SectionDamage
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarketState
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.outfit.JunkyardStock
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.outfit.UpgradeId
import com.orbitalfrontier.platform.Clock
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.ShipType
import com.orbitalfrontier.ship.ShipTypeId
import com.orbitalfrontier.station.OwnedStation
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.station.StationModuleId
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState

/**
 * SQLDelight-backed [GameStateRepository] + [SaveSlotRepository] (ADR 0003 / ADR 0026), mirroring
 * [SqlDelightSettingsRepository]'s pattern: constructor-injected generated [OrbitalFrontier] database +
 * [Logger] (DIP), all writes inside `transaction { }`, every failure caught + logged and never thrown
 * (graceful degradation, coding-guidelines § "Error handling").
 *
 * This class owns the single **Float ↔ Double** conversion at the persistence boundary: the model's
 * kinematics are `Float`, SQLite stores `REAL` (mapped to `Double`). `Float -> Double` is widening
 * and `Double -> Float` of that same value returns the original `Float` bit-for-bit, so a save →
 * reload round-trip is exact (UC04 AC#8). No engine/Box2D types are persisted — only the pure
 * [ShipKinematics] the body is re-seeded from on load (UC04 pitfall).
 *
 * **UC38 — save slots.** Every read/write is scoped to a [SlotId]: the single DB partitions every
 * game-state table by `slot_id` (ADR 0026), so loading / saving / deleting one slot never reads or
 * mutates another (slot isolation, UC38 AC#4). A [Clock] is injected (DIP) so a save stamps the slot's
 * `last_saved_epoch_millis` with real wall-clock time without `core` reading a platform clock directly
 * (the pure simulation stays time-free, ADR 0006). An autosave updates only the gameplay columns and the
 * autosave metadata — never the slot's player-chosen `name` (the name-clobber guard); renaming is the
 * separate [renameSlot]. The active-slot pointer (`meta.active_slot_id`) selects which slot the autosave
 * targets and `Continue` resumes (UC38 AC#3).
 *
 * **UC09 — the whole fleet (within a slot).** A slot holds **multiple ships** (one `ship` row each, each
 * with its own cargo + `ship_upgrade` rows); load reconstructs every [OwnedShip], rebuilds its [Loadout],
 * **re-derives** cargo/fuel/crew capacities from `type + loadout` via [ShipStats], and assembles a [Fleet]
 * with `game_state.active_ship_id` selecting the active one. Unknown ship type / upgrade id / resource /
 * faction / module degrade gracefully (skip + WARN), never crashing the load — "never stranded".
 */
class SqlDelightGameStateRepository(
    private val database: OrbitalFrontier,
    private val logger: Logger,
    private val clock: Clock,
) : GameStateRepository, SaveSlotRepository {
    private val queries get() = database.orbitalFrontierQueries

    override fun loadGameState(slot: SlotId): WorldState? {
        val slotId = slot.dbId
        return try {
            val header = queries.selectGameStateForSlot(slotId).executeAsOneOrNull() ?: return null
            val shipRows = queries.selectShipsForSlot(slotId).executeAsList()
            if (shipRows.isEmpty()) {
                logger.error(TAG, "Slot ${slot.value} header present but no ship rows; treating as no save")
                return null
            }

            val cargoByShip = loadCargoByShip(slotId)
            val upgradesByShip = loadUpgradesByShip(slotId)
            val sectionDamageByShip = loadSectionDamageByShip(slotId)

            val ships =
                shipRows
                    .map { row ->
                        val type = resolveShipType(row.ship_type)
                        val loadout = upgradesByShip[row.id] ?: Loadout.EMPTY
                        val cargoCapacity = ShipStats.cargoCapacity(type, loadout)
                        val fuelCapacity = ShipStats.fuelCapacity(type, loadout)
                        val crewCapacity = ShipStats.crewCapacity(type, loadout)
                        OwnedShip(
                            id = ShipId(row.id),
                            type = type,
                            kinematics =
                                ShipKinematics(
                                    position = Vec2(row.pos_x.toFloat(), row.pos_y.toFloat()),
                                    velocity = Vec2(row.vel_x.toFloat(), row.vel_y.toFloat()),
                                    headingRadians = row.heading.toFloat(),
                                    angularVelocity = row.ang_vel.toFloat(),
                                ),
                            // Cargo/fuel capacities are derived ship stats, not save data — re-derived
                            // above from type + loadout; only the contents/level are persisted.
                            cargo = Cargo(cargoByShip[row.id].orEmpty(), cargoCapacity),
                            // coerceIn guards a corrupt/over-capacity row so the Fuel(level in 0..capacity)
                            // invariant always holds — degrade gracefully, never crash.
                            fuel = Fuel(level = row.fuel.toFloat().coerceIn(0f, fuelCapacity), capacity = fuelCapacity),
                            loadout = loadout,
                            // Crew (UC11): a persisted count; capacity is derived (above). coerceIn guards a
                            // corrupt/over-capacity row so the OwnedShip(0 <= crew <= capacity) invariant holds.
                            crew = row.crew.toInt().coerceIn(0, crewCapacity),
                            // Section damage (UC13): per-section current HP, clamped to each section's
                            // derived max HP (type + loadout) so a stored value above the new max (after a
                            // refit since the save) degrades gracefully into range — never stranded.
                            sectionDamage = clampToDerivedMax(sectionDamageByShip[row.id].orEmpty(), type, loadout),
                        )
                    }
                    .sortedBy { it.id.value }

            val activeId = ShipId(header.active_ship_id)
            val fleet =
                if (ships.any { it.id == activeId }) {
                    Fleet(ships, activeId)
                } else {
                    logger.warn(
                        TAG,
                        "active_ship_id ${header.active_ship_id} not among slot ${slot.value} ships; defaulting to first",
                    )
                    Fleet(ships, ships.first().id)
                }

            WorldState(
                currentSector = SectorId(header.current_sector),
                fleet = fleet,
                // Null column (a v2 save migrated up, or a save written while in flight) -> not docked.
                dockedStation = header.docked_station_id?.let { PoiId(it) },
                // Field depletion: absent field = pristine; stored values are REMAINING units (UC06).
                fieldDepletion = loadFieldDepletion(slotId),
                // Credits (UC08): coerceAtLeast(0) guards a corrupt/negative row — never negative.
                credits = header.credits.coerceAtLeast(0),
                // Revealed hidden contacts (UC10): absent = still hidden; reveal is monotonic.
                revealedContacts = loadRevealedContacts(slotId),
                // Missions (UC12): only the accepted / terminal missions are persisted; the available
                // offers are regenerated from the static authored world on load (regenerate-and-filter).
                missions = MissionLog(available = emptyList(), accepted = loadMissions(slotId)),
                // Combat (UC13): transient — hostiles/projectiles/RNG are regenerated from the seeded
                // encounter, never persisted, so a load always starts with no live encounter (ADR 0012).
                combat = com.orbitalfrontier.combat.CombatState.NONE,
                // Last docked station (UC13): the respawn point. NULL column (a fresh / migrated v10 save)
                // -> never docked yet, so destruction leaves the player in place until their first dock.
                lastDockedStation = header.last_docked_station_id?.let { PoiId(it) },
                // Reputation (UC14): absent faction = neutral; only non-neutral standings are stored and
                // each is coerced into the params' bounds, an unknown faction slug skipped (WARN).
                reputation = loadReputation(slotId),
                // Owned stations (UC15): the player-built stations + their modules; an unknown module
                // slug is skipped (WARN). Empty for a fresh / migrated pre-UC15 save (ADR 0014).
                stations = loadStations(slotId),
                // Station-market pressure (UC46): the dynamic per-station supply/demand state; an unknown
                // resource slug is skipped (WARN), a zero pressure dropped. Empty for a fresh / migrated
                // pre-UC46 save (no station_market rows), so every station reads back at its authored base.
                marketState = loadStationMarketState(slotId),
                // Junkyard used-part depletion (UC47): the durable per-junkyard purchased counts; an unknown
                // upgrade slug is skipped (WARN), a zero/negative purchased dropped. Empty for a fresh /
                // migrated pre-UC47 save (no junkyard_stock rows), so every used part reads back at full baseline.
                junkyardStock = loadJunkyardStock(slotId),
                // Play time (UC38): the accumulated wall-of-play seconds shown per slot; coerced >= 0.
                playTimeSeconds = header.play_time_seconds.coerceAtLeast(0),
            )
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load slot ${slot.value}; treating as no save (New Game)", e)
            null
        }
    }

    override fun saveGameState(
        slot: SlotId,
        state: WorldState,
    ) {
        val slotId = slot.dbId
        try {
            // Header + every ship row written atomically: a failure rolls back all, so the previous
            // good save is never left half-overwritten (UC04 AC#3).
            queries.transaction {
                // Ensure the slot header row exists, establishing its player-facing name ONCE on first save
                // (a no-op if the slot already exists — so an autosave never resets the name). The two
                // no-DEFAULT columns (sector / active ship) are seeded here and re-set by updateSlotHeader.
                queries.insertSlotHeaderIfAbsent(
                    slot_id = slotId,
                    current_sector = state.currentSector.value,
                    active_ship_id = state.fleet.activeShipId.value,
                    name = defaultSlotName(slot),
                )
                // Update ONLY the gameplay columns + autosave metadata (last-saved time, play time) — never
                // `name` (the name-clobber guard, UC38). last-saved is the injected wall clock (UC38 AC#1).
                queries.updateSlotHeader(
                    current_sector = state.currentSector.value,
                    active_ship_id = state.fleet.activeShipId.value,
                    docked_station_id = state.dockedStation?.value,
                    credits = state.credits,
                    last_docked_station_id = state.lastDockedStation?.value,
                    last_saved_epoch_millis = clock.nowEpochMillis(),
                    play_time_seconds = state.playTimeSeconds.coerceAtLeast(0),
                    slot_id = slotId,
                )

                for (ship in state.fleet.ships) {
                    val kin = ship.kinematics
                    queries.upsertShip(
                        slot_id = slotId,
                        id = ship.id.value,
                        pos_x = kin.position.x.toDouble(),
                        pos_y = kin.position.y.toDouble(),
                        vel_x = kin.velocity.x.toDouble(),
                        vel_y = kin.velocity.y.toDouble(),
                        heading = kin.headingRadians.toDouble(),
                        ang_vel = kin.angularVelocity.toDouble(),
                        // Fuel level (UC07): same Float -> Double boundary as the kinematics above.
                        fuel = ship.fuel.level.toDouble(),
                        ship_type = ship.type.id.value,
                        // Crew count (UC11): a persisted per-ship count; capacity is not stored (derived).
                        crew = ship.crew.toLong(),
                    )

                    // Cargo: full-snapshot rewrite of this ship's rows (delete-then-plain-INSERT,
                    // minSdk-24-safe). Only non-zero amounts are stored; capacity is not persisted.
                    queries.deleteCargoForShip(slot_id = slotId, ship_id = ship.id.value)
                    for ((resource, units) in ship.cargo.contents) {
                        if (units > 0) {
                            queries.insertCargoEntry(
                                slot_id = slotId,
                                ship_id = ship.id.value,
                                resource = resource.name,
                                units = units.toLong(),
                            )
                        }
                    }

                    // Section damage (UC13): full-snapshot rewrite per ship (delete-then-INSERT), like
                    // cargo. An absent section is pristine, so only damaged sections (current HP stored)
                    // are written; an undamaged ship writes no rows. Max HP is derived, not persisted.
                    queries.deleteShipSectionDamageForShip(slot_id = slotId, ship_id = ship.id.value)
                    for ((section, currentHp) in ship.sectionDamage) {
                        queries.insertShipSectionDamage(
                            slot_id = slotId,
                            ship_id = ship.id.value,
                            section = section.name,
                            current_hp = currentHp.toLong(),
                        )
                    }

                    // Installed upgrades (UC09): full-snapshot rewrite per ship (delete-then-INSERT). A
                    // gap-tolerant loadout persists exactly the filled (category, slot_index) rows.
                    queries.deleteShipUpgradesForShip(slot_id = slotId, ship_id = ship.id.value)
                    for ((category, slots) in ship.loadout.slots) {
                        for ((slotIndex, upgradeId) in slots) {
                            queries.insertShipUpgrade(
                                slot_id = slotId,
                                ship_id = ship.id.value,
                                slot_category = category.name,
                                slot_index = slotIndex.toLong(),
                                upgrade_id = upgradeId.value,
                            )
                        }
                    }
                }

                // Field depletion: upsert every (field, resource) remaining amount (full snapshot).
                // Depletion is monotonic so we never delete; an untouched field has no rows (UC06).
                for ((fieldId, remaining) in state.fieldDepletion) {
                    for ((resource, units) in remaining) {
                        queries.upsertFieldDeposit(
                            slot_id = slotId,
                            field_id = fieldId.value,
                            resource = resource.name,
                            remaining_units = units.toLong(),
                        )
                    }
                }

                // Revealed hidden contacts (UC10): insert-or-ignore each id. Reveal is monotonic, so we
                // only ever add rows and never delete — an already-revealed contact stays revealed.
                for (contactId in state.revealedContacts) {
                    queries.insertRevealedContact(slot_id = slotId, contact_id = contactId.value)
                }

                // Missions (UC12): full-snapshot rewrite of the accepted / terminal missions for this slot
                // (delete-then-plain-INSERT, minSdk-24-safe). Available offers are NOT persisted — they
                // are regenerated from the static authored world on load (regenerate-and-filter, ADR 0011).
                queries.deleteAllMissionsForSlot(slotId)
                for (mission in state.missions.accepted) {
                    queries.insertMission(
                        slot_id = slotId,
                        id = mission.id.value,
                        type = mission.type.name,
                        source = mission.source.name,
                        status = mission.status.name,
                        reward_credits = mission.rewardCredits,
                        reward_resource = mission.rewardResource?.name,
                        reward_resource_units = mission.rewardResourceUnits.toLong(),
                        quota_resource = mission.quotaResource?.name,
                        quota_units = mission.quotaUnits.toLong(),
                        pickup = mission.pickup?.value,
                        destination = mission.destination?.value,
                        remaining_ticks = mission.remainingTicks.toLong(),
                        picked_up = if (mission.pickedUp) 1L else 0L,
                        // Faction attribution (UC14): the faction this mission credits/costs reputation
                        // to; NULL for a faction-less mission. The gate is not persisted (offers are).
                        faction_id = mission.factionId?.value,
                        // Bounty (UC41): the target encounter-zone (NULL for a non-bounty mission), the kill
                        // quota and the durable kill progress. Defaults (null / 0 / 0) for mining/courier.
                        target_zone_id = mission.targetZoneId,
                        kill_target = mission.killTarget.toLong(),
                        kill_progress = mission.killProgress.toLong(),
                    )
                }

                // Reputation (UC14): full-snapshot rewrite of the non-neutral per-faction standings for this
                // slot (delete-then-plain-INSERT, minSdk-24-safe), like the mission table. Only non-neutral
                // standings are stored — a faction at neutral (0) is simply absent.
                queries.deleteAllReputationForSlot(slotId)
                for ((faction, value) in state.reputation.byFaction) {
                    if (value != 0) {
                        queries.insertReputation(slot_id = slotId, faction_id = faction.value, value_ = value.toLong())
                    }
                }

                // Station-market pressure (UC46): full-snapshot rewrite of the non-zero per-(station,
                // resource) pressure for this slot (delete-then-plain-INSERT, minSdk-24-safe), exactly like
                // the reputation table. Only non-zero pressure is stored — a (station, resource) at base
                // (pressure 0) is simply absent — so a never-traded save writes no rows and reads back at
                // base prices (byte-identical to pre-UC46).
                queries.deleteAllStationMarketForSlot(slotId)
                for ((stationId, pressures) in state.marketState.pressureByStation) {
                    for ((resource, pressure) in pressures) {
                        if (pressure != 0) {
                            queries.insertStationMarketEntry(
                                slot_id = slotId,
                                station_id = stationId.value,
                                resource = resource.name,
                                pressure = pressure.toLong(),
                            )
                        }
                    }
                }

                // Junkyard used-part depletion (UC47): full-snapshot rewrite of the non-zero per-(junkyard,
                // upgrade) purchased counts for this slot (delete-then-plain-INSERT, minSdk-24-safe), exactly
                // like the station_market table. Only positive purchases are stored — an undepleted
                // (station, upgrade) is absent — so a never-bought-used save writes no rows and reads back at
                // full baseline (byte-identical to pre-UC47).
                queries.deleteAllJunkyardStockForSlot(slotId)
                for ((stationId, purchases) in state.junkyardStock.purchasedByStation) {
                    for ((upgradeId, purchased) in purchases) {
                        if (purchased > 0) {
                            queries.insertJunkyardStockEntry(
                                slot_id = slotId,
                                station_id = stationId.value,
                                upgrade_id = upgradeId.value,
                                purchased = purchased.toLong(),
                            )
                        }
                    }
                }

                // Owned stations (UC15): one upserted owned_station row per station + a full-snapshot
                // rewrite of its station_module rows (delete-then-INSERT, minSdk-24-safe), exactly like a
                // ship's upgrades. A station's module slot map is gap-tolerant, so only the filled
                // (slot_index) rows are written. Stations only grow, so rows are never deleted at the
                // station level (no delete-station query); the module rewrite is per-station (ADR 0014).
                for (station in state.stations.stations) {
                    queries.upsertOwnedStation(slot_id = slotId, id = station.id.value, sector = station.sector.value)
                    queries.deleteStationModulesForStation(slot_id = slotId, station_id = station.id.value)
                    for ((slotIndex, moduleId) in station.modules) {
                        queries.insertStationModule(
                            slot_id = slotId,
                            station_id = station.id.value,
                            slot_index = slotIndex.toLong(),
                            module_type = moduleId.value,
                        )
                    }
                }
            }
            logger.info(
                TAG,
                "Saved slot ${slot.value}: sector=${state.currentSector.value}, ships=${state.fleet.ships.size}, " +
                    "active=${state.fleet.activeShipId.value}",
            )
        } catch (e: Exception) {
            // Graceful degradation: keep the last good save, log, do not crash the app.
            logger.error(TAG, "Failed to persist slot ${slot.value}; last good save kept", e)
        }
    }

    override fun hasSave(slot: SlotId): Boolean {
        return try {
            queries.hasSlot(slot.dbId).executeAsOne()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to query slot ${slot.value} presence; treating as no save", e)
            false
        }
    }

    override fun clearSave(slot: SlotId) = wipeSlot(slot, "Cleared")

    // ----- SaveSlotRepository (UC38) -----------------------------------------------------------

    override fun listSlots(): List<SaveSlotSummary> {
        val occupied =
            try {
                queries.listSlots().executeAsList().associate { row ->
                    val id = SlotId(row.slot_id.toInt())
                    id to
                        SaveSlotSummary.Occupied(
                            slotId = id,
                            name = row.name,
                            lastSavedEpochMillis = row.last_saved_epoch_millis,
                            credits = row.credits.coerceAtLeast(0),
                            sector = SectorId(row.current_sector),
                            playTimeSeconds = row.play_time_seconds.coerceAtLeast(0),
                        )
                }
            } catch (e: Exception) {
                logger.error(TAG, "Failed to list slots; treating all as empty", e)
                emptyMap()
            }
        // Fill EVERY configured slot index: an occupied row where present, an Empty placeholder otherwise,
        // in ascending slot order (UC38 AC#1). A row whose slot_id is out of the configured range is ignored.
        return SaveSlots.ALL.map { id -> occupied[id] ?: SaveSlotSummary.Empty(id) }
    }

    override fun deleteSlot(slot: SlotId) = wipeSlot(slot, "Deleted")

    override fun renameSlot(
        slot: SlotId,
        name: String,
    ) {
        try {
            queries.setSlotName(name = name, slot_id = slot.dbId)
            logger.info(TAG, "Renamed slot ${slot.value} to '$name'")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to rename slot ${slot.value}", e)
        }
    }

    override fun activeSlot(): SlotId {
        return try {
            val value = queries.selectActiveSlot().executeAsOneOrNull() ?: return SlotId.LEGACY
            val id = SlotId(value.toInt())
            if (SaveSlots.isValid(id)) id else SlotId.LEGACY
        } catch (e: Exception) {
            logger.error(TAG, "Failed to read active slot; defaulting to legacy", e)
            SlotId.LEGACY
        }
    }

    override fun setActiveSlot(slot: SlotId) {
        try {
            queries.setActiveSlot(slot.dbId)
            logger.info(TAG, "Active slot set to ${slot.value}")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to set active slot to ${slot.value}", e)
        }
    }

    /**
     * Atomically clear one slot's durable game-state rows (shared by [clearSave] and [deleteSlot]). Every
     * non-meta / non-settings table is wiped for this slot in ONE transaction, leaving `meta` / `settings`
     * and **every other slot** intact (UC38 AC#4). Corruption-safe — a failure is logged, never thrown.
     */
    private fun wipeSlot(
        slot: SlotId,
        verb: String,
    ) {
        val slotId = slot.dbId
        try {
            queries.transaction {
                queries.deleteGameStateForSlot(slotId)
                queries.deleteShipsForSlot(slotId)
                queries.deleteAllShipUpgradesForSlot(slotId)
                queries.deleteAllCargoForSlot(slotId)
                queries.deleteAllShipSectionDamageForSlot(slotId)
                queries.deleteAllFieldDepositsForSlot(slotId)
                queries.deleteAllRevealedContactsForSlot(slotId)
                queries.deleteAllMissionsForSlot(slotId)
                queries.deleteAllReputationForSlot(slotId)
                queries.deleteAllStationMarketForSlot(slotId)
                queries.deleteAllJunkyardStockForSlot(slotId)
                queries.deleteAllOwnedStationsForSlot(slotId)
                queries.deleteAllStationModulesForSlot(slotId)
            }
            logger.info(TAG, "$verb slot ${slot.value} (settings + meta kept)")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to clear slot ${slot.value}; last good save kept", e)
        }
    }

    /** Default player-facing name for a slot created on first save: the legacy slot is "Autosave". */
    private fun defaultSlotName(slot: SlotId): String = if (slot == SlotId.LEGACY) "Autosave" else "Save ${slot.value + 1}"

    /**
     * Group a slot's ship section-damage rows by ship id (UC13): section name -> current HP. An unknown
     * section name (enum changed) is skipped with a WARN — the rest of the ship's damage still loads.
     */
    private fun loadSectionDamageByShip(slotId: Long): Map<Long, SectionDamage> {
        val byShip = LinkedHashMap<Long, LinkedHashMap<ShipSection, Int>>()
        for (entry in queries.selectAllShipSectionDamageForSlot(slotId).executeAsList()) {
            val section = parseSection(entry.section) ?: continue
            byShip.getOrPut(entry.ship_id) { LinkedHashMap() }[section] = entry.current_hp.toInt()
        }
        return byShip
    }

    /**
     * Re-canonicalise persisted section damage against the ship's **derived** max HP (UC13): coerce each
     * stored current HP into `0..maxHp` and drop any section now at/above full (absent = pristine), so a
     * value above the current fit's max (after a refit since the save) degrades gracefully into range.
     */
    private fun clampToDerivedMax(
        damage: SectionDamage,
        type: ShipType,
        loadout: Loadout,
    ): SectionDamage {
        if (damage.isEmpty()) return SectionDamages.PRISTINE
        var result: SectionDamage = SectionDamages.PRISTINE
        for ((section, hp) in damage) {
            val maxHp = ShipStats.sectionHp(type, loadout, section)
            result = SectionDamages.setHp(result, section, hp, maxHp)
        }
        return result
    }

    /** Map a persisted section name to its [ShipSection], or null (logged) if unknown. */
    private fun parseSection(name: String): ShipSection? {
        val section = ShipSection.entries.firstOrNull { it.name == name }
        if (section == null) logger.warn(TAG, "Skipping unknown persisted section '$name' (enum changed?)")
        return section
    }

    /** Group a slot's cargo rows by ship id (resource name -> units; unknown names skipped). */
    private fun loadCargoByShip(slotId: Long): Map<Long, Map<ResourceType, Int>> {
        val byShip = LinkedHashMap<Long, LinkedHashMap<ResourceType, Int>>()
        for (entry in queries.selectAllCargoForSlot(slotId).executeAsList()) {
            val resource = parseResource(entry.resource) ?: continue
            byShip.getOrPut(entry.ship_id) { LinkedHashMap() }[resource] = entry.units.toInt()
        }
        return byShip
    }

    /**
     * Group a slot's upgrade rows by ship id into a [Loadout] each. An unknown slot-category name or an
     * [UpgradeId] the [UpgradeCatalog] no longer knows is skipped with a WARN (the rest still loads).
     */
    private fun loadUpgradesByShip(slotId: Long): Map<Long, Loadout> {
        val slotsByShip = LinkedHashMap<Long, LinkedHashMap<SlotCategory, LinkedHashMap<Int, UpgradeId>>>()
        for (entry in queries.selectAllShipUpgradesForSlot(slotId).executeAsList()) {
            val category = parseSlotCategory(entry.slot_category) ?: continue
            val upgradeId = parseUpgrade(entry.upgrade_id) ?: continue
            slotsByShip
                .getOrPut(entry.ship_id) { LinkedHashMap() }
                .getOrPut(category) { LinkedHashMap() }[entry.slot_index.toInt()] = upgradeId
        }
        return slotsByShip.mapValues { (_, slots) -> Loadout(slots) }
    }

    /**
     * Reconstruct a slot's per-field remaining-deposit map from `field_deposit`. Grouped by field id; a
     * field with no rows is simply absent (pristine). Unrecognised resource names are skipped (WARN).
     */
    private fun loadFieldDepletion(slotId: Long): Map<PoiId, Map<ResourceType, Int>> {
        val byField = LinkedHashMap<PoiId, LinkedHashMap<ResourceType, Int>>()
        for (entry in queries.selectFieldDepositsForSlot(slotId).executeAsList()) {
            val resource = parseResource(entry.resource) ?: continue
            byField.getOrPut(PoiId(entry.field_id)) { LinkedHashMap() }[resource] = entry.remaining_units.toInt()
        }
        return byField
    }

    /**
     * Reconstruct a slot's revealed-hidden-contact set from `revealed_contact` (UC10). Each row's id is a
     * [PoiId]; an id whose contact the authored map no longer contains is kept harmlessly. Empty when
     * nothing has been scanned.
     */
    private fun loadRevealedContacts(slotId: Long): Set<PoiId> {
        val ids = LinkedHashSet<PoiId>()
        for (contactId in queries.selectRevealedContactsForSlot(slotId).executeAsList()) {
            ids.add(PoiId(contactId))
        }
        return ids
    }

    /**
     * Reconstruct a slot's accepted / terminal missions from the `mission` table (UC12). Each row maps to a
     * [Mission]; a row whose enum / resource name no longer resolves is **skipped with a WARN** (the rest
     * still load — "never stranded"). Available offers are not stored — they are regenerated on load.
     */
    private fun loadMissions(slotId: Long): List<Mission> {
        val missions = ArrayList<Mission>()
        for (row in queries.selectAllMissionsForSlot(slotId).executeAsList()) {
            val type = parseMissionType(row.type) ?: continue
            val source = parseMissionSource(row.source) ?: continue
            val status = parseMissionStatus(row.status) ?: continue

            // A mining mission's quota resource must resolve; an unknown one is a skip (WARN).
            val quotaResource =
                if (type == MissionType.MINING) {
                    parseResource(row.quota_resource ?: "") ?: continue
                } else {
                    row.quota_resource?.let { parseResource(it) }
                }
            // The optional reward resource is best-effort: an unknown one degrades to "no resource bonus".
            val rewardResource = row.reward_resource?.let { parseResource(it) }

            // Bounty (UC41): coerce the persisted kill quota/progress against the Mission(killProgress in
            // 0..killTarget) invariant, so a corrupt/negative row never throws on load (best-effort, like
            // the credit/quota guards above).
            val killTarget = row.kill_target.toInt().coerceAtLeast(0)
            val killProgress = row.kill_progress.toInt().coerceIn(0, killTarget)

            missions +=
                Mission(
                    id = MissionId(row.id),
                    type = type,
                    source = source,
                    status = status,
                    // coerceAtLeast(0) guards a corrupt/negative row against the Mission(>= 0) invariants.
                    rewardCredits = row.reward_credits.coerceAtLeast(0),
                    rewardResource = rewardResource,
                    rewardResourceUnits = row.reward_resource_units.toInt().coerceAtLeast(0),
                    quotaResource = quotaResource,
                    quotaUnits = row.quota_units.toInt().coerceAtLeast(0),
                    // Unknown station ids are kept harmlessly (they resolve to nothing when looked up).
                    pickup = row.pickup?.let { PoiId(it) },
                    destination = row.destination?.let { PoiId(it) },
                    remainingTicks = row.remaining_ticks.toInt().coerceAtLeast(0),
                    pickedUp = row.picked_up != 0L,
                    // Faction attribution (UC14): best-effort — an unknown faction slug degrades to "no
                    // attribution" (the mission still loads; it just grants no reputation on turn-in).
                    factionId = parseFaction(row.faction_id),
                    // Bounty (UC41): the target zone + kill quota/progress (null / 0 / 0 for a non-bounty).
                    targetZoneId = row.target_zone_id,
                    killTarget = killTarget,
                    killProgress = killProgress,
                )
        }
        return missions
    }

    /**
     * Reconstruct a slot's per-faction reputation from the `reputation` table (UC14). Each row's value is
     * coerced into the [ReputationParams] bounds; a faction whose slug the [Factions] catalog no longer
     * knows is **skipped with a WARN**. A standing that coerces to neutral (0) is dropped.
     */
    private fun loadReputation(slotId: Long): Reputation {
        val params = ReputationParams()
        val standings = LinkedHashMap<FactionId, Int>()
        for (row in queries.selectReputationForSlot(slotId).executeAsList()) {
            val faction = parseFaction(row.faction_id) ?: continue
            val value = row.value_.toInt().coerceIn(params.min, params.max)
            if (value != 0) standings[faction] = value
        }
        return Reputation(standings)
    }

    /**
     * Reconstruct a slot's dynamic station-market pressure from the `station_market` table (UC46). Each
     * row is a net signed pressure for one (station, resource); a row whose resource slug no longer
     * resolves is **skipped with a WARN** ([parseResource]), and a zero pressure is dropped (canonical:
     * only non-zero pressure is meaningful). Empty for a fresh / migrated pre-UC46 save (no rows) → every
     * station reads back at its authored base price.
     */
    private fun loadStationMarketState(slotId: Long): StationMarketState {
        val byStation = LinkedHashMap<PoiId, LinkedHashMap<ResourceType, Int>>()
        for (row in queries.selectStationMarketForSlot(slotId).executeAsList()) {
            val resource = parseResource(row.resource) ?: continue
            val pressure = row.pressure.toInt()
            if (pressure == 0) continue
            byStation.getOrPut(PoiId(row.station_id)) { LinkedHashMap() }[resource] = pressure
        }
        return if (byStation.isEmpty()) StationMarketState.EMPTY else StationMarketState(byStation)
    }

    /**
     * Reconstruct a slot's junkyard used-part depletion from the `junkyard_stock` table (UC47). Each row
     * is the cumulative count the player has bought of one used part at one junkyard; a row whose upgrade
     * slug no longer resolves in the [UpgradeCatalog] is **skipped with a WARN** (so a removed part never
     * strands a save), and a zero/negative purchased is dropped (canonical: only positive depletion is
     * meaningful). Empty for a fresh / migrated pre-UC47 save (no rows) → every used part reads back at its
     * full baseline. The baseline itself is NOT stored — it is recomputed on demand
     * ([com.orbitalfrontier.outfit.UsedPartPricing.baselineStock]), so `available = baseline − purchased`.
     */
    private fun loadJunkyardStock(slotId: Long): JunkyardStock {
        val byStation = LinkedHashMap<PoiId, LinkedHashMap<UpgradeId, Int>>()
        for (row in queries.selectJunkyardStockForSlot(slotId).executeAsList()) {
            val purchased = row.purchased.toInt()
            if (purchased <= 0) continue
            if (UpgradeCatalog.MVP.upgrade(UpgradeId(row.upgrade_id)) == null) {
                logger.warn(TAG, "Slot $slotId: junkyard_stock references unknown upgrade '${row.upgrade_id}'; skipping")
                continue
            }
            byStation.getOrPut(PoiId(row.station_id)) { LinkedHashMap() }[UpgradeId(row.upgrade_id)] = purchased
        }
        return if (byStation.isEmpty()) JunkyardStock.EMPTY else JunkyardStock(byStation)
    }

    /**
     * Reconstruct a slot's [StationRegistry] from `owned_station` + `station_module` (UC15). Each station's
     * modules are grouped by station id (slot index -> module slug); an unknown module slug is **skipped
     * with a WARN**. Stations are sorted by id for a deterministic order. Empty for a fresh / pre-UC15 save.
     */
    private fun loadStations(slotId: Long): StationRegistry {
        val modulesByStation = loadStationModulesByStation(slotId)
        val stations =
            queries.selectAllOwnedStationsForSlot(slotId).executeAsList()
                .map { row ->
                    OwnedStation(
                        id = StationId(row.id),
                        sector = SectorId(row.sector),
                        modules = modulesByStation[row.id] ?: emptyMap(),
                    )
                }
                .sortedBy { it.id.value }
        return StationRegistry(stations)
    }

    /**
     * Group a slot's station-module rows by station id (UC15): slot index -> [StationModuleId]. A module
     * slug the [StationModuleCatalog] no longer knows is skipped with a WARN (the rest still load).
     */
    private fun loadStationModulesByStation(slotId: Long): Map<Long, Map<Int, StationModuleId>> {
        val byStation = LinkedHashMap<Long, LinkedHashMap<Int, StationModuleId>>()
        for (entry in queries.selectAllStationModulesForSlot(slotId).executeAsList()) {
            val moduleId = parseStationModule(entry.module_type) ?: continue
            byStation.getOrPut(entry.station_id) { LinkedHashMap() }[entry.slot_index.toInt()] = moduleId
        }
        return byStation
    }

    /** Map a persisted station-module slug to a catalogued [StationModuleId], or null (logged) if unknown. */
    private fun parseStationModule(slug: String): StationModuleId? {
        if (slug.isNotBlank()) {
            val moduleId = StationModuleId(slug)
            if (StationModuleCatalog.MVP.module(moduleId) != null) return moduleId
        }
        logger.warn(TAG, "Skipping unknown persisted station module '$slug' (catalog changed?)")
        return null
    }

    /**
     * Map a persisted faction slug to a catalogued [FactionId] (UC14), or null (logged) when it is null /
     * blank / not in the [Factions] catalog (an evolved/removed faction) — best-effort, never stranded.
     */
    private fun parseFaction(slug: String?): FactionId? {
        if (slug.isNullOrBlank()) return null
        val id = FactionId(slug)
        if (Factions.byId(id) != null) return id
        logger.warn(TAG, "Skipping unknown persisted faction '$slug' (catalog changed?)")
        return null
    }

    /** Map a persisted mission-type name to its [MissionType], or null (logged) if unknown. */
    private fun parseMissionType(name: String): MissionType? {
        val type = MissionType.entries.firstOrNull { it.name == name }
        if (type == null) logger.warn(TAG, "Skipping mission with unknown type '$name' (enum changed?)")
        return type
    }

    /** Map a persisted mission-source name to its [MissionSource], or null (logged) if unknown. */
    private fun parseMissionSource(name: String): MissionSource? {
        val source = MissionSource.entries.firstOrNull { it.name == name }
        if (source == null) logger.warn(TAG, "Skipping mission with unknown source '$name' (enum changed?)")
        return source
    }

    /** Map a persisted mission-status name to its [MissionStatus], or null (logged) if unknown. */
    private fun parseMissionStatus(name: String): MissionStatus? {
        val status = MissionStatus.entries.firstOrNull { it.name == name }
        if (status == null) logger.warn(TAG, "Skipping mission with unknown status '$name' (enum changed?)")
        return status
    }

    /** Resolve a persisted ship-type slug to a [ShipType]; an unknown slug degrades to the starter (WARN). */
    private fun resolveShipType(slug: String): ShipType {
        if (slug.isNotBlank()) {
            ShipRoster.byId(ShipTypeId(slug))?.let { return it }
        }
        logger.warn(TAG, "Skipping unknown persisted ship_type '$slug'; using starter type")
        return ShipRoster.STARTER
    }

    /** Map a persisted slot-category name to its [SlotCategory], or null (logged) if unknown. */
    private fun parseSlotCategory(name: String): SlotCategory? {
        val category = SlotCategory.entries.firstOrNull { it.name == name }
        if (category == null) {
            logger.warn(TAG, "Skipping unknown persisted slot_category '$name' (enum changed?)")
        }
        return category
    }

    /** Map a persisted upgrade id to a catalogued [UpgradeId], or null (logged) if not catalogued. */
    private fun parseUpgrade(id: String): UpgradeId? {
        if (id.isNotBlank()) {
            val upgradeId = UpgradeId(id)
            if (UpgradeCatalog.MVP.upgrade(upgradeId) != null) return upgradeId
        }
        logger.warn(TAG, "Skipping unknown persisted upgrade_id '$id' (catalog changed?)")
        return null
    }

    /** Map a persisted resource name to its [ResourceType], or null (logged) if it is unknown. */
    private fun parseResource(name: String): ResourceType? {
        val resource = ResourceType.entries.firstOrNull { it.name == name }
        if (resource == null) {
            logger.warn(TAG, "Skipping unknown persisted resource '$name' (enum changed?)")
        }
        return resource
    }

    /** The SQLite `slot_id` (INTEGER -> Long) for a [SlotId] — the single Int -> Long conversion at the boundary. */
    private val SlotId.dbId: Long get() = value.toLong()

    private companion object {
        const val TAG = "Save"
    }
}
