package com.orbitalfrontier.station

import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Sector
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.Station

/**
 * Projects player-owned stations into the authored world as synthetic [Station] POIs (UC51 AC#2/#3) —
 * "project, don't author".
 *
 * Owned stations are player state ([StationRegistry] on [com.orbitalfrontier.world.WorldState]), never
 * authored into the fixed sector graph (ADR 0014). To **surface** them — render them in the sector and
 * on the minimap, label them (UC24), and let the player dock and use their modules — this turns each
 * [OwnedStation] anchored in a sector into a synthetic [Station] at render/dock time. Because a
 * synthetic [Station] is a [com.orbitalfrontier.world.Transponder] carrying markets, every existing
 * consumer (the world/minimap/overlay renderers, `MapLabels`, [com.orbitalfrontier.world.Docking], the
 * docked trade/outfit desks and the hub routing) works on it with **zero changes** — fed
 * `effectivePois = sector.pois + projection`.
 *
 * **Determinism (UC51 primary risk).** This is strictly additive: with **no owned stations** every
 * projection method returns an empty list / the input unchanged, so `effectivePois == sector.pois`
 * and the docked-station lookup is unchanged ⇒ every existing fixture is byte-identical. The synthetic
 * station's position is a pure function of its id ([OwnedStationPlacement]) and its markets are
 * reconstructed ([OwnedStationMarkets]), so a projected station re-derives identically across a
 * save/reload (AC#4) without any persisted placement.
 *
 * Pure (no engine types) so it is the single function reused by BOTH the device's `PlayScreen` and the
 * test-set `Simulation` (lockstep) and is unit-tested directly (ADR 0001).
 */
object OwnedStationProjection {
    /** The synthetic POI-id namespace for owned stations — kept separate from every authored [PoiId]. */
    private const val POI_PREFIX: String = "owned-station-"

    /** The deterministic synthetic [PoiId] of the owned station with [id] (`owned-station-<id>`). */
    fun poiIdFor(id: StationId): PoiId = PoiId("$POI_PREFIX${id.value}")

    /**
     * The [StationId] encoded in [poiId] if it is an owned-station projection id, else null — the
     * inverse of [poiIdFor], so a docked synthetic id can be resolved back to its owned station.
     */
    fun ownedStationIdOf(poiId: PoiId): StationId? {
        val raw = poiId.value
        if (!raw.startsWith(POI_PREFIX)) return null
        val value = raw.removePrefix(POI_PREFIX).toLongOrNull() ?: return null
        return StationId(value)
    }

    /** The display name shown for the owned station with [id] (1-based: id 0 → "Outpost 1"). */
    fun displayNameFor(id: StationId): String = "Outpost ${id.value + 1}"

    /**
     * The synthetic [Station] for [owned], resolving its modules through [catalog]. It carries a
     * commerce trade desk iff a COMMERCE module is installed and a retrofit outfit desk iff a RETROFIT
     * module is installed ([OwnedStationMarkets]); **all other station capabilities are off** — no
     * shipyard, no crew desk, no faction, not build-capable, default [com.orbitalfrontier.world
     * .StationKind.DEALER] — so it offers ONLY the functions its modules provide (pitfall #4).
     */
    fun stationFor(
        owned: OwnedStation,
        catalog: StationModuleCatalog = StationModuleCatalog.MVP,
    ): Station {
        val functions = owned.availableFunctions(catalog)
        return Station(
            id = poiIdFor(owned.id),
            position = OwnedStationPlacement.position(owned),
            displayName = displayNameFor(owned.id),
            market =
                if (StationFunction.COMMERCE in functions) {
                    OwnedStationMarkets.COMMERCE_DESK
                } else {
                    com.orbitalfrontier.economy.StationMarket.EMPTY
                },
            outfitMarket =
                if (StationFunction.RETROFIT in functions) {
                    OwnedStationMarkets.RETROFIT_DESK
                } else {
                    OutfitMarket.EMPTY
                },
        )
    }

    /**
     * The synthetic [Station]s for every owned station anchored in [sectorId], in registry (id) order.
     * Empty when the player owns no station there — the byte-identity guarantee for a no-owned-station
     * sector.
     */
    fun stationsIn(
        sectorId: SectorId,
        registry: StationRegistry,
        catalog: StationModuleCatalog = StationModuleCatalog.MVP,
    ): List<Station> =
        registry.stations
            .filter { it.sector == sectorId }
            .map { stationFor(it, catalog) }

    /**
     * [sector]'s authored POIs **plus** the owned-station projections anchored in it — the
     * `effectivePois` the renderers, minimap, overlay and docking consume (AC#2/#3). Returns
     * `sector.pois` unchanged (same list) when no owned station is anchored here, so a no-owned-station
     * sector renders byte-identically.
     */
    fun poisIn(
        sector: Sector,
        registry: StationRegistry,
        catalog: StationModuleCatalog = StationModuleCatalog.MVP,
    ): List<Poi> {
        val owned = stationsIn(sector.id, registry, catalog)
        return if (owned.isEmpty()) sector.pois else sector.pois + owned
    }

    /**
     * The [Station] the ship is docked at (AC#3): the authored station with [dockedStation] in
     * [currentSector] if there is one, otherwise — when [dockedStation] is an owned-station projection
     * id whose station is anchored in [currentSector] — its synthetic projection. Null when undocked or
     * unresolvable. This is the single lookup reused by `PlayScreen` and the test-set `Simulation`, so
     * a docked owned station yields its commerce/retrofit markets in both (lockstep).
     */
    fun resolveDocked(
        world: SectorWorld,
        currentSector: SectorId,
        registry: StationRegistry,
        dockedStation: PoiId?,
        catalog: StationModuleCatalog = StationModuleCatalog.MVP,
    ): Station? {
        if (dockedStation == null) return null
        val authored = world.sectorOrNull(currentSector)?.station(dockedStation)
        if (authored != null) return authored
        val ownedId = ownedStationIdOf(dockedStation) ?: return null
        val owned = registry.station(ownedId) ?: return null
        if (owned.sector != currentSector) return null
        return stationFor(owned, catalog)
    }
}
