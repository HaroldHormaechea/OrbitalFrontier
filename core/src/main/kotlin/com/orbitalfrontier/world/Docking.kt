package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * The player's intent for the dock/undock control on a given frame (UC05 AC#2).
 *
 * [NONE] is the common per-frame case (no dock button pressed); [DOCK]/[UNDOCK] are the discrete
 * edge-triggered actions the play screen feeds in when the context button is tapped.
 */
enum class DockAction {
    NONE,
    DOCK,
    UNDOCK,
}

/**
 * Pure, deterministic dock/undock resolution (UC05 AC#5) — the docking analogue of [GateTraversal].
 *
 * Both functions are side-effect-free functions of (world, current sector, ship position, …):
 * identical inputs always yield an identical result, with no I/O and no engine types, so they slot
 * into the deterministic simulation stepper and are fully JVM-unit-testable (UC05 AC#5/#6). They do
 * **not** mutate anything — the caller (the play screen on device, the replay harness in tests)
 * applies the resulting dock state.
 *
 * Docking is **proximity + explicit action**, never automatic (UC05 pitfall): [availableStation]
 * reports whether a station is in range so the UI can offer a dock prompt, and [resolve] only
 * changes the dock state when the player actually issues a [DockAction.DOCK]/[DockAction.UNDOCK].
 */
object Docking {
    /**
     * The station the ship can currently dock with in [currentSector], or null if none is in range.
     *
     * A station is dockable when the ship is inside its [Station.dockingRadius] circle. When several
     * stations overlap the ship, the **nearest** wins; ties break by the sector's authored POI order
     * (deterministic by construction), so the same input always selects the same station.
     */
    fun availableStation(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
    ): Station? = availableStation(world, currentSector, shipPosition, emptyList())

    /**
     * The dockable station as [availableStation], but considering the sector's authored stations
     * **plus** [extraStations] — the player-owned station projections surfaced for this sector (UC51
     * AC#3; [com.orbitalfrontier.station.OwnedStationProjection]). Authored stations are considered
     * first, then the extras; the nearest in-range station overall wins. With an **empty**
     * [extraStations] this is identical to the base overload, so a no-owned-station sector resolves
     * byte-identically.
     */
    fun availableStation(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        extraStations: List<Station>,
    ): Station? {
        val sector = world.sector(currentSector)
        return (sector.stations + extraStations)
            .filter { (shipPosition - it.position).length <= it.dockingRadius }
            .minByOrNull { (shipPosition - it.position).length }
    }

    /**
     * Resolve the next dock state from the current one and the player's [action]:
     *  - **undocked + [DockAction.DOCK] + a station in range** → that station's [PoiId] (now docked);
     *  - **docked + [DockAction.UNDOCK]** → null (now in flight);
     *  - **anything else** (no action, dock with nothing in range, undock while already undocked,
     *    or dock while already docked) → [dockedStation] unchanged.
     *
     * @param dockedStation the station the ship is currently docked at, or null when in flight.
     * @return the station the ship is docked at after the action, or null when in flight.
     */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        dockedStation: PoiId?,
        shipPosition: Vec2,
        action: DockAction,
    ): PoiId? = resolve(world, currentSector, dockedStation, shipPosition, action, emptyList())

    /**
     * Resolve the next dock state as [resolve], but with the sector's owned-station projections
     * ([extraStations]) also dockable (UC51 AC#3). A [DockAction.DOCK] may now commit to an owned
     * station's synthetic [PoiId]; UNDOCK/NONE are unaffected. With an **empty** [extraStations] this is
     * identical to the base overload, so a no-owned-station tick resolves byte-identically.
     */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        dockedStation: PoiId?,
        shipPosition: Vec2,
        action: DockAction,
        extraStations: List<Station>,
    ): PoiId? =
        when (action) {
            DockAction.DOCK ->
                if (dockedStation == null) {
                    availableStation(world, currentSector, shipPosition, extraStations)?.id ?: dockedStation
                } else {
                    dockedStation
                }
            DockAction.UNDOCK -> if (dockedStation != null) null else dockedStation
            DockAction.NONE -> dockedStation
        }
}
