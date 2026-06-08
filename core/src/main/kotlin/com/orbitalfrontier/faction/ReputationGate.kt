package com.orbitalfrontier.faction

import com.orbitalfrontier.mission.Mission

/**
 * The pure reputation gate (UC14 AC#3) — decides whether a mission offer is currently **available** to
 * the player given their standing. A side-effect-free function of its inputs, no engine types, so it
 * is fully JVM-testable (UC14 AC#5) and identical inputs always yield the same answer.
 *
 * **CRITICAL — gating is a SEPARATE filter, applied AFTER generation + the takenIds filter** (the UC14
 * determinism invariant; restated in ADR 0013). [com.orbitalfrontier.mission.MissionGenerator] stays a
 * pure function of *static* world state — it stamps each offer with its source-station faction and the
 * authored gate, but it does **not** consult reputation, so adding a gated offer never perturbs the
 * bytes of any existing offer (each offer is independently string-seeded). Reputation only changes an
 * offer's **visibility**, never its id or content: this filter is applied at the three symmetric
 * post-takenIds sites (board, radio, and the simulation) to drop offers the player hasn't unlocked.
 *
 * **Identity when nothing is gated.** An offer with no `unlockFaction` is always available (the common
 * case, and every pre-UC14 offer), so this filter is the identity over an un-gated offer list — back-
 * compatible by construction. A gated offer is available only when the player's standing with its
 * [Mission.unlockFaction] is at or above its [Mission.unlockThreshold].
 */
object ReputationGate {
    /**
     * True when [mission] is available to a player with [reputation]: always true for an un-gated offer
     * (no [Mission.unlockFaction]); for a gated offer, true iff the player's standing with that faction
     * is `>= mission.unlockThreshold`.
     */
    fun isAvailable(
        mission: Mission,
        reputation: Reputation,
    ): Boolean {
        val gateFaction = mission.unlockFaction ?: return true
        return reputation.valueFor(gateFaction) >= mission.unlockThreshold
    }
}
