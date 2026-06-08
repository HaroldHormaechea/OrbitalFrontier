package com.orbitalfrontier.world

/**
 * One sector: an unbounded plane whose content is clustered near its centre (origin), per
 * docs/design/world-and-sector.md and UC03 AC#2.
 *
 * There is no hard wall — [contentExtent] is a **soft** radius describing how far from the centre
 * the authored content reaches (used by the minimap to scale the view, AC#6); flying beyond it
 * yields empty space, not a boundary. The MVP extent is sized so the content area is ~30s to cross
 * at the ship's max speed (see [MvpSectorMap]).
 *
 * Pure data; gate lookups are derived from [pois] so a sector has a single source of truth for its
 * contents.
 */
data class Sector(
    val id: SectorId,
    val displayName: String,
    val pois: List<Poi>,
    /** Soft content radius (world-units): how far from the centre authored content reaches. */
    val contentExtent: Float,
) {
    init {
        require(displayName.isNotBlank()) { "Sector $id displayName must not be blank" }
        require(contentExtent > 0f) { "Sector $id contentExtent must be positive: $contentExtent" }
    }

    /** The jump gates in this sector (a view over [pois]); empty if the sector has none. */
    val gates: List<JumpGate> get() = pois.filterIsInstance<JumpGate>()

    /** The stations in this sector (a view over [pois], authored order); empty if it has none. */
    val stations: List<Station> get() = pois.filterIsInstance<Station>()

    /** The asteroid fields in this sector (a view over [pois], authored order); empty if it has none. */
    val asteroidFields: List<AsteroidField> get() = pois.filterIsInstance<AsteroidField>()

    /** The gate with [gateId] in this sector, or null if there is none. */
    fun gate(gateId: PoiId): JumpGate? = gates.firstOrNull { it.id == gateId }

    /** The station with [stationId] in this sector, or null if there is none. */
    fun station(stationId: PoiId): Station? = stations.firstOrNull { it.id == stationId }

    /** The asteroid field with [fieldId] in this sector, or null if there is none. */
    fun asteroidField(fieldId: PoiId): AsteroidField? = asteroidFields.firstOrNull { it.id == fieldId }
}
