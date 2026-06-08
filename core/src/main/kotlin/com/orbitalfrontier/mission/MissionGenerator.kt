package com.orbitalfrontier.mission

import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.Station

/**
 * Deterministic procedural instancing of mission offers from the **static authored world** (UC12
 * AC#1/#2; docs/design/missions.md, ADR 0011).
 *
 * Mission *types* are handcrafted (MINING / COURIER); individual *instances* are generated here as a
 * pure function of the fixed authored [SectorWorld] (its stations, their sectors, the asteroid-field
 * deposits) plus the authored [MissionParams]. Identical world + identical params always yield
 * identical offers, so:
 *  - the live game and the headless replay harness surface the same offers (UC12 AC#6), and
 *  - the **regenerate-and-filter** invariant holds: available offers are never persisted; on load they
 *    are regenerated and filtered against the persisted accepted/terminal ids ([MissionLog.takenIds]),
 *    so an accepted or completed/failed offer never re-appears (ADR 0011). This validity rests entirely
 *    on generation being a pure function of *static* authored state — if generation ever consulted
 *    mutable/runtime state, a regenerated offer could differ from the one that was accepted and the
 *    filter would break.
 *
 * **CRITICAL — determinism (ADR 0011).** Every procedural choice (which resource, quota size, courier
 * destination, deadline, reward jitter) is derived from an explicit **string-hash → LCG** seeded ONLY
 * by stable String primitives ([PoiId.value] / [SectorId.value] / [ResourceType.name]). It MUST NOT
 * use `enum`/data-class/identity `hashCode()` — those are run-dependent (identity hash) or
 * reorder-fragile, which would break byte-identical replay. Candidate lists are ordered by those same
 * stable strings (never by ordinal or hashCode) before an index is drawn. See [MissionRng] / [fnv1a].
 *
 * A board surfaces **one MINING offer and one COURIER offer** (the latter only when the world has a
 * second station to deliver to); radio broadcasts surface **one MINING offer per in-range station**.
 * The offer *content* is procedural; that each board always carries one of each type is a deliberate
 * authored guarantee so both types are reachable (and so a mining offer is always available to drive
 * the AC#7 recorded playthrough).
 */
object MissionGenerator {
    /**
     * Reputation a player must have with a faction station for its gated `:premium` offer to surface
     * (UC14 AC#3/#6). Authored `<=` the default [com.orbitalfrontier.faction.ReputationParams]
     * `missionCompleteDelta` (10), so a single completed faction mission opens the gate. [TUNE]
     */
    private const val PREMIUM_UNLOCK_THRESHOLD: Int = 10

    /** Flat extra credits a `:premium` offer pays over a regular courier of the same span. [TUNE] */
    private const val PREMIUM_REWARD_BONUS: Long = 600L

    /**
     * The mission-board offers for the station with [stationId] (UC12 AC#2): a MINING offer (when the
     * station's sector has minable resources) and a COURIER offer (when another station exists to
     * deliver to). All offers are [MissionStatus.AVAILABLE]; the caller filters them against
     * [MissionLog.takenIds] before surfacing. Empty if [stationId] is not a station in [world].
     */
    fun boardOffers(
        world: SectorWorld,
        stationId: PoiId,
        params: MissionParams = MissionParams(),
    ): List<Mission> {
        val (sectorId, station) = locateStation(world, stationId) ?: return emptyList()
        val offers = ArrayList<Mission>(3)

        miningOffer(
            world = world,
            sectorId = sectorId,
            id = MissionId("board:${stationId.value}:mining"),
            source = MissionSource.BOARD,
            seedKey = "board:${stationId.value}:mining",
            quotaMin = params.boardMiningQuotaMin,
            quotaMax = params.boardMiningQuotaMax,
            params = params,
            // UC14: stamp the board mining offer with the station's owning faction, so completing it
            // credits that faction's reputation (and, at Alpha, unlocks Alpha's gated premium offer).
            factionId = station.factionId,
        )?.let { offers += it }

        courierOffer(world, station, params)?.let { offers += it }

        // UC14: the gated `:premium` board offer for a faction station — a higher-paying courier that is
        // surfaced only once the player has earned enough reputation with the station's faction. Authored
        // here from STATIC world state only (its faction + gate are fixed); the SEPARATE ReputationGate
        // filter (applied after this list is built + the takenIds filter) decides visibility. Independently
        // string-seeded, so appending it never perturbs the bytes of the mining/courier offers above.
        premiumOffer(world, station, params)?.let { offers += it }

        return offers
    }

    /**
     * The ship-radio broadcast offers in [currentSector] within [MissionParams.radioRange] of
     * [shipPosition] (UC12 AC#2) — one MINING offer per in-range broadcasting station. Range-based and
     * deterministic, mirroring [com.orbitalfrontier.world.Scanning.contactsInRange]: it filters the
     * sector's stations by distance (authored order preserved) and instances one offer per source.
     * Empty in a sector with no minable resources or no station in range. The caller filters the result
     * against [MissionLog.takenIds].
     */
    fun radioOffers(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        params: MissionParams = MissionParams(),
    ): List<Mission> {
        val sector = world.sectorOrNull(currentSector) ?: return emptyList()
        val range = params.radioRange
        val inRange = sector.stations.filter { (shipPosition - it.position).length <= range }
        if (inRange.isEmpty()) return emptyList()

        val offers = ArrayList<Mission>(inRange.size)
        for (station in inRange) {
            miningOffer(
                world = world,
                sectorId = currentSector,
                id = MissionId("radio:${station.id.value}"),
                source = MissionSource.RADIO,
                seedKey = "radio:${station.id.value}",
                quotaMin = params.radioMiningQuotaMin,
                quotaMax = params.radioMiningQuotaMax,
                params = params,
                // UC14: a radio mining offer is credited to its broadcasting station's faction.
                factionId = station.factionId,
            )?.let { offers += it }
        }
        return offers
    }

    /**
     * Build one MINING offer for [sectorId] keyed by [id]/[seedKey], or null when the sector has no
     * minable resources. The quota resource is drawn from the sector's asteroid-field resources (so the
     * quota is gatherable nearby), and the quota size is clamped to what those fields actually hold (so
     * a mission is never impossible). Reward = base + perUnit × quota.
     */
    private fun miningOffer(
        world: SectorWorld,
        sectorId: SectorId,
        id: MissionId,
        source: MissionSource,
        seedKey: String,
        quotaMin: Int,
        quotaMax: Int,
        params: MissionParams,
        factionId: FactionId? = null,
    ): Mission? {
        // Resources present in this sector's fields, with their total authored units. Ordered by the
        // stable ResourceType.name string (NOT ordinal/hashCode) so selection is reorder-proof.
        val candidates = sectorResourceAvailability(world, sectorId)
        if (candidates.isEmpty()) return null

        val rng = MissionRng(fnv1a(seedKey))
        val (resource, available) = candidates[rng.nextInt(candidates.size)]

        // Clamp the quota to what the sector's fields can supply, so the mission is always completable.
        val hi = minOf(quotaMax, available)
        val lo = minOf(quotaMin, hi)
        val quota = (if (hi <= lo) hi else rng.nextIntInRange(lo, hi)).coerceAtLeast(1)
        val reward = params.miningRewardBase + quota.toLong() * params.miningRewardPerUnit

        return Mission(
            id = id,
            type = MissionType.MINING,
            source = source,
            status = MissionStatus.AVAILABLE,
            rewardCredits = reward,
            quotaResource = resource,
            quotaUnits = quota,
            factionId = factionId,
        )
    }

    /**
     * Build one COURIER offer originating at [station], or null when there is no other station in the
     * world to deliver to. The destination is drawn from the other stations (ordered by the stable
     * [PoiId.value] string), the deadline from the params' tick range, and the reward from a base plus
     * a procedural 0..span bonus — all via the same string-seeded [MissionRng]. The parcel is virtual.
     */
    private fun courierOffer(
        world: SectorWorld,
        station: Station,
        params: MissionParams,
    ): Mission? {
        // Every other station, ordered by the stable PoiId.value string (NOT authored order/hashCode).
        val others =
            allStations(world)
                .filter { it.id != station.id }
                .sortedBy { it.id.value }
        if (others.isEmpty()) return null

        val rng = MissionRng(fnv1a("board:${station.id.value}:courier"))
        val destination = others[rng.nextInt(others.size)]
        val ticks = rng.nextIntInRange(params.courierTickLimitMin, params.courierTickLimitMax)
        val reward = params.courierRewardBase + rng.nextInt(params.courierRewardSpan + 1).toLong()

        return Mission(
            id = MissionId("board:${station.id.value}:courier"),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.AVAILABLE,
            rewardCredits = reward,
            pickup = station.id,
            destination = destination.id,
            remainingTicks = ticks,
            pickedUp = false,
            // UC14: a courier is credited to its PICKUP (source) station's faction — even though the
            // parcel is delivered at the destination, the contract is the source faction's.
            factionId = station.factionId,
        )
    }

    /**
     * Build the gated `:premium` board offer for [station] (UC14 AC#3), or null when [station] has no
     * owning faction (an unaligned station has no premium contract) or there is no other station to
     * deliver to. A premium offer is a higher-paying [MissionType.COURIER] (always instanceable while a
     * second station exists, regardless of local minable resources — so even a resource-less faction
     * station like the Gamma junkyard gets one) carrying the reputation **gate**: its [Mission.unlockFaction]
     * is the station's faction and its [Mission.unlockThreshold] is [PREMIUM_UNLOCK_THRESHOLD]. The gate
     * is authored here from STATIC state only; the SEPARATE [com.orbitalfrontier.faction.ReputationGate]
     * decides visibility from live reputation, so generation stays a pure function of the authored world.
     *
     * Independently string-seeded (`board:<station>:premium`), so it never perturbs the other offers'
     * bytes. [PREMIUM_UNLOCK_THRESHOLD] is `<=` the default [com.orbitalfrontier.faction.ReputationParams]
     * `missionCompleteDelta`, so completing one faction mission at the station opens its gate (UC14 AC#6).
     */
    private fun premiumOffer(
        world: SectorWorld,
        station: Station,
        params: MissionParams,
    ): Mission? {
        val faction = station.factionId ?: return null
        val others =
            allStations(world)
                .filter { it.id != station.id }
                .sortedBy { it.id.value }
        if (others.isEmpty()) return null

        val rng = MissionRng(fnv1a("board:${station.id.value}:premium"))
        val destination = others[rng.nextInt(others.size)]
        val ticks = rng.nextIntInRange(params.courierTickLimitMin, params.courierTickLimitMax)
        // A premium reward: the courier base + span, plus a flat premium bonus.
        val reward = params.courierRewardBase + rng.nextInt(params.courierRewardSpan + 1).toLong() + PREMIUM_REWARD_BONUS

        return Mission(
            id = MissionId("board:${station.id.value}:premium"),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.AVAILABLE,
            rewardCredits = reward,
            pickup = station.id,
            destination = destination.id,
            remainingTicks = ticks,
            pickedUp = false,
            // The premium offer is itself a faction contract (completing it credits reputation) AND is
            // gated on that same faction's standing.
            factionId = faction,
            unlockFaction = faction,
            unlockThreshold = PREMIUM_UNLOCK_THRESHOLD,
        )
    }

    /** Every station across the whole sector graph, in authored sector/POI order. */
    private fun allStations(world: SectorWorld): List<Station> = world.sectors.flatMap { it.stations }

    /** Locate the station with [stationId] across the graph, returning its sector id + the station. */
    private fun locateStation(
        world: SectorWorld,
        stationId: PoiId,
    ): Pair<SectorId, Station>? {
        for (sector in world.sectors) {
            val station = sector.station(stationId)
            if (station != null) return sector.id to station
        }
        return null
    }

    /**
     * The resources minable in [sectorId] with their total authored units, summed across the sector's
     * asteroid fields and ordered by the stable [ResourceType.name] string. Determinism-safe ordering:
     * never relies on ordinal or hashCode.
     */
    private fun sectorResourceAvailability(
        world: SectorWorld,
        sectorId: SectorId,
    ): List<Pair<ResourceType, Int>> {
        val sector = world.sectorOrNull(sectorId) ?: return emptyList()
        val totals = LinkedHashMap<ResourceType, Int>()
        for (field in sector.asteroidFields) {
            for ((resource, units) in field.deposits) {
                totals[resource] = (totals[resource] ?: 0) + units
            }
        }
        return totals.entries
            .map { it.key to it.value }
            .sortedBy { it.first.name }
    }

    /**
     * Explicit FNV-1a 64-bit hash of [s] — delegates to the shared [DeterministicRng.fnv1a] primitive
     * (UC13 extraction). Deliberately NOT [String.hashCode] (and certainly not enum/data-class/identity
     * hashCode), so the seed is self-contained and stable across JVMs and refactors (ADR 0011).
     */
    private fun fnv1a(s: String): Long = DeterministicRng.fnv1a(s)

    /**
     * A tiny deterministic 64-bit LCG (Knuth MMIX constants) seeded by a string hash — the explicit
     * "string-hash → LCG" the determinism rule prescribes. Pure integer arithmetic, no floating point,
     * no platform RNG, no wall clock; identical seed ⇒ identical stream on any JVM.
     *
     * **UC13 refactor (byte-for-byte unchanged).** The advance/draw arithmetic now lives in the shared
     * [DeterministicRng] primitive ([DeterministicRng.lcgAdvance] / [DeterministicRng.boundedInt]); this
     * class is a thin stateful wrapper over it. The stream is identical to the pre-UC13 inlined version
     * (the constants and bit operations are the same), which UC12's golden fixtures verify on replay.
     */
    private class MissionRng(seed: Long) {
        private var state: Long = seed

        /** Advance the LCG and return the next 64-bit state. */
        private fun nextLong(): Long {
            state = DeterministicRng.lcgAdvance(state)
            return state
        }

        /** A uniform non-negative int in `[0, bound)`. [bound] must be positive. */
        fun nextInt(bound: Int): Int = DeterministicRng.boundedInt(nextLong(), bound)

        /** A uniform int in the inclusive range `[minInclusive, maxInclusive]`. */
        fun nextIntInRange(
            minInclusive: Int,
            maxInclusive: Int,
        ): Int {
            require(maxInclusive >= minInclusive) { "empty range: $minInclusive..$maxInclusive" }
            return minInclusive + nextInt(maxInclusive - minInclusive + 1)
        }
    }
}
