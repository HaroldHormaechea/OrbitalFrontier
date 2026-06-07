package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for the fixed sector-graph validation (UC03 AC#1/#8; ADR 0004).
 *
 * [SectorWorld] validates the whole authored graph at construction and **fails fast** on any
 * malformed map (a programmer/authoring error → throw, per coding-guidelines § error-handling).
 * These tests pin both halves of the contract: a well-formed map (including the production
 * [MvpSectorMap]) builds, and each malformed shape — link to an unknown sector, a dangling gate
 * link, a non-reciprocal link, duplicate sector ids, duplicate POI ids, an empty graph — is rejected.
 */
class SectorWorldTest {
    private fun gate(
        id: String,
        destSector: String,
        destGate: String,
        position: Vec2 = Vec2(100f, 0f),
    ): JumpGate =
        JumpGate(
            id = PoiId(id),
            position = position,
            triggerRadius = 50f,
            link = GateLink(destinationSector = SectorId(destSector), destinationGate = PoiId(destGate)),
        )

    private fun sector(
        id: String,
        vararg pois: Poi,
    ): Sector = Sector(id = SectorId(id), displayName = id, pois = pois.toList(), contentExtent = 1000f)

    /** A minimal well-formed two-sector map with a single reciprocal gate pair. */
    private fun validPair(): List<Sector> =
        listOf(
            sector("a", gate("a-g", destSector = "b", destGate = "b-g")),
            sector("b", gate("b-g", destSector = "a", destGate = "a-g")),
        )

    @Test
    fun `a well-formed reciprocal map builds`() {
        val world = SectorWorld(validPair())

        assertEquals(2, world.sectors.size)
        assertEquals(SectorId("b"), world.sector(SectorId("a")).gate(PoiId("a-g"))!!.link.destinationSector)
    }

    @Test
    fun `the production MVP map is a valid graph`() {
        val world = MvpSectorMap.build()
        // Three sectors, each with two reciprocal gates (the triangle topology).
        assertEquals(3, world.sectors.size)
    }

    @Test
    fun `a link to an unknown sector fails fast`() {
        val sectors =
            listOf(
                sector("a", gate("a-g", destSector = "ghost", destGate = "b-g")),
                sector("b", gate("b-g", destSector = "a", destGate = "a-g")),
            )
        assertThrows(IllegalArgumentException::class.java) { SectorWorld(sectors) }
    }

    @Test
    fun `a dangling gate link (no such gate in the destination sector) fails fast`() {
        val sectors =
            listOf(
                sector("a", gate("a-g", destSector = "b", destGate = "missing")),
                sector("b", gate("b-g", destSector = "a", destGate = "a-g")),
            )
        assertThrows(IllegalArgumentException::class.java) { SectorWorld(sectors) }
    }

    @Test
    fun `a non-reciprocal link fails fast`() {
        // a-g → b-g, but b-g links back to a *different* (here, itself's sector) gate, breaking reciprocity.
        val sectors =
            listOf(
                sector("a", gate("a-g", destSector = "b", destGate = "b-g")),
                sector(
                    "b",
                    gate("b-g", destSector = "b", destGate = "b-other"),
                    gate("b-other", destSector = "b", destGate = "b-g"),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) { SectorWorld(sectors) }
    }

    @Test
    fun `duplicate sector ids fail fast`() {
        val sectors =
            listOf(
                sector("a", gate("a-g", destSector = "a", destGate = "a-g")),
                sector("a", gate("a-g2", destSector = "a", destGate = "a-g2")),
            )
        assertThrows(IllegalArgumentException::class.java) { SectorWorld(sectors) }
    }

    @Test
    fun `duplicate POI ids within a sector fail fast`() {
        val sectors =
            listOf(
                sector(
                    "a",
                    gate("dup", destSector = "b", destGate = "b-g"),
                    gate("dup", destSector = "b", destGate = "b-g"),
                ),
                sector("b", gate("b-g", destSector = "a", destGate = "dup")),
            )
        assertThrows(IllegalArgumentException::class.java) { SectorWorld(sectors) }
    }

    @Test
    fun `an empty graph fails fast`() {
        assertThrows(IllegalArgumentException::class.java) { SectorWorld(emptyList()) }
    }
}
