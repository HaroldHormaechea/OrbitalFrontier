package com.orbitalfrontier.faction

/**
 * Authored balancing tunables for the reputation system (UC14) — the reputation analogue of
 * [com.orbitalfrontier.mission.MissionParams].
 *
 * Pure data injected into the reputation-changing logic ([com.orbitalfrontier.mission.Missions.resolve]
 * grants [missionCompleteDelta] on a turn-in; [com.orbitalfrontier.mission.Missions.advance] applies
 * [courierFailDelta] on a courier expiry) and into the clamp ([min]..[max]) [Reputation.with] enforces.
 * Injecting the params keeps the reputation logic a pure function of its inputs (the same params + the
 * same actions always yield the same standings), so the whole system is deterministic and JVM-testable
 * (UC14 AC#5).
 *
 * **Pinnable per playthrough.** Like [MissionParams] / the other `*Params`, a recorded playthrough can
 * serialize the exact [ReputationParams] it ran under, so a later default tuning change can't silently
 * invalidate an old replay. All fields are plain public data for that reason.
 *
 * **Threshold ≤ delta invariant (for the AC#6 playthrough).** The authored gated offers
 * ([com.orbitalfrontier.world.MvpSectorMap]) set their `unlockThreshold` so that **one**
 * mission-complete gain at a faction is enough to open that faction's gate — i.e. the gate threshold is
 * `<= missionCompleteDelta`. This is what lets the recorded playthrough complete a single faction
 * mission and then see a newly available gated offer.
 *
 * Every `[TUNE]` constant is a placeholder for balancing; none affects determinism, which is fixed by
 * the (pure) generation + resolution algorithms.
 */
data class ReputationParams(
    /** Reputation gained with a mission's faction when that mission is completed (turned in). [TUNE] */
    val missionCompleteDelta: Int = DEFAULT_MISSION_COMPLETE_DELTA,
    /** Reputation lost with a courier's (pickup-station) faction when the courier times out. [TUNE] */
    val courierFailDelta: Int = DEFAULT_COURIER_FAIL_DELTA,
    /**
     * Reputation change applied with a destroyed hostile's faction per faction-affiliated kill (UC43).
     * A loss (`<= 0`, like [courierFailDelta]): destroying a faction's ship sours the player's standing
     * with it through the existing [Reputation.with] seam (clamped at [min]). Unaffiliated hostiles (a
     * null [com.orbitalfrontier.combat.HostileArchetype.factionId]) apply no change. [TUNE]
     */
    val combatKillDelta: Int = DEFAULT_COMBAT_KILL_DELTA,
    /** Lower clamp bound for any faction standing (hostile floor). [TUNE] */
    val min: Int = DEFAULT_MIN,
    /** Upper clamp bound for any faction standing (allied ceiling). [TUNE] */
    val max: Int = DEFAULT_MAX,
) {
    init {
        require(min <= max) { "reputation bounds invalid: $min..$max" }
        require(missionCompleteDelta >= 0) { "missionCompleteDelta must be >= 0: $missionCompleteDelta" }
        require(courierFailDelta <= 0) { "courierFailDelta must be <= 0 (a loss): $courierFailDelta" }
        require(combatKillDelta <= 0) { "combatKillDelta must be <= 0 (a loss): $combatKillDelta" }
    }

    companion object {
        const val DEFAULT_MISSION_COMPLETE_DELTA: Int = 10
        const val DEFAULT_COURIER_FAIL_DELTA: Int = -15
        const val DEFAULT_COMBAT_KILL_DELTA: Int = -5
        const val DEFAULT_MIN: Int = -100
        const val DEFAULT_MAX: Int = 100
    }
}
