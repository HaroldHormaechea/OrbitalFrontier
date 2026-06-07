package com.orbitalfrontier.world

/**
 * The immutable, fixed sector graph (UC03 AC#1) — a set of [Sector]s linked by reciprocal jump
 * gates (ADR 0004).
 *
 * Built once from authored map data ([MvpSectorMap]) and never mutated. Construction **validates
 * the whole graph and fails fast** (an [IllegalArgumentException]) on any malformed map — this is a
 * programmer/authoring error, so per coding-guidelines § error-handling we throw rather than degrade.
 * The invariants checked:
 *  - sector ids are unique;
 *  - POI ids are unique within each sector;
 *  - every gate link resolves: the destination sector exists and contains the destination gate;
 *  - every link is **reciprocal**: the destination gate links straight back to the origin gate
 *    (so traversal is symmetric and there are no one-way or dangling gates).
 *
 * Lookups are O(1) via an id index. Pure (no engine types): the graph is part of the JVM-testable
 * world model and is safe for the replay-path purity guard.
 */
class SectorWorld(sectors: List<Sector>) {
    /** All sectors, in authored order. */
    val sectors: List<Sector> = sectors.toList()

    private val byId: Map<SectorId, Sector>

    init {
        require(sectors.isNotEmpty()) { "SectorWorld must contain at least one sector" }

        val index = LinkedHashMap<SectorId, Sector>(sectors.size)
        for (sector in sectors) {
            require(index.put(sector.id, sector) == null) { "duplicate sector id: ${sector.id}" }
            val poiIds = HashSet<PoiId>(sector.pois.size)
            for (poi in sector.pois) {
                require(poiIds.add(poi.id)) { "duplicate POI id ${poi.id} in sector ${sector.id}" }
            }
        }
        byId = index

        validateGateGraph()
    }

    /** The sector with [id]. Throws if it is not in the graph (a programmer error — fail fast). */
    fun sector(id: SectorId): Sector = byId[id] ?: throw IllegalArgumentException("no such sector: $id")

    /** The sector with [id], or null if it is not in the graph. */
    fun sectorOrNull(id: SectorId): Sector? = byId[id]

    private fun validateGateGraph() {
        for (sector in sectors) {
            for (gate in sector.gates) {
                val destSector =
                    byId[gate.link.destinationSector]
                        ?: throw IllegalArgumentException(
                            "gate ${gate.id} in ${sector.id} links to unknown sector ${gate.link.destinationSector}",
                        )
                val destGate =
                    destSector.gate(gate.link.destinationGate)
                        ?: throw IllegalArgumentException(
                            "gate ${gate.id} in ${sector.id} links to unknown gate " +
                                "${gate.link.destinationGate} in ${destSector.id}",
                        )
                val reciprocal =
                    destGate.link.destinationSector == sector.id && destGate.link.destinationGate == gate.id
                require(reciprocal) {
                    "gate ${gate.id} in ${sector.id} is not reciprocal: " +
                        "${destGate.id} in ${destSector.id} links to " +
                        "${destGate.link.destinationGate} in ${destGate.link.destinationSector}"
                }
            }
        }
    }
}
