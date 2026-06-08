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
 * The two handcrafted MVP mission types (UC12 AC#1; docs/design/missions.md). A closed set modelled
 * as an `enum` (coding-guidelines § O): MVP launches with exactly these two; combat missions are a
 * later phase. New types are added by introducing a new constant, never by editing a central `when`.
 */
enum class MissionType {
    /** Gather a quota of a resource (from asteroid fields) and turn it in at a mission board. */
    MINING,

    /** Pick up a (virtual) parcel at station A and deliver it to station B before the timer expires. */
    COURIER,
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
) {
    init {
        require(rewardCredits >= 0) { "Mission $id rewardCredits must not be negative: $rewardCredits" }
        require(rewardResourceUnits >= 0) { "Mission $id rewardResourceUnits must not be negative: $rewardResourceUnits" }
        require(quotaUnits >= 0) { "Mission $id quotaUnits must not be negative: $quotaUnits" }
        require(remainingTicks >= 0) { "Mission $id remainingTicks must not be negative: $remainingTicks" }
    }

    /** True when this mission is a terminal outcome (completed or failed) — kept for offer filtering. */
    val isTerminal: Boolean get() = status == MissionStatus.COMPLETED || status == MissionStatus.FAILED
}
