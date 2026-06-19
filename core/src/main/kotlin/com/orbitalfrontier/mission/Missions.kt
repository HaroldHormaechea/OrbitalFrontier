package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.world.PoiId

/**
 * Pure, deterministic mission resolution (UC12 AC#3/#4/#6) — the mission analogue of
 * [com.orbitalfrontier.economy.Trading] / [com.orbitalfrontier.crew.Hiring].
 *
 * Both [resolve] (accept / turn-in, plus automatic courier pickup) and [advance] (the per-tick
 * courier timer) are side-effect-free functions of their inputs: identical inputs always yield an
 * identical [MissionResult], with no I/O, no engine types, and **no production RNG or wall clock**, so
 * they slot into the deterministic simulation/replay path and are fully JVM-unit-testable (UC12 AC#6).
 * They do **not** mutate anything — the caller (the play screen on device, the simulation on the JVM)
 * applies the returned values.
 *
 * **No-op ⇒ same instances.** Any order that cannot apply (offer gone, quota not held, wrong station,
 * not picked up) — and an [advance] with no active courier — returns the **input** [MissionLog],
 * credits, cargo and reputation unchanged with `changed = false`, so the caller cheaply detects
 * "nothing happened" and skips the autosave, and pre-UC12 fixtures (empty log) thread the same
 * instances through and step byte-identically.
 *
 * **UC14 — reputation is action-driven, applied here.** Reputation changes only through these pure
 * resolvers (never as a side effect of generation/gating): a faction mission's turn-in grants
 * [ReputationParams.missionCompleteDelta] to its [Mission.factionId], and a courier expiry applies
 * [ReputationParams.courierFailDelta] to the courier's faction. A faction-less mission, and every
 * non-completing outcome, leaves reputation as the same input instance — so the determinism invariant
 * (same-instance on no-op, byte-identical pre-UC14 replay) holds for reputation exactly as it does for
 * credits/cargo.
 */
object Missions {
    /**
     * Resolve one mission [order] against the current [log], the currently-surfaced [offers] (board
     * offers while docked, radio offers while in flight), and the world context.
     *
     * Two things happen, in order:
     *  1. **Automatic courier pickup.** When [dockedStation] is non-null, any ACTIVE courier whose
     *     pickup station is the docked station and which is not yet picked up has its (virtual) parcel
     *     loaded (`pickedUp = true`). This needs no explicit order — docking at A grabs the parcel.
     *  2. **The order:**
     *     - [MissionOrder.None] — nothing further.
     *     - [MissionOrder.Accept] — if the id is among [offers] and not already taken, the offer is
     *       moved into the accepted log as [MissionStatus.ACTIVE]. A courier accepted **while docked at
     *       its own pickup** is picked up immediately; a radio courier accepted in flight is not (it is
     *       picked up later on docking at the pickup).
     *     - [MissionOrder.TurnIn] — if the id is an ACTIVE mission and its completion conditions hold,
     *       it flips to [MissionStatus.COMPLETED] and the reward is granted:
     *         * **MINING** — only at a mission-board station ([dockedStation] non-null) and only when the
     *           hold carries at least the quota; the quota is consumed from [cargo] and the credits (plus
     *           any optional resource bonus) are granted.
     *         * **COURIER** — only when docked at the destination and the parcel has been picked up; the
     *           reward credits (plus any optional resource bonus) are granted. The virtual parcel just
     *           vanishes (it was never in the hold).
     *
     * A completing **turn-in** of a mission with a [Mission.factionId] also grants
     * [ReputationParams.missionCompleteDelta] to that faction in [reputation] (UC14 AC#4), clamped to
     * the params' bounds; every other path returns the input [reputation] instance unchanged.
     *
     * @param offers the currently-surfaced AVAILABLE offers; an [MissionOrder.Accept] is only honoured
     *   for an id present here and absent from [MissionLog.takenIds].
     */
    fun resolve(
        log: MissionLog,
        offers: List<Mission>,
        order: MissionOrder,
        dockedStation: PoiId?,
        cargo: Cargo,
        credits: Long,
        params: MissionParams = MissionParams(),
        reputation: Reputation = Reputation.EMPTY,
        reputationParams: ReputationParams = ReputationParams(),
    ): MissionResult {
        var accepted = log.accepted
        var changed = false
        var workingCargo = cargo
        var workingCredits = credits
        var workingReputation = reputation

        // 1) Automatic courier pickup on docking at the pickup station.
        if (dockedStation != null) {
            val pickedUp =
                accepted.map { m ->
                    if (m.type == MissionType.COURIER && m.status == MissionStatus.ACTIVE &&
                        !m.pickedUp && m.pickup == dockedStation
                    ) {
                        m.copy(pickedUp = true)
                    } else {
                        m
                    }
                }
            if (pickedUp != accepted) {
                accepted = pickedUp
                changed = true
            }
        }

        // 2) Apply the order.
        when (order) {
            MissionOrder.None -> {}
            is MissionOrder.Accept -> {
                val takenIds = accepted.mapTo(HashSet()) { it.id }
                if (order.id !in takenIds) {
                    val offer = offers.firstOrNull { it.id == order.id }
                    if (offer != null) {
                        val pickedUpNow =
                            offer.type == MissionType.COURIER && dockedStation != null && dockedStation == offer.pickup
                        accepted = accepted + offer.copy(status = MissionStatus.ACTIVE, pickedUp = pickedUpNow)
                        changed = true
                    }
                }
            }
            is MissionOrder.TurnIn -> {
                val index = accepted.indexOfFirst { it.id == order.id && it.status == MissionStatus.ACTIVE }
                if (index >= 0) {
                    val mission = accepted[index]
                    val outcome = tryComplete(mission, dockedStation, workingCargo, workingCredits)
                    if (outcome != null) {
                        workingCargo = outcome.cargo
                        workingCredits = outcome.credits
                        // UC14: a completing turn-in credits reputation to the mission's faction (if any),
                        // clamped to the params' bounds. A faction-less mission leaves reputation as-is.
                        val faction = mission.factionId
                        if (faction != null) {
                            workingReputation =
                                workingReputation.with(
                                    faction,
                                    reputationParams.missionCompleteDelta,
                                    reputationParams.min,
                                    reputationParams.max,
                                )
                        }
                        accepted =
                            accepted.toMutableList().also { it[index] = mission.copy(status = MissionStatus.COMPLETED) }
                        changed = true
                    }
                }
            }
        }

        if (!changed) return MissionResult(log, credits, cargo, reputation, false)
        return MissionResult(MissionLog(log.available, accepted), workingCredits, workingCargo, workingReputation, true)
    }

    /**
     * Advance the per-tick courier timers by one model tick (UC12 AC#4). Each ACTIVE courier's
     * `remainingTicks` is decremented; one that reaches 0 flips to [MissionStatus.FAILED] and a fixed
     * [MissionParams.courierFailurePenalty] is deducted from [credits] (clamped at 0 — the wallet never
     * goes negative). Mining missions have no timer and are untouched.
     *
     * **Returns the input instances unchanged with `changed = false` when there is no ACTIVE courier**
     * (the common path, and every pre-UC12 fixture) — so the step stays byte-identical. When at least
     * one courier is active the returned [MissionResult.log] always reflects this tick's decrements (so
     * the caller folds it to advance the timer), but [MissionResult.changed] is `true` **only when a
     * courier actually expired this tick** — the terminal transition worth an event autosave. A plain
     * decrement updates the log yet reports `changed = false`, keeping per-tick autosave I/O off the
     * frame budget.
     */
    fun advance(
        log: MissionLog,
        credits: Long,
        cargo: Cargo,
        params: MissionParams = MissionParams(),
        reputation: Reputation = Reputation.EMPTY,
        reputationParams: ReputationParams = ReputationParams(),
    ): MissionResult {
        val hasActiveCourier =
            log.accepted.any { it.type == MissionType.COURIER && it.status == MissionStatus.ACTIVE }
        if (!hasActiveCourier) return MissionResult(log, credits, cargo, reputation, false)

        var expired = false
        var workingCredits = credits
        var workingReputation = reputation
        val updated =
            log.accepted.map { mission ->
                if (mission.type == MissionType.COURIER && mission.status == MissionStatus.ACTIVE) {
                    val next = mission.remainingTicks - 1
                    if (next <= 0) {
                        expired = true
                        workingCredits = (workingCredits - params.courierFailurePenalty).coerceAtLeast(0)
                        // UC14: a courier timeout also costs reputation with its (pickup-station) faction.
                        val faction = mission.factionId
                        if (faction != null) {
                            workingReputation =
                                workingReputation.with(
                                    faction,
                                    reputationParams.courierFailDelta,
                                    reputationParams.min,
                                    reputationParams.max,
                                )
                        }
                        mission.copy(remainingTicks = 0, status = MissionStatus.FAILED)
                    } else {
                        mission.copy(remainingTicks = next)
                    }
                } else {
                    mission
                }
            }
        return MissionResult(MissionLog(log.available, updated), workingCredits, cargo, workingReputation, expired)
    }

    /** The cargo + credits a successful turn-in produces, or null when the mission cannot be completed. */
    private fun tryComplete(
        mission: Mission,
        dockedStation: PoiId?,
        cargo: Cargo,
        credits: Long,
    ): Outcome? =
        when (mission.type) {
            MissionType.MINING -> {
                val resource = mission.quotaResource
                // Mining turn-in: at any mission-board station (docked), with the full quota in the hold.
                if (dockedStation == null || resource == null) {
                    null
                } else {
                    val held = cargo.contents[resource] ?: 0
                    if (held < mission.quotaUnits) {
                        null
                    } else {
                        val afterQuota = cargo.remove(resource, mission.quotaUnits)
                        Outcome(grantReward(mission, afterQuota), credits + mission.rewardCredits)
                    }
                }
            }
            MissionType.COURIER -> {
                // Courier turn-in: docked at the destination, parcel picked up. The parcel is virtual.
                if (dockedStation != null && dockedStation == mission.destination && mission.pickedUp) {
                    Outcome(grantReward(mission, cargo), credits + mission.rewardCredits)
                } else {
                    null
                }
            }
            // UC41: a BOUNTY has NO manual turn-in — it auto-completes and pays the moment its kill quota
            // is met, via the pure [BountyTracking.applyKills] folded into the combat step. A TurnIn order
            // therefore never resolves one (it stays ACTIVE until its kills land), so this is always null.
            MissionType.BOUNTY -> null
        }

    /** Add the optional resource bonus (if any) to [cargo], clamped to the hold's free space. */
    private fun grantReward(
        mission: Mission,
        cargo: Cargo,
    ): Cargo {
        val resource = mission.rewardResource ?: return cargo
        if (mission.rewardResourceUnits <= 0) return cargo
        return cargo.add(resource, mission.rewardResourceUnits).cargo
    }

    /** Internal carrier for a turn-in's resulting cargo + credits (private to keep [tryComplete] tidy). */
    private data class Outcome(val cargo: Cargo, val credits: Long)
}
