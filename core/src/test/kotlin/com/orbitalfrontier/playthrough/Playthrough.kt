package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.SectionDamage
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.PricingParams
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarketState
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.mission.BountyParams
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionParams
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeId
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.ShipTypeId
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.station.OwnedStation
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.station.StationModuleId
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * @property missionConfig the pinned [MissionParams] snapshot the run was recorded under (UC12), same
 *   rationale as [config]; defaulted so older artifacts (recorded before missions) decode unchanged.
 * @property combatConfig the pinned [CombatParams] snapshot the run was recorded under (UC13), same
 *   rationale as [config] — a later balance retune of weapon/damage/respawn numbers can't invalidate this
 *   replay; defaulted so older artifacts (recorded before combat) decode unchanged.
 * @property reputationConfig the pinned [ReputationParams] snapshot the run was recorded under (UC14),
 *   same rationale as [config] — a later retune of the mission-complete / courier-fail deltas or the
 *   clamp bounds can't silently invalidate this replay. Marked `@EncodeDefault(NEVER)` so an artifact
 *   that ran under the default params **omits** it on disk, keeping every pre-UC14 fixture byte-identical
 *   (`encodeDefaults = true` would otherwise write it into all of them).
 * @property bountyConfig the pinned [BountyParams] snapshot the run was recorded under (UC41), same
 *   rationale as [config] — a later retune of the bounty reward base / per-kill bonus can't silently
 *   invalidate this replay (the payout it asserts is reproduced exactly). Marked `@EncodeDefault(NEVER)`
 *   so an artifact that ran under the default bounty tuning **omits** it on disk, keeping every pre-UC41
 *   fixture byte-identical despite the codec's global `encodeDefaults = true`.
 * @property pricingConfig the pinned [PricingParams] snapshot the run was recorded under (UC46), same
 *   rationale as [config] — a later retune of the elasticity / drift / decay / faction constants can't
 *   silently invalidate this replay (the price moves it asserts are reproduced exactly). Marked
 *   `@EncodeDefault(NEVER)` so an artifact that ran under the default pricing tuning **omits** it on disk,
 *   keeping every pre-UC46 fixture byte-identical despite the codec's global `encodeDefaults = true`.
 * @property initialState optional starting snapshot; when null the replay starts from the default
 *   [SimulationState].
 * @property inputEvents the ordered input script; supports 0..N events per tick.
 */
@OptIn(ExperimentalSerializationApi::class)
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
    val missionConfig: MissionParamsDto = MissionParamsDto.DEFAULT,
    val combatConfig: CombatParamsDto = CombatParamsDto.DEFAULT,
    // UC14: @EncodeDefault(NEVER) so a run under the default reputation tuning omits this on disk, keeping
    // every pre-UC14 fixture byte-identical despite the codec's global encodeDefaults = true.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reputationConfig: ReputationParamsDto = ReputationParamsDto.DEFAULT,
    // UC41: @EncodeDefault(NEVER) so a run under the default bounty tuning omits this on disk, keeping
    // every pre-UC41 fixture byte-identical despite the codec's global encodeDefaults = true.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bountyConfig: BountyParamsDto = BountyParamsDto.DEFAULT,
    // UC46: @EncodeDefault(NEVER) so a run under the default pricing tuning omits this on disk, keeping
    // every pre-UC46 fixture byte-identical despite the codec's global encodeDefaults = true.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pricingConfig: PricingParamsDto = PricingParamsDto.DEFAULT,
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
 * Serializable mirror of [MissionParams] (UC12 config snapshot).
 *
 * [MissionParams] is a pure domain type and stays annotation-free; this DTO carries the same fields for
 * persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers live
 * in exactly one place. Pinning the mission tuning per artifact (mirroring [MiningParamsDto]) means a
 * later balancing change to the reward formulae, quota ranges, courier timer or radio range cannot
 * silently invalidate an old recorded playthrough — the offers it generates and the rewards it grants
 * are reproduced exactly. Defaulted on [Playthrough] so older artifacts (recorded before missions)
 * decode unchanged.
 */
@Serializable
data class MissionParamsDto(
    val boardMiningQuotaMin: Int,
    val boardMiningQuotaMax: Int,
    val radioMiningQuotaMin: Int,
    val radioMiningQuotaMax: Int,
    val miningRewardBase: Long,
    val miningRewardPerUnit: Long,
    val courierRewardBase: Long,
    val courierRewardSpan: Int,
    val courierTickLimitMin: Int,
    val courierTickLimitMax: Int,
    val courierFailurePenalty: Long,
    val radioRange: Float,
) {
    /** Reconstruct the domain [MissionParams] (its `init` re-validates the values). */
    fun toMissionParams(): MissionParams =
        MissionParams(
            boardMiningQuotaMin = boardMiningQuotaMin,
            boardMiningQuotaMax = boardMiningQuotaMax,
            radioMiningQuotaMin = radioMiningQuotaMin,
            radioMiningQuotaMax = radioMiningQuotaMax,
            miningRewardBase = miningRewardBase,
            miningRewardPerUnit = miningRewardPerUnit,
            courierRewardBase = courierRewardBase,
            courierRewardSpan = courierRewardSpan,
            courierTickLimitMin = courierTickLimitMin,
            courierTickLimitMax = courierTickLimitMax,
            courierFailurePenalty = courierFailurePenalty,
            radioRange = radioRange,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: MissionParams): MissionParamsDto =
            MissionParamsDto(
                boardMiningQuotaMin = params.boardMiningQuotaMin,
                boardMiningQuotaMax = params.boardMiningQuotaMax,
                radioMiningQuotaMin = params.radioMiningQuotaMin,
                radioMiningQuotaMax = params.radioMiningQuotaMax,
                miningRewardBase = params.miningRewardBase,
                miningRewardPerUnit = params.miningRewardPerUnit,
                courierRewardBase = params.courierRewardBase,
                courierRewardSpan = params.courierRewardSpan,
                courierTickLimitMin = params.courierTickLimitMin,
                courierTickLimitMax = params.courierTickLimitMax,
                courierFailurePenalty = params.courierFailurePenalty,
                radioRange = params.radioRange,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: MissionParamsDto = from(MissionParams())
    }
}

/**
 * Serializable mirror of [CombatParams] (UC13 config snapshot).
 *
 * [CombatParams] is a pure domain type and stays annotation-free; this DTO carries the same fields for
 * persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers live
 * in exactly one place. Pinning the combat tuning per artifact (mirroring [MiningParamsDto]) means a
 * later balance change to hit radius / section weights / engine-speed floor / respawn cargo-loss /
 * muzzle offset / spawn distance cannot silently invalidate an old recorded combat playthrough — the
 * projectiles it fires, the sections it strikes (the RNG-weighted pick reads these weights) and the
 * respawn penalty are reproduced exactly. Defaulted on [Playthrough] so older artifacts (recorded
 * before combat) decode unchanged.
 *
 * [sectionHitWeights] is stored as a [ShipSection.name]-keyed map (the stable enum name, not its
 * ordinal) so the on-disk form is diffable and survives enum reordering — matching every other enum-keyed
 * DTO map here.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CombatParamsDto(
    val hitRadius: Float,
    val sectionHitWeights: Map<String, Int>,
    val minEngineSpeedFactor: Float,
    val respawnCargoLossFraction: Float,
    val muzzleOffset: Float,
    val spawnDistance: Float,
    // UC42: the salvage pickup radius. Unlike its sibling fields this is `@EncodeDefault(NEVER)` with a
    // default derived from [CombatParams] — so a run under the default radius OMITS the key entirely.
    // Every one of the 14 committed combat fixtures serializes its combatConfig block (the codec's global
    // encodeDefaults = true), so a plain new field would have added a key to all of them and broken their
    // bytes; omitting-by-default keeps them byte-identical (no fixture regen). A future playthrough that
    // pins a non-default radius records it, so the replay it asserts is still reproduced exactly.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val salvagePickupRadius: Float = CombatParams().salvagePickupRadius,
) {
    /** Reconstruct the domain [CombatParams] (its `init` re-validates the values). */
    fun toCombatParams(): CombatParams =
        CombatParams(
            hitRadius = hitRadius,
            sectionHitWeights = sectionHitWeights.mapKeys { ShipSection.valueOf(it.key) },
            minEngineSpeedFactor = minEngineSpeedFactor,
            respawnCargoLossFraction = respawnCargoLossFraction,
            muzzleOffset = muzzleOffset,
            spawnDistance = spawnDistance,
            salvagePickupRadius = salvagePickupRadius,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: CombatParams): CombatParamsDto =
            CombatParamsDto(
                hitRadius = params.hitRadius,
                sectionHitWeights = params.sectionHitWeights.mapKeys { it.key.name },
                minEngineSpeedFactor = params.minEngineSpeedFactor,
                respawnCargoLossFraction = params.respawnCargoLossFraction,
                muzzleOffset = params.muzzleOffset,
                spawnDistance = params.spawnDistance,
                salvagePickupRadius = params.salvagePickupRadius,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: CombatParamsDto = from(CombatParams())
    }
}

/**
 * Serializable mirror of [ReputationParams] (UC14 config snapshot).
 *
 * [ReputationParams] is a pure domain type and stays annotation-free; this DTO carries the same fields
 * for persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers
 * live in exactly one place. Pinning the reputation tuning per artifact (mirroring [MissionParamsDto])
 * means a later balance change to the mission-complete gain, the courier-fail loss, or the clamp bounds
 * cannot silently invalidate an old recorded faction playthrough — the standings it grants are
 * reproduced exactly. Defaulted (and `@EncodeDefault(NEVER)` on [Playthrough]) so older artifacts
 * (recorded before reputation) decode unchanged and omit it on disk.
 */
@Serializable
data class ReputationParamsDto(
    val missionCompleteDelta: Int,
    val courierFailDelta: Int,
    val min: Int,
    val max: Int,
    // UC43: the per-faction-kill standing change. The only NEW serialized field; `@EncodeDefault(NEVER)`
    // with a domain-derived default (mirroring CombatParamsDto.salvagePickupRadius) so a run under the
    // default tuning OMITS the key — every committed fixture stays byte-identical (no regen) and older
    // artifacts (recorded before UC43) decode via the default.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val combatKillDelta: Int = ReputationParams().combatKillDelta,
) {
    /** Reconstruct the domain [ReputationParams] (its `init` re-validates the values). */
    fun toReputationParams(): ReputationParams =
        ReputationParams(
            missionCompleteDelta = missionCompleteDelta,
            courierFailDelta = courierFailDelta,
            combatKillDelta = combatKillDelta,
            min = min,
            max = max,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: ReputationParams): ReputationParamsDto =
            ReputationParamsDto(
                missionCompleteDelta = params.missionCompleteDelta,
                courierFailDelta = params.courierFailDelta,
                combatKillDelta = params.combatKillDelta,
                min = params.min,
                max = params.max,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: ReputationParamsDto = from(ReputationParams())
    }
}

/**
 * Serializable mirror of [BountyParams] (UC41 config snapshot).
 *
 * [BountyParams] is a pure domain type and stays annotation-free; this DTO carries the same fields for
 * persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers live
 * in exactly one place. Pinning the bounty tuning per artifact (mirroring [MissionParamsDto]) means a
 * later balance change to the reward base / per-kill bonus cannot silently invalidate an old recorded
 * bounty playthrough — the payout it grants on completion is reproduced exactly. Defaulted (and
 * `@EncodeDefault(NEVER)` on [Playthrough]) so older artifacts (recorded before bounties) decode
 * unchanged and omit it on disk.
 */
@Serializable
data class BountyParamsDto(
    val rewardBase: Long,
    val rewardPerKill: Long,
) {
    /** Reconstruct the domain [BountyParams] (its `init` re-validates the values). */
    fun toBountyParams(): BountyParams =
        BountyParams(
            rewardBase = rewardBase,
            rewardPerKill = rewardPerKill,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: BountyParams): BountyParamsDto =
            BountyParamsDto(
                rewardBase = params.rewardBase,
                rewardPerKill = params.rewardPerKill,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: BountyParamsDto = from(BountyParams())
    }
}

/**
 * Serializable mirror of [SimulationState] (UC02 AC#3 optional initial state; AC#6 snapshots).
 *
 * Mirrors the snapshot as flat scalar fields so the domain types ([SimulationState],
 * [ShipKinematics], [Vec2]) stay annotation-free. Extend this DTO in lock-step with
 * [SimulationState] as later use cases add simulated systems.
 */
@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * The player's credit balance (UC08 AC#1). Defaulted to `0L` so older artifacts (recorded before
     * credits existed) decode broke — matching the production v5→v6 migration backfill — and so the
     * pre-UC08 fixtures replay byte-identically (no credits, no trade). Credits are save-wide (not
     * per-ship), so they ride here directly rather than on a ship sub-snapshot.
     */
    val credits: Long = 0L,
    /**
     * The fleet of owned ships (UC09 AC#5/#6) — one entry per [com.orbitalfrontier.ship.OwnedShip].
     * Defaulted **empty** so older artifacts (recorded before the fleet existed) decode via the legacy
     * path in [toFleet]: the flat ship fields above reconstruct the **one** starter ship, so the
     * pre-UC09 fixtures replay byte-identically. When non-empty it is **authoritative** — the flat ship
     * fields are ignored and the fleet is rebuilt from these entries.
     */
    val ownedShips: List<OwnedShipDto> = emptyList(),
    /**
     * Which owned ship is active (UC09 AC#5) — [com.orbitalfrontier.ship.ShipId.value]. Defaulted to
     * the starter ship id (0). Used only when [ownedShips] is non-empty; the legacy single-ship path
     * always reconstructs the one starter ship as active.
     */
    val activeShipId: Long = 0L,
    /**
     * The ids of hidden contacts the player has revealed by active scanning (UC10 AC#4) — each a
     * [PoiId.value] slug. Stored as a **sorted** list so the on-disk form is stable and diffable (the
     * domain side is an order-insensitive `Set`). Defaulted **empty** so older artifacts (recorded
     * before scanning existed) decode with nothing revealed, and the pre-UC10 fixtures replay
     * byte-identically.
     */
    val revealedContacts: List<String> = emptyList(),
    /**
     * The player's mission log (UC12) — its accepted / terminal missions, plus any surfaced offers. In
     * the pure simulation only [MissionLog.accepted] ever grows; [MissionLog.available] is recomputed
     * transiently and is normally empty here. Defaulted to an **empty** log so older artifacts (recorded
     * before missions existed) decode with no missions and the pre-UC12 fixtures replay byte-identically.
     */
    val missions: MissionLogDto = MissionLogDto.EMPTY,
    /**
     * The [PoiId] slug of the station the player most recently docked at (UC13 AC#5) — the respawn point
     * on destruction — or null when the player has never docked. **Persisted** (unlike [dockedStation]).
     * The transient live combat encounter (hostiles/projectiles/RNG) is deliberately NOT carried here —
     * it is regenerated from the seeded encounter on load (a mid-combat save reloads with combat cleared,
     * ADR 0012), so the snapshot only carries the durable combat state (this + each ship's section damage).
     * Defaulted null so older artifacts (recorded before combat) decode unchanged.
     */
    val lastDockedStation: String? = null,
    /**
     * The player's per-faction reputation (UC14 AC#2) — the save-wide standing carried on
     * [SimulationState.reputation]. Marked `@EncodeDefault(NEVER)` and defaulted to [ReputationDto.EMPTY]
     * so a neutral snapshot (every pre-UC14 fixture, and a fresh game) **omits** it on disk — keeping the
     * 11 committed fixtures byte-identical despite the codec's global `encodeDefaults = true`. A non-neutral
     * standing is written as a compact `factionSlug → value` map (only the non-zero rows, matching the
     * domain [Reputation], which stores only non-neutral standings).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reputation: ReputationDto = ReputationDto.EMPTY,
    /**
     * The player's owned stations (UC15 AC#1/#3) — the save-wide registry carried on
     * [SimulationState.stations]. Marked `@EncodeDefault(NEVER)` and defaulted to [StationsDto.EMPTY] so an
     * empty registry (every pre-UC15 fixture, and a fresh game) **omits** it on disk — keeping the existing
     * committed fixtures byte-identical despite the codec's global `encodeDefaults = true`. A non-empty
     * registry is written as a list of [OwnedStationDto] (id + anchor sector + slot→module-slug map).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stations: StationsDto = StationsDto.EMPTY,
    /**
     * The dynamic per-station market pressure (UC46 AC#1/#3) — the save-wide state carried on
     * [SimulationState.marketState]. Marked `@EncodeDefault(NEVER)` and defaulted to
     * [StationMarketStateDto.EMPTY] so an empty (never-traded) state — every pre-UC46 fixture, and a fresh
     * game — **omits** it on disk, keeping the committed fixtures byte-identical despite the codec's global
     * `encodeDefaults = true`. A non-empty state is written as a compact `stationSlug → (resourceName →
     * pressure)` map (only the non-zero rows, matching the domain [StationMarketState]).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val marketState: StationMarketStateDto = StationMarketStateDto.EMPTY,
) {
    /** Reconstruct the domain [SimulationState]. */
    fun toSimulationState(): SimulationState =
        SimulationState(
            tick = tick,
            fleet = toFleet(),
            currentSector = SectorId(currentSector),
            dockedStation = dockedStation?.let(::PoiId),
            fieldDepletion =
                fieldDepletion
                    .mapKeys { (fieldId, _) -> PoiId(fieldId) }
                    .mapValues { (_, deposits) -> deposits.mapKeys { ResourceType.valueOf(it.key) } },
            credits = credits,
            revealedContacts = revealedContacts.map(::PoiId).toSet(),
            missions = missions.toMissionLog(),
            // Combat is transient (ADR 0012) — decode as NONE; only the durable lastDockedStation rides here.
            lastDockedStation = lastDockedStation?.let(::PoiId),
            // UC14: the per-faction standing (an absent / omitted field decodes to neutral EMPTY).
            reputation = reputation.toReputation(),
            // UC15: the player's owned stations (an absent / omitted field decodes to the empty registry).
            stations = stations.toStationRegistry(),
            // UC46: the dynamic market pressure (an absent / omitted field decodes to the empty state).
            marketState = marketState.toMarketState(),
        )

    /**
     * Rebuild the [Fleet]. When [ownedShips] is non-empty it is authoritative — one [OwnedShip] per
     * entry, sorted by id, with [activeShipId] selected (falling back to the first ship if it points
     * at no owned ship). When empty (older artifacts) the **flat** ship fields reconstruct the single
     * starter ship — its fuel capacity reconstructed from [FuelParams.DEFAULT_TANK_CAPACITY] like cargo
     * capacity — so the pre-UC09 fixtures replay byte-identically.
     */
    private fun toFleet(): Fleet {
        if (ownedShips.isEmpty()) {
            val ship =
                OwnedShip(
                    id = OwnedShip.STARTER_SHIP_ID,
                    type = ShipRoster.STARTER,
                    kinematics =
                        ShipKinematics(
                            position = Vec2(posX, posY),
                            velocity = Vec2(velX, velY),
                            headingRadians = headingRadians,
                            angularVelocity = angularVelocity,
                        ),
                    cargo = Cargo(cargo.mapKeys { ResourceType.valueOf(it.key) }, cargoCapacity),
                    fuel = Fuel(level = fuel, capacity = FuelParams.DEFAULT_TANK_CAPACITY),
                    loadout = Loadout.EMPTY,
                )
            return Fleet(listOf(ship), OwnedShip.STARTER_SHIP_ID)
        }
        val ships = ownedShips.map { it.toOwnedShip() }.sortedBy { it.id.value }
        val activeId = ShipId(activeShipId)
        return Fleet(ships, if (ships.any { it.id == activeId }) activeId else ships.first().id)
    }

    companion object {
        /** Snapshot [state] into its serializable form. */
        fun from(state: SimulationState): StateSnapshotDto =
            StateSnapshotDto(
                tick = state.tick,
                // The flat ship fields mirror the ACTIVE ship — retained for back-compat + human-diff.
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
                credits = state.credits,
                ownedShips = state.fleet.ships.map(OwnedShipDto::from),
                activeShipId = state.fleet.activeShipId.value,
                // Sorted slug list: stable/diffable on disk; the domain side is an order-insensitive Set.
                revealedContacts = state.revealedContacts.map { it.value }.sorted(),
                missions = MissionLogDto.from(state.missions),
                // UC13: only the durable last docked station is snapshotted; live combat is transient.
                lastDockedStation = state.lastDockedStation?.value,
                // UC14: the per-faction standing — neutral EMPTY omits on disk (byte-identical pre-UC14).
                reputation = ReputationDto.from(state.reputation),
                // UC15: the player's owned stations — empty registry omits on disk (byte-identical pre-UC15).
                stations = StationsDto.from(state.stations),
                // UC46: the dynamic market pressure — empty state omits on disk (byte-identical pre-UC46).
                marketState = StationMarketStateDto.from(state.marketState),
            )
    }
}

/**
 * Serializable mirror of [StationMarketState] (UC46 AC#1/#3). Stores the dynamic per-station market
 * pressure as a compact `stationSlug → (resourceName → pressure)` map — only the **non-zero** rows,
 * exactly as the domain [StationMarketState] keeps only non-zero pressure, so the on-disk form is minimal
 * and diffable. [EMPTY] (the no-pressure default) is the value [StateSnapshotDto.marketState] omits via
 * `@EncodeDefault(NEVER)`, so a fresh game and every pre-UC46 fixture serialize without a marketState
 * field at all. String-keyed (PoiId slug / ResourceType name) for the same stability reason as cargo /
 * field depletion. On decode an unknown resource name throws (a fixture must reference real resources),
 * matching how cargo / field-depletion DTOs decode.
 */
@Serializable
data class StationMarketStateDto(
    val pressure: Map<String, Map<String, Int>> = emptyMap(),
) {
    /** Reconstruct the domain [StationMarketState] (stationSlug → resourceName → pressure). */
    fun toMarketState(): StationMarketState {
        if (pressure.isEmpty()) return StationMarketState.EMPTY
        return StationMarketState(
            pressure
                .mapKeys { (stationSlug, _) -> PoiId(stationSlug) }
                .mapValues { (_, byResource) -> byResource.mapKeys { ResourceType.valueOf(it.key) } },
        )
    }

    companion object {
        /** The no-pressure default — a fresh game and every pre-UC46 / migrated save. */
        val EMPTY: StationMarketStateDto = StationMarketStateDto()

        /** Snapshot [state] into its serializable form (the non-zero pressure rows, slug/name-keyed). */
        fun from(state: StationMarketState): StationMarketStateDto =
            StationMarketStateDto(
                state.pressureByStation
                    .mapKeys { (stationId, _) -> stationId.value }
                    .mapValues { (_, byResource) -> byResource.mapKeys { it.key.name } },
            )
    }
}

/**
 * Serializable mirror of [PricingParams] (UC46 config snapshot).
 *
 * [PricingParams] is a pure domain type and stays annotation-free; this DTO carries the same fields for
 * persistence and maps both ways. Its [DEFAULT] is derived from the domain default so the numbers live in
 * exactly one place. Pinning the pricing tuning per artifact (mirroring [ReputationParamsDto]) means a
 * later retune of the elasticity / drift / decay / faction constants cannot silently invalidate an old
 * recorded trading playthrough — the price moves it asserts are reproduced exactly. Defaulted (and
 * `@EncodeDefault(NEVER)` on [Playthrough]) so older artifacts (recorded before dynamic pricing) decode
 * unchanged and omit it on disk.
 */
@Serializable
data class PricingParamsDto(
    val elasticity: Double,
    val pressureScale: Double,
    val minMul: Double,
    val maxMul: Double,
    val driftAmplitude: Double,
    val driftPeriodTicks: Int,
    val decayNum: Int,
    val decayDen: Int,
    val decayPeriodTicks: Int,
    val factionInfluence: Double,
    val factionStandingScale: Double,
) {
    /** Reconstruct the domain [PricingParams] (its `init` re-validates the values). */
    fun toPricingParams(): PricingParams =
        PricingParams(
            elasticity = elasticity,
            pressureScale = pressureScale,
            minMul = minMul,
            maxMul = maxMul,
            driftAmplitude = driftAmplitude,
            driftPeriodTicks = driftPeriodTicks,
            decayNum = decayNum,
            decayDen = decayDen,
            decayPeriodTicks = decayPeriodTicks,
            factionInfluence = factionInfluence,
            factionStandingScale = factionStandingScale,
        )

    companion object {
        /** Snapshot [params] into its serializable form. */
        fun from(params: PricingParams): PricingParamsDto =
            PricingParamsDto(
                elasticity = params.elasticity,
                pressureScale = params.pressureScale,
                minMul = params.minMul,
                maxMul = params.maxMul,
                driftAmplitude = params.driftAmplitude,
                driftPeriodTicks = params.driftPeriodTicks,
                decayNum = params.decayNum,
                decayDen = params.decayDen,
                decayPeriodTicks = params.decayPeriodTicks,
                factionInfluence = params.factionInfluence,
                factionStandingScale = params.factionStandingScale,
            )

        /** The serialized default tuning, derived from the domain default (single source of truth). */
        val DEFAULT: PricingParamsDto = from(PricingParams())
    }
}

/**
 * Serializable mirror of [StationRegistry] (UC15 AC#1/#3). Stores the player's owned stations as a list of
 * [OwnedStationDto]. [EMPTY] (the no-stations default) is the value [StateSnapshotDto.stations] omits via
 * `@EncodeDefault(NEVER)`, so a fresh game and every pre-UC15 fixture serialize without a stations field at
 * all. On decode the registry is rebuilt sorted by id (the domain [StationRegistry] requires sorted-unique
 * ids), so the on-disk order need not be authoritative.
 */
@Serializable
data class StationsDto(
    val stations: List<OwnedStationDto> = emptyList(),
) {
    /** Reconstruct the domain [StationRegistry] (sorted by id, as the domain invariant requires). */
    fun toStationRegistry(): StationRegistry = StationRegistry(stations.map { it.toOwnedStation() }.sortedBy { it.id.value })

    companion object {
        /** The empty registry — a fresh game and every pre-UC15 / migrated save. */
        val EMPTY: StationsDto = StationsDto()

        /** Snapshot [registry] into its serializable form (one [OwnedStationDto] per owned station). */
        fun from(registry: StationRegistry): StationsDto = StationsDto(registry.stations.map(OwnedStationDto::from))
    }
}

/**
 * Serializable mirror of one [OwnedStation] (UC15 AC#1/#2/#3) — its [id], anchor [sector] slug, and the
 * gap-tolerant `slotIndex → module-slug` [modules] map (the [StationModuleId] is the persisted slug; the
 * module's function/cost are authored catalog data, never stored — resolved through
 * [com.orbitalfrontier.station.StationModuleCatalog] on use). Mirrors the production `owned_station` +
 * `station_module` row shape (UC15 persistence) and keeps the domain types annotation-free.
 */
@Serializable
data class OwnedStationDto(
    val id: Long,
    val sector: String,
    val modules: Map<Int, String> = emptyMap(),
) {
    /** Reconstruct the domain [OwnedStation]. */
    fun toOwnedStation(): OwnedStation =
        OwnedStation(
            id = StationId(id),
            sector = SectorId(sector),
            modules = modules.mapValues { StationModuleId(it.value) },
        )

    companion object {
        /** Snapshot [station] into its serializable form. */
        fun from(station: OwnedStation): OwnedStationDto =
            OwnedStationDto(
                id = station.id.value,
                sector = station.sector.value,
                modules = station.modules.mapValues { it.value.value },
            )
    }
}

/**
 * Serializable mirror of [Reputation] (UC14 AC#2). Stores the player's standings as a compact
 * `factionSlug → value` map — only the **non-neutral** rows, exactly as the domain [Reputation] keeps
 * only non-zero standings, so the on-disk form is minimal and diffable. [EMPTY] (the neutral default)
 * is the value [StateSnapshotDto.reputation] omits via `@EncodeDefault(NEVER)`, so a fresh game and
 * every pre-UC14 fixture serialize without a reputation field at all.
 */
@Serializable
data class ReputationDto(
    val byFaction: Map<String, Int> = emptyMap(),
) {
    /** Reconstruct the domain [Reputation] (factionSlug → standing). */
    fun toReputation(): Reputation = Reputation(byFaction.mapKeys { FactionId(it.key) })

    companion object {
        /** The neutral default — no recorded standing with any faction. */
        val EMPTY: ReputationDto = ReputationDto()

        /** Snapshot [reputation] into its serializable form (the non-neutral rows, keyed by faction slug). */
        fun from(reputation: Reputation): ReputationDto = ReputationDto(reputation.byFaction.mapKeys { it.key.value })
    }
}

/**
 * Serializable mirror of [MissionLog] (UC12 AC#3/#5). Splits into the transient [available] offers and
 * the persisted [accepted] / terminal missions, mirroring the domain split; on disk each is a list of
 * [MissionDto]. Both default empty so a snapshot with no missions encodes minimally and an older
 * artifact decodes to [MissionLog.EMPTY].
 */
@Serializable
data class MissionLogDto(
    val available: List<MissionDto> = emptyList(),
    val accepted: List<MissionDto> = emptyList(),
) {
    /** Reconstruct the domain [MissionLog]. */
    fun toMissionLog(): MissionLog =
        MissionLog(
            available = available.map { it.toMission() },
            accepted = accepted.map { it.toMission() },
        )

    companion object {
        /** An empty mission log DTO — the snapshot default. */
        val EMPTY: MissionLogDto = MissionLogDto()

        /** Snapshot [log] into its serializable form. */
        fun from(log: MissionLog): MissionLogDto =
            MissionLogDto(
                available = log.available.map(MissionDto::from),
                accepted = log.accepted.map(MissionDto::from),
            )
    }
}

/**
 * Serializable mirror of one [Mission] (UC12). Flat string-keyed fields keep the domain [Mission]
 * annotation-free: enums ([MissionType]/[MissionSource]/[MissionStatus]) and the [ResourceType] reward /
 * quota are stored by their stable **name** (not ordinal), and [PoiId]s by their slug, so the on-disk
 * form is diffable and survives enum reordering. The optional type-specific fields mirror [Mission]'s
 * nullable mining / courier params one-for-one.
 *
 * **UC41 bounty fields** ([targetZoneId], [killTarget], [killProgress]) mirror [Mission]'s additive bounty
 * params. Each is marked `@EncodeDefault(NEVER)` so a non-bounty mission (every pre-UC41 mission, where they
 * sit at their `null`/`0` defaults) **omits** them on disk despite the codec's global `encodeDefaults = true`,
 * keeping every pre-UC41 fixture byte-identical; only a real BOUNTY mission (non-default values) writes them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MissionDto(
    val id: String,
    val type: String,
    val source: String,
    val status: String,
    val rewardCredits: Long,
    val rewardResource: String? = null,
    val rewardResourceUnits: Int = 0,
    val quotaResource: String? = null,
    val quotaUnits: Int = 0,
    val pickup: String? = null,
    val destination: String? = null,
    val remainingTicks: Int = 0,
    val pickedUp: Boolean = false,
    // UC41 BOUNTY params — @EncodeDefault(NEVER) so a non-bounty mission omits them (byte-identical pre-UC41).
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val targetZoneId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val killTarget: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val killProgress: Int = 0,
) {
    /** Reconstruct the domain [Mission]. */
    fun toMission(): Mission =
        Mission(
            id = MissionId(id),
            type = MissionType.valueOf(type),
            source = MissionSource.valueOf(source),
            status = MissionStatus.valueOf(status),
            rewardCredits = rewardCredits,
            rewardResource = rewardResource?.let { ResourceType.valueOf(it) },
            rewardResourceUnits = rewardResourceUnits,
            quotaResource = quotaResource?.let { ResourceType.valueOf(it) },
            quotaUnits = quotaUnits,
            pickup = pickup?.let(::PoiId),
            destination = destination?.let(::PoiId),
            remainingTicks = remainingTicks,
            pickedUp = pickedUp,
            targetZoneId = targetZoneId,
            killTarget = killTarget,
            killProgress = killProgress,
        )

    companion object {
        /** Snapshot [mission] into its serializable form. */
        fun from(mission: Mission): MissionDto =
            MissionDto(
                id = mission.id.value,
                type = mission.type.name,
                source = mission.source.name,
                status = mission.status.name,
                rewardCredits = mission.rewardCredits,
                rewardResource = mission.rewardResource?.name,
                rewardResourceUnits = mission.rewardResourceUnits,
                quotaResource = mission.quotaResource?.name,
                quotaUnits = mission.quotaUnits,
                pickup = mission.pickup?.value,
                destination = mission.destination?.value,
                remainingTicks = mission.remainingTicks,
                pickedUp = mission.pickedUp,
                targetZoneId = mission.targetZoneId,
                killTarget = mission.killTarget,
                killProgress = mission.killProgress,
            )
    }
}

/**
 * Serializable mirror of one [OwnedShip] in the fleet (UC09 AC#5/#6).
 *
 * Mirrors the ship as flat scalar fields + string-keyed maps so the domain types stay annotation-free,
 * matching [StateSnapshotDto]. Self-contained: it stores both cargo and fuel **capacities** (not just
 * contents/level) so a recorded snapshot reconstructs the exact ship regardless of later catalog/​type
 * retuning — a recorded capacity already equals what [com.orbitalfrontier.outfit.ShipStats] derived at
 * record time, so the replay assertion holds. [shipType] is the [ShipTypeId] slug (resolved via
 * [ShipRoster]; an unknown slug degrades to the starter type); [loadout] is `category-name → (slot
 * index → upgrade-id slug)`.
 */
@Serializable
data class OwnedShipDto(
    val id: Long,
    val shipType: String,
    val posX: Float,
    val posY: Float,
    val velX: Float,
    val velY: Float,
    val headingRadians: Float,
    val angularVelocity: Float,
    val cargoCapacity: Int,
    val cargo: Map<String, Int> = emptyMap(),
    val fuelLevel: Float,
    val fuelCapacity: Float,
    val loadout: Map<String, Map<Int, String>> = emptyMap(),
    /**
     * The crew **count** aboard this ship (UC11 AC#1) — a persisted per-ship number (capacity is the
     * derived stat [com.orbitalfrontier.outfit.ShipStats.crewCapacity], never stored). Defaulted to 0
     * so every pre-UC11 artifact (recorded before crew existed) decodes back as an uncrewed ship and
     * the pre-UC11 fixtures replay byte-identically — mirroring the additive v8 -> v9 save migration.
     */
    val crew: Int = 0,
    /**
     * This ship's persisted **per-section combat damage** (UC13 AC#3) — `[ShipSection.name]` → current HP,
     * with an **absent** section meaning pristine (full derived HP), exactly like the domain
     * [com.orbitalfrontier.ship.OwnedShip.sectionDamage]. String-keyed (the stable enum name, not its
     * ordinal) so the on-disk form is diffable and survives enum reordering, matching [cargo]. Max HP is
     * the derived stat [com.orbitalfrontier.outfit.ShipStats.sectionHp] and is never stored. Defaulted
     * **empty** (pristine) so every pre-UC13 artifact (recorded before combat) decodes back as an
     * undamaged ship and the pre-UC13 fixtures replay byte-identically — mirroring the additive v10 -> v11
     * `ship_section_damage` migration (a migrated save has no rows ⇒ pristine).
     */
    val sectionDamage: Map<String, Int> = emptyMap(),
) {
    /** Reconstruct the domain [OwnedShip]. */
    fun toOwnedShip(): OwnedShip =
        OwnedShip(
            id = ShipId(id),
            type = ShipRoster.byId(ShipTypeId(shipType)) ?: ShipRoster.STARTER,
            kinematics =
                ShipKinematics(
                    position = Vec2(posX, posY),
                    velocity = Vec2(velX, velY),
                    headingRadians = headingRadians,
                    angularVelocity = angularVelocity,
                ),
            cargo = Cargo(cargo.mapKeys { ResourceType.valueOf(it.key) }, cargoCapacity),
            fuel = Fuel(level = fuelLevel, capacity = fuelCapacity),
            loadout = parseLoadout(loadout),
            crew = crew,
            sectionDamage = parseSectionDamage(sectionDamage),
        )

    companion object {
        /** Snapshot [ship] into its serializable form. */
        fun from(ship: OwnedShip): OwnedShipDto =
            OwnedShipDto(
                id = ship.id.value,
                shipType = ship.type.id.value,
                posX = ship.kinematics.position.x,
                posY = ship.kinematics.position.y,
                velX = ship.kinematics.velocity.x,
                velY = ship.kinematics.velocity.y,
                headingRadians = ship.kinematics.headingRadians,
                angularVelocity = ship.kinematics.angularVelocity,
                cargoCapacity = ship.cargo.capacity,
                cargo = ship.cargo.contents.mapKeys { it.key.name },
                fuelLevel = ship.fuel.level,
                fuelCapacity = ship.fuel.capacity,
                loadout =
                    ship.loadout.slots
                        .mapKeys { (category, _) -> category.name }
                        .mapValues { (_, slots) -> slots.mapValues { (_, upgradeId) -> upgradeId.value } },
                crew = ship.crew,
                // Section name -> current HP; an undamaged ship writes no entries (canonical: absent = pristine).
                sectionDamage = ship.sectionDamage.mapKeys { it.key.name },
            )

        /** Rebuild a [Loadout] from its string-keyed form; an unknown category/​blank id is skipped. */
        private fun parseLoadout(raw: Map<String, Map<Int, String>>): Loadout {
            val slots = LinkedHashMap<SlotCategory, Map<Int, UpgradeId>>()
            for ((categoryName, indexed) in raw) {
                val category = SlotCategory.entries.firstOrNull { it.name == categoryName } ?: continue
                val inner = LinkedHashMap<Int, UpgradeId>()
                for ((slotIndex, upgradeId) in indexed) {
                    if (upgradeId.isNotBlank()) inner[slotIndex] = UpgradeId(upgradeId)
                }
                if (inner.isNotEmpty()) slots[category] = inner
            }
            return Loadout(slots)
        }

        /**
         * Rebuild a [SectionDamage] from its string-keyed form; an unknown section name is skipped (a
         * removed/typo'd enum constant degrades gracefully rather than crashing the load). Empty ⇒ pristine.
         */
        private fun parseSectionDamage(raw: Map<String, Int>): SectionDamage {
            if (raw.isEmpty()) return SectionDamages.PRISTINE
            val result = LinkedHashMap<ShipSection, Int>()
            for ((name, hp) in raw) {
                val section = ShipSection.entries.firstOrNull { it.name == name } ?: continue
                result[section] = hp
            }
            return result
        }
    }
}
