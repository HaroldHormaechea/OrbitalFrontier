package com.orbitalfrontier.station

/**
 * One service the docked station hub can offer (UC51 AC#3).
 *
 * A small, closed `enum` (coding-guidelines § O) that the station-hub view
 * ([com.orbitalfrontier.screen.StationHubScreen]) gates each of its service buttons on: a button is
 * shown only when its service is in the hub's `enabledServices` set. The **default** set is [ALL], so
 * an authored station offers exactly the historical button set (preserved byte-for-byte by a guard
 * test); a **player-owned** station offers only the subset its installed modules expose — mapped from
 * its [StationFunction]s by [OwnedStationServices.hubServices] — plus [UNDOCK]. This is the
 * "compose, don't fork" seam (UC51): one hub screen, gated, rather than a duplicate owned-station hub.
 *
 * Pure identity only (no engine types), so it lives in the JVM-testable `station` model and
 * [OwnedStationServices] can map onto it without depending on the GL screen layer (ADR 0001).
 */
enum class HubService {
    /** Open the trade desk (a COMMERCE module's function on an owned station). */
    TRADE,

    /** Open the outfitting / retrofit desk (a RETROFIT module's function on an owned station). */
    OUTFIT,

    /** Open the shipyard / ship-switch screen. */
    SHIPS,

    /** Open the crew-hire desk. */
    CREW,

    /** Open the fleet & crew management screen. */
    FLEET,

    /** Open the station mission board. */
    MISSIONS,

    /** Open the station-build/edit screen (additionally gated on the station being build-capable). */
    BUILD,

    /** Convert hydrogen cargo into fuel (H₂ refuel). */
    REFUEL,

    /** Buy fuel for credits. */
    BUY_FUEL,

    /** Exit the ship and walk the station interior on foot. */
    DISEMBARK,

    /** Leave the station and return to flight. Always offered. */
    UNDOCK,

    ;

    companion object {
        /** The full service set — the default for an authored station (its historical button set). */
        val ALL: Set<HubService> = entries.toSet()
    }
}
