package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.ship.Shipyard

/**
 * A station POI — a dockable point of interest that broadcasts a transponder (UC05 AC#1/#2;
 * docs/design/world-and-sector.md "Stations").
 *
 * Stations sit in a sector's content cluster like any other [Poi]. Flying within [dockingRadius] of
 * [position] makes the station dockable (see [Docking]); docking opens the station-hub screen. The
 * station broadcasts a [ContactKind.STATION] transponder, so it appears on the minimap automatically
 * (UC05 AC#1) — it implements [Transponder] as a separate capability (coding-guidelines § I) rather
 * than baking "is on the minimap" into [Poi].
 *
 * [market] is the station's fixed buy/sell trade desk (UC08): a [StationMarket] of authored prices
 * the trade screen lists and [com.orbitalfrontier.economy.Trading] resolves against while docked. It
 * defaults to [StationMarket.EMPTY] (trades nothing) so a station without an authored market — and
 * existing call sites — read back as "no trade desk". Prices are authored map data carried with the
 * world, not persisted rows (ADR 0007).
 *
 * Pure data — no engine types — so stations are part of the JVM-testable world model (ADR 0001) and
 * the dock/undock logic that reads them ([Docking]) stays unit-testable on the JVM (UC05 AC#5).
 */
data class Station(
    override val id: PoiId,
    override val position: Vec2,
    val displayName: String,
    /** Radius (world-units) of the circle around [position] within which the ship can dock. */
    val dockingRadius: Float = DEFAULT_DOCKING_RADIUS,
    /** This station's fixed buy/sell prices (UC08); [StationMarket.EMPTY] when it has no trade desk. */
    val market: StationMarket = StationMarket.EMPTY,
    /**
     * What kind of station this is (UC09 AC#4): a [StationKind.DEALER] (the default) installs upgrades
     * and may sell ships; a [StationKind.JUNKYARD] additionally lets the player remove/sell used
     * upgrades and refit. Junkyard is a station **capability**, not a new [Poi] subtype (ADR 0008).
     */
    val kind: StationKind = StationKind.DEALER,
    /**
     * The upgrades this station offers for install (UC09 AC#3); [OutfitMarket.EMPTY] when it has no
     * outfitting desk. Authored map data carried with the world, not persisted (ADR 0008).
     */
    val outfitMarket: OutfitMarket = OutfitMarket.EMPTY,
    /**
     * The ship types this station sells (UC09 AC#5); [Shipyard.EMPTY] when it has no shipyard.
     * Authored map data carried with the world, not persisted (ADR 0008).
     */
    val shipyard: Shipyard = Shipyard.EMPTY,
    /**
     * Whether this station hires crew (UC11 AC#2). Defaulted to `false` so an existing station — and
     * every call site authored before UC11 — reads back as "no crew desk"; an authored crew-hiring
     * station sets it `true`. Like the markets above, this is fixed authored map data carried with the
     * world, not persisted. [com.orbitalfrontier.crew.Hiring] gates a hire on this flag.
     */
    val hiresCrew: Boolean = false,
) : Poi, Transponder {
    override val contactKind: ContactKind get() = ContactKind.STATION

    init {
        require(displayName.isNotBlank()) { "Station $id displayName must not be blank" }
        require(dockingRadius > 0f) { "Station $id dockingRadius must be positive: $dockingRadius" }
    }

    companion object {
        /**
         * Default docking-range radius (world-units). Comfortably larger than a gate's trigger ring
         * so docking is forgiving to line up; an authored tunable, overridable per station. [TUNE]
         */
        const val DEFAULT_DOCKING_RADIUS: Float = 100f
    }
}
