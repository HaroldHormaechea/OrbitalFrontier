package com.orbitalfrontier.save

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.ship.ShipKinematics
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
                )
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

    private companion object {
        const val TAG = "Save"

        /**
         * Id of the active ship row. One owned ship today; UC09 grows to multiple rows keyed by id,
         * with `game_state.active_ship_id` selecting the active one (the seam is already in place).
         */
        const val ACTIVE_SHIP_ID = 0L
    }
}
