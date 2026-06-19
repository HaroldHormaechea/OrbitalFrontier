package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams

/**
 * Pure, deterministic bounty-kill resolution (UC41) — the combat-mission analogue of
 * [Missions.resolve] / [Missions.advance]. The single place a destroyed hostile is folded into an
 * ACTIVE [MissionType.BOUNTY]'s progress and the bounty auto-completed + paid (AC#3: no manual turn-in).
 *
 * [applyKills] is a side-effect-free function of its inputs: identical inputs always yield an identical
 * [MissionResult], with no I/O, no engine types and no RNG, so it slots into the deterministic
 * simulation/replay path and is fully JVM-unit-testable. It does **not** mutate anything — the caller
 * (the play screen on device, the simulation on the JVM) applies the returned values.
 *
 * **No-op ⇒ same instances.** When [kills] is 0 or no ACTIVE bounty's [Mission.targetZoneId] matches
 * [zoneId], the **input** [log], [credits], [cargo] and [reputation] are returned unchanged with
 * `changed = false`, so the caller cheaply detects "nothing happened" with a reference check and skips
 * the autosave, and pre-UC41 fixtures (no bounty in the log) thread the same instances through and step
 * byte-identically.
 *
 * **Reputation is threaded UNCHANGED here (the UC43 seam).** A bounty completion will grant reputation
 * once UC43 (combat reputation) lands; until then [reputation] is returned as the same input instance
 * and [reputationParams] is accepted only to keep the signature stable for that future wiring.
 */
object BountyTracking {
    /**
     * Fold [kills] hostiles destroyed in the encounter zone [zoneId] into every ACTIVE [MissionType.BOUNTY]
     * whose [Mission.targetZoneId] equals [zoneId]: each such bounty's [Mission.killProgress] is raised by
     * [kills] (capped at its [Mission.killTarget]); a bounty that reaches its [Mission.killTarget] flips to
     * [MissionStatus.COMPLETED] and its [Mission.rewardCredits] are added to [credits] (auto-complete-and-pay,
     * AC#3). [cargo] is threaded through unchanged (a bounty grants no cargo); [reputation] likewise (UC43 seam).
     *
     * @param zoneId the [com.orbitalfrontier.combat.CombatState.zoneId] of the encounter the kills happened in,
     *   captured BEFORE the combat step (a destroyed/cleared encounter resets the zone id). The bounty's
     *   authored target-zone id, the spawn zone id and this attribution key are equal by construction.
     */
    fun applyKills(
        log: MissionLog,
        zoneId: String,
        kills: Int,
        credits: Long,
        cargo: Cargo,
        reputation: Reputation = Reputation.EMPTY,
        @Suppress("UNUSED_PARAMETER") reputationParams: ReputationParams = ReputationParams(),
    ): MissionResult {
        if (kills <= 0 || zoneId.isEmpty()) return MissionResult(log, credits, cargo, reputation, false)

        var changed = false
        var workingCredits = credits
        val updated =
            log.accepted.map { mission ->
                if (mission.type == MissionType.BOUNTY && mission.status == MissionStatus.ACTIVE &&
                    mission.targetZoneId == zoneId && mission.killProgress < mission.killTarget
                ) {
                    val nextProgress = (mission.killProgress + kills).coerceAtMost(mission.killTarget)
                    changed = true
                    if (nextProgress >= mission.killTarget) {
                        // Final kill: auto-complete and pay the bounty (no return-to-station turn-in, AC#3).
                        workingCredits += mission.rewardCredits
                        mission.copy(killProgress = nextProgress, status = MissionStatus.COMPLETED)
                    } else {
                        // Progress only: the durable kill count advances; the bounty stays ACTIVE.
                        mission.copy(killProgress = nextProgress)
                    }
                } else {
                    mission
                }
            }

        if (!changed) return MissionResult(log, credits, cargo, reputation, false)
        return MissionResult(MissionLog(log.available, updated), workingCredits, cargo, reputation, true)
    }
}
