package com.orbitalfrontier.save

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
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
