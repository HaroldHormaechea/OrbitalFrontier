package com.orbitalfrontier.world

/**
 * What kind of station a [Station] is (UC09 AC#4).
 *
 * A closed set modelled as an `enum` (coding-guidelines § O). The kind is a **capability of the
 * station**, not a new [Poi] subtype — a junkyard is still a dockable station; it just additionally
 * permits removing/selling used upgrades and refitting (which a [DEALER] does not). This supersedes
 * the design note's earlier "junkyard = NEW POI type" wording (see ADR 0008): keeping it a station
 * kind means docking, the minimap transponder, and the hub screen all work unchanged.
 */
enum class StationKind {
    /** A normal trade/outfit station: buys/sells goods, installs upgrades, may have a shipyard. */
    DEALER,

    /** A junkyard: additionally lets the player remove + sell used upgrades and refit (UC09 AC#4). */
    JUNKYARD,
}
