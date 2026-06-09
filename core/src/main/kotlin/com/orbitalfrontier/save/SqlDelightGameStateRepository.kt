package com.orbitalfrontier.save

import com.orbitalfrontier.combat.SectionDamage
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
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
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.outfit.UpgradeId
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
 * SQLDelight-backed [GameStateRepository] (ADR 0003), mirroring [SqlDelightSettingsRepository]'s
 * pattern: constructor-injected generated [OrbitalFrontier] database + [Logger] (DIP), all writes
 * inside `transaction { }`, every failure caught + logged and never thrown (graceful degradation,
 * coding-guidelines § "Error handling").
 *
 * This class owns the single **Float ↔ Double** conversion at the persistence boundary: the model's
 * kinematics are `Float`, SQLite stores `REAL` (mapped to `Double`). `Float -> Double` is widening
 * and `Double -> Float` of that same value returns the original `Float` bit-for-bit, so a save →
 * reload round-trip is exact (UC04 AC#8). No engine/Box2D types are persisted — only the pure
 * [ShipKinematics] the body is re-seeded from on load (UC04 pitfall).
 *
 * **UC09 — the whole fleet.** A save holds **multiple ships** (one `ship` row each, each with its own
 * cargo + `ship_upgrade` rows); load reconstructs every [OwnedShip], rebuilds its [Loadout] from its
 * upgrade rows, **re-derives** cargo/fuel capacities from `type + loadout` via [ShipStats] (so a save
 * never pins a stale capacity), and assembles a [Fleet] with `game_state.active_ship_id` selecting the
 * active one. Save writes every ship's kinematics/type/cargo/upgrades in one transaction. Unknown
 * ship type / upgrade id / resource name degrade gracefully (skip + WARN), never crashing the load —
 * "never stranded". In the MVP a fleet only grows, so ship rows are only ever added, never deleted.
 */
class SqlDelightGameStateRepository(
    private val database: OrbitalFrontier,
    private val logger: Logger,
) : GameStateRepository {
    private val queries get() = database.orbitalFrontierQueries

    override fun loadGameState(): WorldState? {
        return try {
            val header = queries.selectGameState().executeAsOneOrNull() ?: return null
            val shipRows = queries.selectAllShips().executeAsList()
            if (shipRows.isEmpty()) {
                logger.error(TAG, "Save header present but no ship rows; treating as no save (New Game)")
                return null
            }

            val cargoByShip = loadCargoByShip()
            val upgradesByShip = loadUpgradesByShip()
            val sectionDamageByShip = loadSectionDamageByShip()

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
                        "active_ship_id ${header.active_ship_id} not among owned ships; defaulting to first",
                    )
                    Fleet(ships, ships.first().id)
                }

            WorldState(
                currentSector = SectorId(header.current_sector),
                fleet = fleet,
                // Null column (a v2 save migrated up, or a save written while in flight) -> not docked.
                dockedStation = header.docked_station_id?.let { PoiId(it) },
                // Field depletion: absent field = pristine; stored values are REMAINING units (UC06).
                fieldDepletion = loadFieldDepletion(),
                // Credits (UC08): coerceAtLeast(0) guards a corrupt/negative row — never negative.
                credits = header.credits.coerceAtLeast(0),
                // Revealed hidden contacts (UC10): absent = still hidden; reveal is monotonic.
                revealedContacts = loadRevealedContacts(),
                // Missions (UC12): only the accepted / terminal missions are persisted; the available
                // offers are regenerated from the static authored world on load (regenerate-and-filter).
                missions = MissionLog(available = emptyList(), accepted = loadMissions()),
                // Combat (UC13): transient — hostiles/projectiles/RNG are regenerated from the seeded
                // encounter, never persisted, so a load always starts with no live encounter (ADR 0012).
                combat = com.orbitalfrontier.combat.CombatState.NONE,
                // Last docked station (UC13): the respawn point. NULL column (a fresh / migrated v10 save)
                // -> never docked yet, so destruction leaves the player in place until their first dock.
                lastDockedStation = header.last_docked_station_id?.let { PoiId(it) },
                // Reputation (UC14): absent faction = neutral; only non-neutral standings are stored and
                // each is coerced into the params' bounds, an unknown faction slug skipped (WARN).
                reputation = loadReputation(),
                // Owned stations (UC15): the player-built stations + their modules; an unknown module
                // slug is skipped (WARN). Empty for a fresh / migrated pre-UC15 save (ADR 0014).
                stations = loadStations(),
            )
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load game state; treating as no save (New Game)", e)
            null
        }
    }

    override fun saveGameState(state: WorldState) {
        try {
            // Header + every ship row written atomically: a failure rolls back all, so the previous
            // good save is never left half-overwritten (UC04 AC#3).
            queries.transaction {
                queries.upsertGameState(
                    current_sector = state.currentSector.value,
                    active_ship_id = state.fleet.activeShipId.value,
                    docked_station_id = state.dockedStation?.value,
                    credits = state.credits,
                    // Last docked station (UC13): the respawn point, persisted (null until the first dock).
                    last_docked_station_id = state.lastDockedStation?.value,
                )

                for (ship in state.fleet.ships) {
                    val kin = ship.kinematics
                    queries.upsertShip(
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
                    queries.deleteCargoForShip(ship.id.value)
                    for ((resource, units) in ship.cargo.contents) {
                        if (units > 0) {
                            queries.insertCargoEntry(
                                ship_id = ship.id.value,
                                resource = resource.name,
                                units = units.toLong(),
                            )
                        }
                    }

                    // Section damage (UC13): full-snapshot rewrite per ship (delete-then-INSERT), like
                    // cargo. An absent section is pristine, so only damaged sections (current HP stored)
                    // are written; an undamaged ship writes no rows. Max HP is derived, not persisted.
                    queries.deleteShipSectionDamageForShip(ship.id.value)
                    for ((section, currentHp) in ship.sectionDamage) {
                        queries.insertShipSectionDamage(
                            ship_id = ship.id.value,
                            section = section.name,
                            current_hp = currentHp.toLong(),
                        )
                    }

                    // Installed upgrades (UC09): full-snapshot rewrite per ship (delete-then-INSERT). A
                    // gap-tolerant loadout persists exactly the filled (category, slot_index) rows.
                    queries.deleteShipUpgradesForShip(ship.id.value)
                    for ((category, slots) in ship.loadout.slots) {
                        for ((slotIndex, upgradeId) in slots) {
                            queries.insertShipUpgrade(
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
                            field_id = fieldId.value,
                            resource = resource.name,
                            remaining_units = units.toLong(),
                        )
                    }
                }

                // Revealed hidden contacts (UC10): insert-or-ignore each id. Reveal is monotonic, so we
                // only ever add rows and never delete — an already-revealed contact stays revealed.
                for (contactId in state.revealedContacts) {
                    queries.insertRevealedContact(contact_id = contactId.value)
                }

                // Missions (UC12): full-snapshot rewrite of the accepted / terminal missions
                // (delete-then-plain-INSERT, minSdk-24-safe). Available offers are NOT persisted — they
                // are regenerated from the static authored world on load (regenerate-and-filter, ADR 0011).
                queries.deleteAllMissions()
                for (mission in state.missions.accepted) {
                    queries.insertMission(
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
                    )
                }

                // Reputation (UC14): full-snapshot rewrite of the non-neutral per-faction standings
                // (delete-then-plain-INSERT, minSdk-24-safe), like the mission table. Only non-neutral
                // standings are stored — a faction at neutral (0) is simply absent (the Reputation map
                // already holds only non-neutral entries, so this writes exactly the non-zero rows).
                queries.deleteAllReputation()
                for ((faction, value) in state.reputation.byFaction) {
                    if (value != 0) {
                        queries.insertReputation(faction_id = faction.value, value_ = value.toLong())
                    }
                }

                // Owned stations (UC15): one upserted owned_station row per station + a full-snapshot
                // rewrite of its station_module rows (delete-then-INSERT, minSdk-24-safe), exactly like a
                // ship's upgrades. A station's module slot map is gap-tolerant, so only the filled
                // (slot_index) rows are written. Stations only grow, so rows are never deleted at the
                // station level (no delete-station query); the module rewrite is per-station (ADR 0014).
                for (station in state.stations.stations) {
                    queries.upsertOwnedStation(id = station.id.value, sector = station.sector.value)
                    queries.deleteStationModulesForStation(station.id.value)
                    for ((slotIndex, moduleId) in station.modules) {
                        queries.insertStationModule(
                            station_id = station.id.value,
                            slot_index = slotIndex.toLong(),
                            module_type = moduleId.value,
                        )
                    }
                }
            }
            logger.info(
                TAG,
                "Saved game state: sector=${state.currentSector.value}, ships=${state.fleet.ships.size}, " +
                    "active=${state.fleet.activeShipId.value}",
            )
        } catch (e: Exception) {
            // Graceful degradation: keep the last good save, log, do not crash the app.
            logger.error(TAG, "Failed to persist game state; last good save kept", e)
        }
    }

    override fun hasSave(): Boolean {
        return try {
            queries.hasGameState().executeAsOne()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to query save presence; treating as no save", e)
            false
        }
    }

    override fun clearSave() {
        try {
            // Every durable game-state table is cleared in ONE transaction so a wipe is atomic — a
            // failure rolls back all of it, leaving the last good save intact (mirrors saveGameState's
            // all-or-nothing guarantee, UC04 AC#3). `meta` (save-format version) and `settings`
            // (handedness) are deliberately NOT touched: the wipe resets progress only (UC21).
            queries.transaction {
                queries.deleteGameState()
                queries.deleteAllShips()
                queries.deleteAllShipUpgrades()
                queries.deleteAllCargo()
                queries.deleteAllShipSectionDamage()
                queries.deleteAllFieldDeposits()
                queries.deleteAllRevealedContacts()
                queries.deleteAllMissions()
                queries.deleteAllReputation()
                queries.deleteAllOwnedStations()
                queries.deleteAllStationModules()
            }
            logger.info(TAG, "Cleared save (settings + meta kept)")
        } catch (e: Exception) {
            // Graceful degradation: keep whatever was there, log, do not crash the app.
            logger.error(TAG, "Failed to clear save; last good save kept", e)
        }
    }

    /**
     * Group every ship's persisted section-damage rows by ship id (UC13): section name -> current HP. An
     * unknown section name (enum changed) is skipped with a WARN — the rest of the ship's damage still
     * loads (never stranded). Stored values are raw current HP; the caller clamps them to the derived max.
     */
    private fun loadSectionDamageByShip(): Map<Long, SectionDamage> {
        val byShip = LinkedHashMap<Long, LinkedHashMap<ShipSection, Int>>()
        for (entry in queries.selectAllShipSectionDamage().executeAsList()) {
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

    /** Group every ship's persisted cargo rows by ship id (resource name -> units; unknown names skipped). */
    private fun loadCargoByShip(): Map<Long, Map<ResourceType, Int>> {
        val byShip = LinkedHashMap<Long, LinkedHashMap<ResourceType, Int>>()
        for (entry in queries.selectAllCargo().executeAsList()) {
            val resource = parseResource(entry.resource) ?: continue
            byShip.getOrPut(entry.ship_id) { LinkedHashMap() }[resource] = entry.units.toInt()
        }
        return byShip
    }

    /**
     * Group every ship's persisted upgrade rows by ship id into a [Loadout] each. An unknown
     * slot-category name or an [UpgradeId] the [UpgradeCatalog] no longer knows is skipped with a WARN
     * (the rest of the loadout still loads — "never stranded").
     */
    private fun loadUpgradesByShip(): Map<Long, Loadout> {
        val slotsByShip = LinkedHashMap<Long, LinkedHashMap<SlotCategory, LinkedHashMap<Int, UpgradeId>>>()
        for (entry in queries.selectAllShipUpgrades().executeAsList()) {
            val category = parseSlotCategory(entry.slot_category) ?: continue
            val upgradeId = parseUpgrade(entry.upgrade_id) ?: continue
            slotsByShip
                .getOrPut(entry.ship_id) { LinkedHashMap() }
                .getOrPut(category) { LinkedHashMap() }[entry.slot_index.toInt()] = upgradeId
        }
        return slotsByShip.mapValues { (_, slots) -> Loadout(slots) }
    }

    /**
     * Reconstruct the per-field remaining-deposit map from `field_deposit`. Grouped by field id; a
     * field with no rows is simply absent (pristine). Unrecognised resource names are skipped (WARN).
     */
    private fun loadFieldDepletion(): Map<PoiId, Map<ResourceType, Int>> {
        val byField = LinkedHashMap<PoiId, LinkedHashMap<ResourceType, Int>>()
        for (entry in queries.selectFieldDeposits().executeAsList()) {
            val resource = parseResource(entry.resource) ?: continue
            byField.getOrPut(PoiId(entry.field_id)) { LinkedHashMap() }[resource] = entry.remaining_units.toInt()
        }
        return byField
    }

    /**
     * Reconstruct the revealed-hidden-contact set from `revealed_contact` (UC10). Each row's id is a
     * [PoiId]; an id whose contact the authored map no longer contains is kept harmlessly (it resolves
     * to nothing when the renderer/scan logic looks it up). Empty when nothing has been scanned.
     */
    private fun loadRevealedContacts(): Set<PoiId> {
        val ids = LinkedHashSet<PoiId>()
        for (contactId in queries.selectRevealedContacts().executeAsList()) {
            ids.add(PoiId(contactId))
        }
        return ids
    }

    /**
     * Reconstruct the accepted / terminal missions from the `mission` table (UC12). Each row maps to a
     * [Mission]; a row whose enum / resource name no longer resolves is **skipped with a WARN** (the
     * rest still load — "never stranded"), so an evolved catalog never crashes a load. Available offers
     * are not stored — they are regenerated on load (the caller wraps this list in a [MissionLog]).
     */
    private fun loadMissions(): List<Mission> {
        val missions = ArrayList<Mission>()
        for (row in queries.selectAllMissions().executeAsList()) {
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
                    // attribution" (the mission still loads; it just grants no reputation on turn-in). The
                    // gate (unlockFaction/unlockThreshold) is not persisted — accepted missions are never re-gated.
                    factionId = parseFaction(row.faction_id),
                )
        }
        return missions
    }

    /**
     * Reconstruct the player's per-faction reputation from the `reputation` table (UC14). Each row's
     * value is coerced into the [ReputationParams] bounds; a faction whose slug the [Factions] catalog no
     * longer knows is **skipped with a WARN** (never stranded). A standing that coerces to neutral (0) is
     * dropped, keeping the in-memory map canonical (only non-neutral entries) — so a fully-neutral or
     * migrated-empty save reads back as [Reputation.EMPTY].
     */
    private fun loadReputation(): Reputation {
        val params = ReputationParams()
        val standings = LinkedHashMap<FactionId, Int>()
        for (row in queries.selectReputation().executeAsList()) {
            val faction = parseFaction(row.faction_id) ?: continue
            val value = row.value_.toInt().coerceIn(params.min, params.max)
            if (value != 0) standings[faction] = value
        }
        return Reputation(standings)
    }

    /**
     * Reconstruct the player's [StationRegistry] from `owned_station` + `station_module` (UC15). Each
     * station's modules are grouped by station id (slot index -> module slug); an unknown module slug is
     * **skipped with a WARN** (the rest of the station's modules still load — never stranded). Stations
     * are sorted by id for a deterministic, registry-invariant order. Empty for a fresh / migrated
     * pre-UC15 save (no rows) — read back as [StationRegistry.EMPTY].
     */
    private fun loadStations(): StationRegistry {
        val modulesByStation = loadStationModulesByStation()
        val stations =
            queries.selectAllOwnedStations().executeAsList()
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
     * Group every station's persisted module rows by station id (UC15): slot index -> [StationModuleId].
     * A module slug the [StationModuleCatalog] no longer knows is skipped with a WARN (the rest of the
     * station's modules still load — never stranded).
     */
    private fun loadStationModulesByStation(): Map<Long, Map<Int, StationModuleId>> {
        val byStation = LinkedHashMap<Long, LinkedHashMap<Int, StationModuleId>>()
        for (entry in queries.selectAllStationModules().executeAsList()) {
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

    private companion object {
        const val TAG = "Save"
    }
}
