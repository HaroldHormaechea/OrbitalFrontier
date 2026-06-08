package com.orbitalfrontier.mission

/**
 * Authored balancing tunables for the mission system (UC12) — the mission analogue of
 * [com.orbitalfrontier.economy.MiningParams] / [com.orbitalfrontier.economy.FuelParams].
 *
 * Pure data injected into [MissionGenerator] (offer instancing), [Missions.resolve] (rewards) and
 * [Missions.advance] (the courier timer + failure penalty) by constructor/parameter injection
 * (coding-guidelines § DIP), so the mission logic stays a pure function of its inputs — the same
 * params + the same static world always yield the same offers and outcomes, keeping the whole system
 * deterministic and JVM-testable (UC12 AC#6).
 *
 * **Pinnable per playthrough.** Like the other `*Params`, a recorded playthrough can serialize the
 * exact [MissionParams] it ran under (the Playthrough DTO's `missionConfig`), so a later default
 * tuning change can't silently invalidate an old replay. All fields are plain public data for that
 * reason.
 *
 * **The courier timer is tick-based** ([courierTickLimitMin]..[courierTickLimitMax] are counts of
 * model ticks, not seconds). [Missions.advance] decrements `remainingTicks` by one per call; the
 * device drives one such call per fixed wall-clock interval, the simulation one per fixed step — so
 * the model timer is the authority and the device countdown is merely its dt-paced surface (ADR 0011).
 *
 * Every `[TUNE]` constant is a placeholder for balancing (UC12 pitfall: reward balancing + radio
 * cadence are placeholders); none affects determinism, which is fixed by the generation algorithm.
 */
data class MissionParams(
    /** Minimum units a board mining quota asks for (clamped down to what a sector field can supply). [TUNE] */
    val boardMiningQuotaMin: Int = DEFAULT_BOARD_MINING_QUOTA_MIN,
    /** Maximum units a board mining quota asks for (clamped down to what a sector field can supply). [TUNE] */
    val boardMiningQuotaMax: Int = DEFAULT_BOARD_MINING_QUOTA_MAX,
    /** Minimum units a radio mining quota asks for. [TUNE] */
    val radioMiningQuotaMin: Int = DEFAULT_RADIO_MINING_QUOTA_MIN,
    /** Maximum units a radio mining quota asks for. [TUNE] */
    val radioMiningQuotaMax: Int = DEFAULT_RADIO_MINING_QUOTA_MAX,
    /** Flat credit base of a mining reward, before the per-unit bonus. [TUNE] */
    val miningRewardBase: Long = DEFAULT_MINING_REWARD_BASE,
    /** Extra credits a mining reward adds per unit of quota. [TUNE] */
    val miningRewardPerUnit: Long = DEFAULT_MINING_REWARD_PER_UNIT,
    /** Flat credit base of a courier reward, before the procedural span bonus. [TUNE] */
    val courierRewardBase: Long = DEFAULT_COURIER_REWARD_BASE,
    /** Maximum extra credits a courier reward adds (a procedural 0..span bonus). [TUNE] */
    val courierRewardSpan: Int = DEFAULT_COURIER_REWARD_SPAN,
    /** Minimum courier delivery deadline (model ticks). [TUNE] */
    val courierTickLimitMin: Int = DEFAULT_COURIER_TICK_LIMIT_MIN,
    /** Maximum courier delivery deadline (model ticks). [TUNE] */
    val courierTickLimitMax: Int = DEFAULT_COURIER_TICK_LIMIT_MAX,
    /** Fixed credit penalty applied when a courier mission times out (predefined consequence, AC#4). [TUNE] */
    val courierFailurePenalty: Long = DEFAULT_COURIER_FAILURE_PENALTY,
    /** Radio broadcast reach (world-units): a radio offer surfaces within this range of its source. [TUNE] */
    val radioRange: Float = DEFAULT_RADIO_RANGE,
) {
    init {
        require(boardMiningQuotaMin in 1..boardMiningQuotaMax) {
            "boardMiningQuota range invalid: $boardMiningQuotaMin..$boardMiningQuotaMax"
        }
        require(radioMiningQuotaMin in 1..radioMiningQuotaMax) {
            "radioMiningQuota range invalid: $radioMiningQuotaMin..$radioMiningQuotaMax"
        }
        require(miningRewardBase >= 0 && miningRewardPerUnit >= 0) { "mining reward tunables must be >= 0" }
        require(courierRewardBase >= 0 && courierRewardSpan >= 0) { "courier reward tunables must be >= 0" }
        require(courierTickLimitMin in 1..courierTickLimitMax) {
            "courierTickLimit range invalid: $courierTickLimitMin..$courierTickLimitMax"
        }
        require(courierFailurePenalty >= 0) { "courierFailurePenalty must be >= 0: $courierFailurePenalty" }
        require(radioRange > 0f) { "radioRange must be positive: $radioRange" }
    }

    companion object {
        const val DEFAULT_BOARD_MINING_QUOTA_MIN: Int = 8
        const val DEFAULT_BOARD_MINING_QUOTA_MAX: Int = 20
        const val DEFAULT_RADIO_MINING_QUOTA_MIN: Int = 6
        const val DEFAULT_RADIO_MINING_QUOTA_MAX: Int = 14
        const val DEFAULT_MINING_REWARD_BASE: Long = 200L
        const val DEFAULT_MINING_REWARD_PER_UNIT: Long = 25L
        const val DEFAULT_COURIER_REWARD_BASE: Long = 400L
        const val DEFAULT_COURIER_REWARD_SPAN: Int = 400
        const val DEFAULT_COURIER_TICK_LIMIT_MIN: Int = 60
        const val DEFAULT_COURIER_TICK_LIMIT_MAX: Int = 240
        const val DEFAULT_COURIER_FAILURE_PENALTY: Long = 150L
        const val DEFAULT_RADIO_RANGE: Float = 700f
    }
}
