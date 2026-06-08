package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.power.PowerParams
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
 * @property miningConfig the pinned [MiningParams] snapshot the run was recorded under (UC06), same
 *   rationale as [config]; defaulted so older artifacts (recorded before mining) decode unchanged.
 * @property powerConfig the pinned [PowerParams] snapshot the run was recorded under (UC07), same
 *   rationale as [config]; defaulted so older artifacts (recorded before fuel/power) decode unchanged.
 * @property fuelConfig the pinned [FuelParams] snapshot the run was recorded under (UC07), same
 *   rationale as [config]; defaulted so older artifacts (recorded before fuel/power) decode unchanged.
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
    val miningConfig: MiningParamsDto = MiningParamsDto.DEFAULT,
    val powerConfig: PowerParamsDto = PowerParamsDto.DEFAULT,
    val fuelConfig: FuelParamsDto = FuelParamsDto.DEFAULT,
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
 * Serializable mirror of [MiningParams] (UC06 config snapshot).
 *
 * [MiningParams] is a pure domain type and stays annotation-free; this DTO carries the same field
 * for persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the number
 * lives in exactly one place. Pinning the mining tuning per artifact means a later balancing change
 * to the extraction rate cannot silently invalidate an old recorded playthrough.
 */
@Serializable
data class MiningParamsDto(
    val extractionUnitsPerTick: Int,
) {
    /** Reconstruct the domain [MiningParams] (its `init` re-validates the value). */
    fun toMiningParams(): MiningParams = MiningParams(extractionUnitsPerTick = extractionUnitsPerTick)

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: MiningParams): MiningParamsDto = MiningParamsDto(params.extractionUnitsPerTick)

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: MiningParamsDto = from(MiningParams())
    }
}

/**
 * Serializable mirror of [PowerParams] (UC07 config snapshot).
 *
 * [PowerParams] is a pure domain type and stays annotation-free; this DTO carries the same fields
 * for persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers
 * live in exactly one place. Pinning the power tuning per artifact means a later balancing change to
 * the reactor output / draw rates cannot silently invalidate an old recorded playthrough.
 */
@Serializable
data class PowerParamsDto(
    val reactorOutput: Float,
    val baseModuleDraw: Float,
    val thrustDraw: Float,
) {
    /** Reconstruct the domain [PowerParams] (its `init` re-validates the values). */
    fun toPowerParams(): PowerParams =
        PowerParams(
            reactorOutput = reactorOutput,
            baseModuleDraw = baseModuleDraw,
            thrustDraw = thrustDraw,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: PowerParams): PowerParamsDto =
            PowerParamsDto(
                reactorOutput = params.reactorOutput,
                baseModuleDraw = params.baseModuleDraw,
                thrustDraw = params.thrustDraw,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: PowerParamsDto = from(PowerParams())
    }
}

/**
 * Serializable mirror of [FuelParams] (UC07 config snapshot).
 *
 * [FuelParams] is a pure domain type and stays annotation-free; this DTO carries the same fields for
 * persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers
 * live in exactly one place. Pinning the fuel tuning per artifact means a later change to the low-fuel
 * threshold / speed floor / conversion ratio cannot silently invalidate an old recorded playthrough.
 */
@Serializable
data class FuelParamsDto(
    val lowFuelThreshold: Float,
    val floorSpeedFraction: Float,
    val hydrogenToFuelRatio: Float,
) {
    /** Reconstruct the domain [FuelParams] (its `init` re-validates the values). */
    fun toFuelParams(): FuelParams =
        FuelParams(
            lowFuelThreshold = lowFuelThreshold,
            floorSpeedFraction = floorSpeedFraction,
            hydrogenToFuelRatio = hydrogenToFuelRatio,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: FuelParams): FuelParamsDto =
            FuelParamsDto(
                lowFuelThreshold = params.lowFuelThreshold,
                floorSpeedFraction = params.floorSpeedFraction,
                hydrogenToFuelRatio = params.hydrogenToFuelRatio,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: FuelParamsDto = from(FuelParams())
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
    /**
     * The cargo hold's capacity (a ship stat, UC06). Defaulted to [Cargo.DEFAULT_CAPACITY] so older
     * artifacts decode with the starter-ship capacity.
     */
    val cargoCapacity: Int = Cargo.DEFAULT_CAPACITY,
    /**
     * The cargo contents, keyed by [ResourceType.name] (the stable enum name, not its ordinal) → unit
     * count (UC06 AC#5). String-keyed so the on-disk form stays diffable and stable across enum
     * reordering. Empty by default, so older artifacts decode as an empty hold.
     */
    val cargo: Map<String, Int> = emptyMap(),
    /**
     * Per-asteroid-field remaining deposits (UC06 AC#4): [PoiId.value] → ([ResourceType.name] → units).
     * String-keyed for the same diffability/stability reason as [cargo]; an absent field is pristine.
     * Empty by default, so older artifacts decode with every field pristine.
     */
    val fieldDepletion: Map<String, Map<String, Int>> = emptyMap(),
    /**
     * The active ship's fuel **level** in fuel units (UC07 AC#6). Defaulted to a full tank
     * ([FuelParams.DEFAULT_TANK_CAPACITY]) so older artifacts (recorded before fuel existed) decode
     * fully fuelled — and so the pre-UC07 fixtures replay byte-identically (a full tank ⇒ no speed
     * penalty). Like cargo capacity, the tank's **capacity** is a ship stat, not save data, so it is
     * reconstructed from [FuelParams.DEFAULT_TANK_CAPACITY] on decode rather than stored.
     */
    val fuel: Float = FuelParams.DEFAULT_TANK_CAPACITY,
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
            cargo = Cargo(cargo.mapKeys { ResourceType.valueOf(it.key) }, cargoCapacity),
            fieldDepletion =
                fieldDepletion
                    .mapKeys { (fieldId, _) -> PoiId(fieldId) }
                    .mapValues { (_, deposits) -> deposits.mapKeys { ResourceType.valueOf(it.key) } },
            // Capacity is a ship stat, reconstructed (like Cargo.capacity); only the level is stored.
            fuel = Fuel(level = fuel, capacity = FuelParams.DEFAULT_TANK_CAPACITY),
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
                cargoCapacity = state.cargo.capacity,
                cargo = state.cargo.contents.mapKeys { it.key.name },
                fieldDepletion =
                    state.fieldDepletion
                        .mapKeys { (fieldId, _) -> fieldId.value }
                        .mapValues { (_, deposits) -> deposits.mapKeys { it.key.name } },
                fuel = state.fuel.level,
            )
    }
}
