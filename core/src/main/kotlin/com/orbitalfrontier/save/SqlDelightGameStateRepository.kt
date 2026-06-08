package com.orbitalfrontier.save

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.ship.ShipKinematics
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
 * kinematics are `Float`, SQLite stores `REAL` (mapped to `Double`). `Float -> Double` is a
 * widening conversion and `Double -> Float` of that same value returns the original `Float` bit-for-
 * bit, so a save → reload round-trip is exact (UC04 AC#8). No engine/Box2D types are persisted —
 * only the pure [ShipKinematics] the body is re-seeded from on load (UC04 pitfall).
 */
class SqlDelightGameStateRepository(
    private val database: OrbitalFrontier,
    private val logger: Logger,
) : GameStateRepository {
    private val queries get() = database.orbitalFrontierQueries

    override fun loadGameState(): WorldState? {
        return try {
            val row = queries.selectGameState().executeAsOneOrNull() ?: return null
            WorldState(
                currentSector = SectorId(row.current_sector),
                ship =
                    ShipKinematics(
                        position = Vec2(row.pos_x.toFloat(), row.pos_y.toFloat()),
                        velocity = Vec2(row.vel_x.toFloat(), row.vel_y.toFloat()),
                        headingRadians = row.heading.toFloat(),
                        angularVelocity = row.ang_vel.toFloat(),
                    ),
                // Null column (a v2 save migrated to v3, or a save written while in flight) -> not docked.
                dockedStation = row.docked_station_id?.let { PoiId(it) },
                // Cargo: capacity is a ship stat, NOT persisted — reconstructed at DEFAULT_CAPACITY (UC06).
                cargo = loadCargo(),
                // Field depletion: absent field = pristine; stored values are REMAINING units (UC06).
                fieldDepletion = loadFieldDepletion(),
            )
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load game state; treating as no save (New Game)", e)
            null
        }
    }

    override fun saveGameState(state: WorldState) {
        try {
            // Ship row + save header written atomically: a failure rolls back both, so the previous
            // good save is never left half-overwritten (UC04 AC#3).
            queries.transaction {
                val ship = state.ship
                queries.upsertShip(
                    id = ACTIVE_SHIP_ID,
                    pos_x = ship.position.x.toDouble(),
                    pos_y = ship.position.y.toDouble(),
                    vel_x = ship.velocity.x.toDouble(),
                    vel_y = ship.velocity.y.toDouble(),
                    heading = ship.headingRadians.toDouble(),
                    ang_vel = ship.angularVelocity.toDouble(),
                )
                queries.upsertGameState(
                    current_sector = state.currentSector.value,
                    active_ship_id = ACTIVE_SHIP_ID,
                    docked_station_id = state.dockedStation?.value,
                )

                // Cargo: full-snapshot rewrite of the active ship's rows (delete-then-plain-INSERT,
                // minSdk-24-safe). Only non-zero amounts are stored; capacity is not persisted (UC06).
                queries.deleteCargoForShip(ACTIVE_SHIP_ID)
                for ((resource, units) in state.cargo.contents) {
                    if (units > 0) {
                        queries.insertCargoEntry(
                            ship_id = ACTIVE_SHIP_ID,
                            resource = resource.name,
                            units = units.toLong(),
                        )
                    }
                }

                // Field depletion: upsert every (field, resource) remaining amount (full snapshot).
                // Depletion is monotonic so we never delete; an untouched field simply has no rows (UC06).
                for ((fieldId, remaining) in state.fieldDepletion) {
                    for ((resource, units) in remaining) {
                        queries.upsertFieldDeposit(
                            field_id = fieldId.value,
                            resource = resource.name,
                            remaining_units = units.toLong(),
                        )
                    }
                }
            }
            logger.info(
                TAG,
                "Saved game state: sector=${state.currentSector.value}, " +
                    "pos=(${state.ship.position.x}, ${state.ship.position.y})",
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

    /**
     * Reconstruct the active ship's [Cargo] from its persisted rows. Capacity is a ship stat, so it
     * is re-seeded from [Cargo.DEFAULT_CAPACITY] (later, the ship's cargo-hold upgrade) rather than
     * read from the save. An unrecognised resource name (e.g. after an enum rename) is skipped with a
     * WARN rather than failing the whole load — "never stranded" (coding-guidelines § error-handling).
     */
    private fun loadCargo(): Cargo {
        val contents = LinkedHashMap<ResourceType, Int>()
        for (entry in queries.selectCargo(ACTIVE_SHIP_ID).executeAsList()) {
            val resource = parseResource(entry.resource) ?: continue
            contents[resource] = entry.units.toInt()
        }
        return Cargo(contents, Cargo.DEFAULT_CAPACITY)
    }

    /**
     * Reconstruct the per-field remaining-deposit map from `field_deposit`. Grouped by field id; a
     * field with no rows is simply absent (pristine). Unrecognised resource names are skipped with a
     * WARN, as in [loadCargo].
     */
    private fun loadFieldDepletion(): Map<PoiId, Map<ResourceType, Int>> {
        val byField = LinkedHashMap<PoiId, LinkedHashMap<ResourceType, Int>>()
        for (entry in queries.selectFieldDeposits().executeAsList()) {
            val resource = parseResource(entry.resource) ?: continue
            byField.getOrPut(PoiId(entry.field_id)) { LinkedHashMap() }[resource] = entry.remaining_units.toInt()
        }
        return byField
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

        /**
         * Id of the active ship row. One owned ship today; UC09 grows to multiple rows keyed by id,
         * with `game_state.active_ship_id` selecting the active one (the seam is already in place).
         */
        const val ACTIVE_SHIP_ID = 0L
    }
}
