package com.orbitalfrontier.mission

/**
 * Authored balancing tunables for the combat-bounty mission type (UC41) — the bounty analogue of
 * [MissionParams]. Kept SEPARATE from [MissionParams] so the bounty `[TUNE]` values live in one place
 * and a later bounty rebalance never perturbs the mining/courier offer bytes (a different params object
 * means a different default set; pre-UC41 fixtures never reference this type at all).
 *
 * Pure data injected into [MissionGenerator] (the bounty offer's reward) by parameter injection
 * (coding-guidelines § DIP), so generation stays a pure function of its inputs — the same params + the
 * same authored bounty contracts always yield the same offer, keeping the system deterministic and
 * JVM-testable. A bounty reward is `rewardBase + rewardPerKill × killTarget`; there is no RNG (the
 * reward is fully determined by the contract + these params), so a bounty offer is inherently
 * byte-stable across runs.
 *
 * **Pinnable per playthrough.** Like the other `*Params`, a recorded playthrough can serialize the exact
 * [BountyParams] it ran under so a later default tuning change can't silently invalidate an old replay.
 */
data class BountyParams(
    /** Flat credit base of a bounty reward, before the per-kill bonus. [TUNE] */
    val rewardBase: Long = DEFAULT_REWARD_BASE,
    /** Extra credits a bounty reward adds per hostile in the kill quota. [TUNE] */
    val rewardPerKill: Long = DEFAULT_REWARD_PER_KILL,
) {
    init {
        require(rewardBase >= 0) { "bounty rewardBase must be >= 0: $rewardBase" }
        require(rewardPerKill >= 0) { "bounty rewardPerKill must be >= 0: $rewardPerKill" }
    }

    /** The credit payout for a bounty with [killTarget] kills required. */
    fun reward(killTarget: Int): Long = rewardBase + killTarget.toLong() * rewardPerKill

    companion object {
        const val DEFAULT_REWARD_BASE: Long = 500L
        const val DEFAULT_REWARD_PER_KILL: Long = 300L
    }
}
