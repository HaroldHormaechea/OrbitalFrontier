package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.world.PoiId

/**
 * Stable identity of a mission instance (UC12).
 *
 * A mission id is a **deterministic, stable string** derived from the static authored world (the
 * source + station + slot it was generated for, e.g. `"board:alpha-station:mining"` or
 * `"radio:beta-station"`) — see [MissionGenerator]. Stability is load-bearing: available offers are
 * **not persisted**; they are regenerated on load and filtered against the ids of the accepted /
 * terminal missions that *are* persisted (the regenerate-and-filter invariant, ADR 0011). A given
 * offer therefore always carries the same id across runs, so once accepted (or completed/failed) it
 * never re-appears in the offer list.
 *
 * A thin value type around the string so mission references are type-safe and never bare strings at
 * call sites. Pure — no engine types — so the whole `mission` package is JVM-testable (ADR 0001).
 */
@JvmInline
value class MissionId(val value: String) {
    init {
        require(value.isNotBlank()) { "MissionId must not be blank" }
    }
}

/**
 * The handcrafted MVP mission types (UC12 AC#1, UC41; docs/design/missions.md). A closed set modelled
 * as an `enum` (coding-guidelines § O). New types are added by introducing a new constant; every
 * exhaustive `when` over this enum (turn-in resolution, the HUD objective line, the board row) is then
 * updated to cover it — adding a constant without covering it fails to compile, which is the guard.
 */
enum class MissionType {
    /** Gather a quota of a resource (from asteroid fields) and turn it in at a mission board. */
    MINING,

    /** Pick up a (virtual) parcel at station A and deliver it to station B before the timer expires. */
    COURIER,

    /**
     * Destroy a quota of hostiles in a dedicated authored encounter zone (UC41). A bounty has no manual
     * turn-in: while it is [MissionStatus.ACTIVE] the orchestrator edge-spawns the contracted hostiles in
     * the mission's [Mission.targetZoneId] zone, player kills attributed to that zone raise
     * [Mission.killProgress], and reaching [Mission.killTarget] auto-completes and pays the bounty (via the
     * pure [BountyTracking.applyKills]). There is no failure timer (mining-style) and no abandon order.
     */
    BOUNTY,
}

/**
 * A mission's lifecycle state (UC12 AC#3): offered → accepted → terminal. A closed set as an `enum`.
 *
 * [AVAILABLE] missions are the offer list (board/radio); they are **not** persisted — they are
 * regenerated on load. [ACTIVE] missions are accepted and in progress. [COMPLETED]/[FAILED] are
 * terminal: they are persisted so their ids keep filtering out the corresponding offer forever (a
 * completed/failed mission never re-offers — ADR 0011).
 */
enum class MissionStatus {
    AVAILABLE,
    ACTIVE,
    COMPLETED,
    FAILED,
}

/**
 * Where a mission offer came from (UC12 AC#2): a station **mission board** (accepted while docked) or
 * a ship **radio broadcast** (surfaced range-based while in flight). A closed set as an `enum`. The
 * source is part of the mission's identity namespace (see [MissionId]) so a board offer and a radio
 * offer never collide on id.
 */
enum class MissionSource {
    BOARD,
    RADIO,
}

/**
 * An immutable mission instance — a handcrafted [type] procedurally instanced from the static
 * authored world (UC12 AC#1/#2; docs/design/missions.md).
 *
 * Pure value (coding-guidelines § immutability) with **no engine types**, so the whole mission system
 * is JVM-testable (UC12 AC#6) and composes into the immutable [com.orbitalfrontier.world.WorldState]
 * snapshot the autosave thread reads. Every state transition (accept/turn-in/advance) returns a new
 * [Mission]; nothing mutates in place.
 *
 * **Type-specific fields are flat + nullable** (rather than a sealed payload) so the persistence layer
 * maps a mission to one flat `mission` table row directly. The [type] selects which fields are
 * meaningful:
 *  - **MINING** uses [quotaResource] + [quotaUnits]: the player gathers [quotaUnits] of
 *    [quotaResource] into the cargo hold and turns it in at any mission-board station.
 *  - **COURIER** uses [pickup] + [destination] + [remainingTicks] + [pickedUp]: the parcel is
 *    **virtual** (tracked here, never in the [com.orbitalfrontier.economy.Cargo] hold — so it can't be
 *    sold or take a hold slot). It is picked up at [pickup] (auto-loaded on docking there) and
 *    delivered at [destination]; [remainingTicks] is the **tick-based** model timer (decremented by
 *    [Missions.advance]); at 0 the mission flips to [MissionStatus.FAILED] with a fixed credit penalty.
 *
 * [rewardCredits] is the credit payout on completion; [rewardResource]/[rewardResourceUnits] are an
 * optional resource bonus added to the hold on turn-in (null/0 = credits-only, the MVP default).
 *
 * **UC14 — factions & reputation (all defaulted/additive, so every pre-UC14 call site is unchanged).**
 *  - [factionId] is the faction that **credits the reputation gain** on completion (UC14 AC#4): the
 *    mission's source-station faction for a board/mining mission, and the **pickup (source) station's**
 *    faction for a courier (credited on delivery even though the parcel is dropped at the destination).
 *    `null` ⇒ a faction-less mission (no reputation effect). Stamped by [MissionGenerator] from static
 *    world state only — never from runtime reputation — so generation stays a pure function of the
 *    authored world (the determinism invariant, ADR 0011/0013).
 *  - [unlockFaction] + [unlockThreshold] are the **reputation gate** (UC14 AC#3): an offer with a
 *    non-null [unlockFaction] is surfaced only when the player's standing with it is `>=`
 *    [unlockThreshold]. `null` unlockFaction (the default, and every pre-UC14 offer) ⇒ ungated, always
 *    available. The gate is applied by the SEPARATE pure [com.orbitalfrontier.faction.ReputationGate]
 *    filter AFTER generation + the takenIds filter — it changes only an offer's *visibility*, never its
 *    id or content, so adding a gated offer does not perturb any existing offer's bytes.
 */
data class Mission(
    val id: MissionId,
    val type: MissionType,
    val source: MissionSource,
    val status: MissionStatus,
    val rewardCredits: Long,
    val rewardResource: ResourceType? = null,
    val rewardResourceUnits: Int = 0,
    // MINING type params.
    val quotaResource: ResourceType? = null,
    val quotaUnits: Int = 0,
    // COURIER type params. The parcel is VIRTUAL — tracked here, never in the cargo hold.
    val pickup: PoiId? = null,
    val destination: PoiId? = null,
    val remainingTicks: Int = 0,
    val pickedUp: Boolean = false,
    // UC14 faction attribution + reputation gate (defaulted ⇒ additive / back-compatible).
    val factionId: FactionId? = null,
    val unlockFaction: FactionId? = null,
    val unlockThreshold: Int = 0,
    // UC41 BOUNTY type params (defaulted ⇒ additive / back-compatible — a non-bounty mission leaves these
    // at their defaults, which is what the persistence layer and every pre-UC41 fixture read back).
    //  - [targetZoneId] is the authored encounter-zone id the bounty contracts: the offer's match key, the
    //    spawn zoneId handed to [com.orbitalfrontier.combat.EncounterSpawner.missionSpawn], AND the
    //    kill-attribution key (a [com.orbitalfrontier.combat.CombatState.zoneId] match) — equal by
    //    construction so all three never disagree. `null` for a non-bounty mission. Plain String (no
    //    combat import) so the `mission` package stays engine-free / JVM-testable.
    //  - [killTarget] is how many hostiles must be destroyed to complete; [killProgress] is how many have
    //    been (durably persisted, raised by [BountyTracking.applyKills], capped at [killTarget]).
    val targetZoneId: String? = null,
    val killTarget: Int = 0,
    val killProgress: Int = 0,
) {
    init {
        require(rewardCredits >= 0) { "Mission $id rewardCredits must not be negative: $rewardCredits" }
        require(rewardResourceUnits >= 0) { "Mission $id rewardResourceUnits must not be negative: $rewardResourceUnits" }
        require(quotaUnits >= 0) { "Mission $id quotaUnits must not be negative: $quotaUnits" }
        require(remainingTicks >= 0) { "Mission $id remainingTicks must not be negative: $remainingTicks" }
        require(killTarget >= 0) { "Mission $id killTarget must not be negative: $killTarget" }
        require(killProgress in 0..killTarget) { "Mission $id killProgress must be in 0..$killTarget: $killProgress" }
    }

    /** True when this mission is a terminal outcome (completed or failed) — kept for offer filtering. */
    val isTerminal: Boolean get() = status == MissionStatus.COMPLETED || status == MissionStatus.FAILED
}
