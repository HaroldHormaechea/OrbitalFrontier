package com.orbitalfrontier.world

/**
 * A hand-authored **combat-bounty contract** the world offers (UC41) — "station [issuingStation] posts a
 * bounty to destroy [killTarget] hostiles in encounter zone [targetZoneId]". Pure value data in the
 * `world` package (it references only [PoiId] and a String zone id; no `combat`/`mission` import), so the
 * authored map ([MvpSectorMap]) declares the contracts and [com.orbitalfrontier.mission.MissionGenerator]
 * turns each into a [com.orbitalfrontier.mission.MissionType.BOUNTY] offer — the same separation by which
 * the world authors encounter zones / stations / markets and the systems consume them.
 *
 * [targetZoneId] is the authored [com.orbitalfrontier.combat.EncounterZone] id the bounty targets: it is
 * the generated offer's id key (`"bounty:<targetZoneId>"`), the spawn zone id, AND the kill-attribution
 * key — equal by construction so all three never disagree. The bounty's reward is derived from
 * [com.orbitalfrontier.mission.BountyParams] (`rewardBase + rewardPerKill × killTarget`), and its faction
 * attribution is the issuing station's own faction (read from the station), so the contract carries no
 * faction field of its own.
 */
data class BountyContract(
    val issuingStation: PoiId,
    val targetZoneId: String,
    val killTarget: Int,
) {
    init {
        require(targetZoneId.isNotBlank()) { "BountyContract targetZoneId must not be blank" }
        require(killTarget > 0) { "BountyContract killTarget must be positive: $killTarget" }
    }
}
