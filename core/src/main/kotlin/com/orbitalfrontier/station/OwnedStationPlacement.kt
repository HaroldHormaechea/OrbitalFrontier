package com.orbitalfrontier.station

import com.orbitalfrontier.common.Vec2

/**
 * Deterministic placement of a player-owned [OwnedStation] in its anchor sector (UC51 AC#2/#4).
 *
 * ADR 0014 left placement an open question. UC51 decides it: an owned station's world position is a
 * **pure deterministic function of its [StationId]**, re-derived byte-identically on every load — so
 * placement persists (AC#4) without a save column, exactly the way authored markets/positions ride
 * with the world rather than the save (ADR 0007). Owned stations are never written into the authored
 * sector graph (ADR 0014); they are *projected* as synthetic POIs at render/dock time
 * ([OwnedStationProjection]), and this is where each one sits.
 *
 * **The fan.** Stations are laid out on a fixed lattice fanned south of the sector centre, based near
 * [BASE] `(0, -600)`. Slot = the station id; the lattice maps slot → (column, row) as
 * `column = slot % COLUMNS`, `row = slot / COLUMNS`. Because station ids are unique within the
 * registry (and globally — [StationRegistry.nextStationId] is `max+1`), this mapping is **injective**,
 * so two stations never share a position (challenger #1: pairwise-distinct for arbitrary N). The fan
 * stays **clear of authored hazards**: it is well south of Alpha Station (`0, 600`) and far from the
 * `alpha-raider-picket` encounter zone (`900, 0`, r260) east of centre, so an owned station never
 * overlaps an authored POI or a combat zone. All offsets are **[TUNE]** placeholders.
 */
object OwnedStationPlacement {
    /** The base anchor point of the owned-station fan — south of the sector centre. [TUNE] */
    val BASE: Vec2 = Vec2(0f, -600f)

    /** How many stations sit in one row before the fan wraps to the next (deeper) row. [TUNE] */
    const val COLUMNS: Int = 5

    /** Horizontal spacing between adjacent columns (world-units). [TUNE] */
    const val STEP_X: Float = 160f

    /** Vertical spacing between rows, fanning further south as the registry grows (world-units). [TUNE] */
    const val STEP_Y: Float = 160f

    /**
     * The fixed world position of [station] in its anchor sector — a pure function of its id (the
     * anchor sector is carried for context but every owned station is placed in the same per-sector
     * fan, since the registry's ids are globally unique). Injective in the id, so distinct stations get
     * distinct positions.
     */
    fun position(station: OwnedStation): Vec2 = positionFor(station.id)

    /** The fixed world position for a station with [id] — see [position]. */
    fun positionFor(id: StationId): Vec2 {
        val slot = id.value
        val column = (slot % COLUMNS).toInt()
        val row = slot / COLUMNS
        val x = BASE.x + (column - (COLUMNS - 1) / 2f) * STEP_X
        val y = BASE.y - row * STEP_Y
        return Vec2(x, y)
    }
}
