package com.orbitalfrontier.station

/**
 * Maps a player-owned station's exposed [StationFunction]s to the [HubService]s its docked hub offers
 * (UC51 AC#3) — "compose, don't fork".
 *
 * An owned station reuses the single [com.orbitalfrontier.screen.StationHubScreen]; this picks which
 * service buttons it shows. Only the functions the station's installed modules actually provide map to
 * a service ([StationFunction.COMMERCE] → [HubService.TRADE], [StationFunction.RETROFIT] →
 * [HubService.OUTFIT]); refuel, missions, crew, shipyard, fleet and disembark are deliberately **not**
 * offered at an owned station (pitfall #4 — no duplicated refuel/mission services). The caller adds
 * [HubService.UNDOCK] (always available) on top of this set.
 *
 * Pure (no engine types) so it stays JVM-testable and unit-tested directly (ADR 0001).
 */
object OwnedStationServices {
    /**
     * The hub services an owned station with these [functions] offers — the COMMERCE→TRADE /
     * RETROFIT→OUTFIT mapping, in [StationFunction] iteration order. Excludes [HubService.UNDOCK]
     * (the caller always adds it). An empty function set yields an empty service set.
     */
    fun hubServices(functions: Set<StationFunction>): Set<HubService> =
        functions.mapNotNullTo(LinkedHashSet()) { function ->
            when (function) {
                StationFunction.COMMERCE -> HubService.TRADE
                StationFunction.RETROFIT -> HubService.OUTFIT
            }
        }
}
