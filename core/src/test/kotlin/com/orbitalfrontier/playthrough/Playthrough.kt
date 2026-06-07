package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import kotlinx.serialization.Serializable

/**
 * A deterministic, serializable recording of a play session (UC02 AC#3).
 *
 * Replaying a [Playthrough] through [com.orbitalfrontier.playthrough.ReplayRunner] reproduces the
 * exact same end state every time: the [seed] fixes randomness, [dtSeconds] fixes the timestep,
 * [config] pins the tuning the run used, [initialState] fixes the starting snapshot, and
 * [inputEvents] is the ordered, tick-stamped input script.
 *
 * @property formatVersion on-disk schema version; bump when the shape changes incompatibly so a
 *   reader can detect and reject/upgrade old artifacts.
 * @property name stable identifier used to locate the artifact (e.g. `playthroughs/<name>.json`).
 * @property seed RNG seed applied per playthrough (UC02 AC#2).
 * @property dtSeconds the fixed timestep every tick is stepped by.
 * @property tickCount total number of ticks the replay should step (0 until tickCount).
 * @property config the pinned [ShipMovementParams] snapshot the run was recorded under, so a later
 *   tuning change can't silently invalidate this artifact.
 * @property initialState optional starting snapshot; when null the replay starts from the default
 *   [SimulationState].
 * @property inputEvents the ordered input script; supports 0..N events per tick.
 */
@Serializable
data class Playthrough(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val name: String,
    val seed: Long,
    val dtSeconds: Float,
    val tickCount: Int,
    val config: MovementParamsDto = MovementParamsDto.DEFAULT,
    val initialState: StateSnapshotDto? = null,
    val inputEvents: List<InputEvent> = emptyList(),
) {
    companion object {
        /** Current on-disk schema version for the playthrough format. */
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

/**
 * Serializable mirror of [ShipMovementParams] (UC02 config snapshot).
 *
 * [ShipMovementParams] is a pure domain type and stays annotation-free; this DTO carries the same
 * fields for persistence and maps both ways. Its [DEFAULT] is derived from the domain default so
 * the numbers live in exactly one place.
 */
@Serializable
data class MovementParamsDto(
    val maxSpeed: Float,
    val maxAcceleration: Float,
    val maxReverseSpeed: Float,
    val rotationAcceleration: Float,
    val maxRotationSpeed: Float,
    val driftDecay: Float,
    val forwardConeRadians: Float,
    val reverseConeRadians: Float,
    val inputDeadzone: Float,
) {
    /** Reconstruct the domain [ShipMovementParams] (its `init` re-validates the values). */
    fun toParams(): ShipMovementParams =
        ShipMovementParams(
            maxSpeed = maxSpeed,
            maxAcceleration = maxAcceleration,
            maxReverseSpeed = maxReverseSpeed,
            rotationAcceleration = rotationAcceleration,
            maxRotationSpeed = maxRotationSpeed,
            driftDecay = driftDecay,
            forwardConeRadians = forwardConeRadians,
            reverseConeRadians = reverseConeRadians,
            inputDeadzone = inputDeadzone,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: ShipMovementParams): MovementParamsDto =
            MovementParamsDto(
                maxSpeed = params.maxSpeed,
                maxAcceleration = params.maxAcceleration,
                maxReverseSpeed = params.maxReverseSpeed,
                rotationAcceleration = params.rotationAcceleration,
                maxRotationSpeed = params.maxRotationSpeed,
                driftDecay = params.driftDecay,
                forwardConeRadians = params.forwardConeRadians,
                reverseConeRadians = params.reverseConeRadians,
                inputDeadzone = params.inputDeadzone,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: MovementParamsDto = from(ShipMovementParams())
    }
}

/**
 * Serializable mirror of [SimulationState] (UC02 AC#3 optional initial state; AC#6 snapshots).
 *
 * Mirrors the snapshot as flat scalar fields so the domain types ([SimulationState],
 * [ShipKinematics], [Vec2]) stay annotation-free. Extend this DTO in lock-step with
 * [SimulationState] as later use cases add simulated systems.
 */
@Serializable
data class StateSnapshotDto(
    val tick: Int,
    val posX: Float,
    val posY: Float,
    val velX: Float,
    val velY: Float,
    val headingRadians: Float,
    val angularVelocity: Float,
    /**
     * The sector slug ([SectorId.value]) the ship is in (UC03 AC#5). Defaulted to the MVP start
     * sector so older artifacts (recorded before the field existed) still decode unchanged.
     */
    val currentSector: String = MvpSectorMap.START_SECTOR.value,
    /**
     * The [PoiId] slug of the station the ship is docked at (UC05 AC#4/#6), or null when in flight.
     * Defaulted to null so older artifacts (recorded before the field existed) decode as "in flight".
     */
    val dockedStation: String? = null,
) {
    /** Reconstruct the domain [SimulationState]. */
    fun toSimulationState(): SimulationState =
        SimulationState(
            tick = tick,
            ship =
                ShipKinematics(
                    position = Vec2(posX, posY),
                    velocity = Vec2(velX, velY),
                    headingRadians = headingRadians,
                    angularVelocity = angularVelocity,
                ),
            currentSector = SectorId(currentSector),
            dockedStation = dockedStation?.let(::PoiId),
        )

    companion object {
        /** Snapshot [state] into its serializable form. */
        fun from(state: SimulationState): StateSnapshotDto =
            StateSnapshotDto(
                tick = state.tick,
                posX = state.ship.position.x,
                posY = state.ship.position.y,
                velX = state.ship.velocity.x,
                velY = state.ship.velocity.y,
                headingRadians = state.ship.headingRadians,
                angularVelocity = state.ship.angularVelocity,
                currentSector = state.currentSector.value,
                dockedStation = state.dockedStation?.value,
            )
    }
}
