package com.orbitalfrontier.station

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [StationBuilder] resolver (UC15 AC#1/#5). All cases are JVM-only (no engine
 * types, no I/O): they assert the deterministic outcome of one `resolve` call against a registry, wallet,
 * and hold — founding a station, building a module onto one, atomic affordability, and the no-op gates.
 *
 * The catalog under test is the authored [StationModuleCatalog.MVP]: `commerce-hub-i` (COMMERCE, 1500cr +
 * {IRON_ORE:15, SILICON:8}) and `retrofit-bay-i` (RETROFIT, 2000cr + {TITANIUM:6, ALUMINUM:10}).
 */
class StationBuilderTest {
    private val sector = SectorId("alpha")
    private val commerceHub = StationModuleCatalog.COMMERCE_HUB
    private val retrofitBay = StationModuleCatalog.RETROFIT_BAY

    /** A hold comfortably covering one commerce hub + one retrofit bay (so atomic checks have headroom). */
    private fun fullHold(): Cargo =
        Cargo(
            mapOf(
                ResourceType.IRON_ORE to 20,
                ResourceType.SILICON to 12,
                ResourceType.TITANIUM to 10,
                ResourceType.ALUMINUM to 15,
            ),
            Cargo.DEFAULT_CAPACITY,
        )

    @Test
    fun `FoundStation allocates the next id, anchors at the sector, slot-0 module, and deducts the cost`() {
        val result =
            StationBuilder.resolve(
                registry = StationRegistry.EMPTY,
                credits = 5000L,
                cargo = fullHold(),
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.FoundStation(commerceHub),
            )

        assertTrue("a successful found reports changed", result.changed)
        assertEquals("one station is now owned (AC#3)", 1, result.registry.size)

        val station = result.registry.stations.single()
        assertEquals("the first founded station gets id 0", StationId(0), station.id)
        assertEquals("anchored at the docked station's sector", sector, station.sector)
        assertEquals("the first module sits in slot 0", commerceHub, station.moduleAt(0))
        assertEquals("only one module so far", 1, station.moduleCount)

        // Atomic deduction: 1500 credits + 15 IRON_ORE + 8 SILICON drawn from the hold; rest untouched.
        assertEquals("credits deducted", 5000L - 1500L, result.credits)
        assertEquals("IRON_ORE deducted", 20 - 15, result.cargo.contents[ResourceType.IRON_ORE])
        assertEquals("SILICON deducted", 12 - 8, result.cargo.contents[ResourceType.SILICON])
        assertEquals("untouched resources stay", 10, result.cargo.contents[ResourceType.TITANIUM])
    }

    @Test
    fun `BuildModule adds the module to the lowest free slot of an owned station`() {
        // Found a station first (slot 0 = commerce hub), then build a retrofit bay onto it.
        val founded =
            StationBuilder.resolve(
                registry = StationRegistry.EMPTY,
                credits = 5000L,
                cargo = fullHold(),
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.FoundStation(commerceHub),
            )
        val stationId = founded.registry.stations.single().id

        val built =
            StationBuilder.resolve(
                registry = founded.registry,
                credits = founded.credits,
                cargo = founded.cargo,
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.BuildModule(stationId, retrofitBay),
            )

        assertTrue("a successful build reports changed", built.changed)
        val station = built.registry.station(stationId)!!
        assertEquals("the new module lands in the lowest free slot (1)", retrofitBay, station.moduleAt(1))
        assertEquals("now two modules", 2, station.moduleCount)
        assertEquals("retrofit-bay credits deducted", founded.credits - 2000L, built.credits)
        assertEquals("TITANIUM deducted", 10 - 6, built.cargo.contents[ResourceType.TITANIUM])
        assertEquals("ALUMINUM deducted", 15 - 10, built.cargo.contents[ResourceType.ALUMINUM])
    }

    @Test
    fun `a credit shortfall is an atomic no-op with no partial deduction`() {
        val hold = fullHold()
        val result =
            StationBuilder.resolve(
                registry = StationRegistry.EMPTY,
                // one credit short of the 1500 commerce-hub price
                credits = 1499L,
                cargo = hold,
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.FoundStation(commerceHub),
            )

        assertFalseChanged(result)
        assertEquals("no station founded", 0, result.registry.size)
        assertEquals("credits untouched (no partial deduction)", 1499L, result.credits)
        assertSame("cargo is the same instance (no resources removed)", hold, result.cargo)
    }

    @Test
    fun `a resource shortfall is an atomic no-op even when credits suffice`() {
        // Enough credits, but one SILICON short of the 8 the commerce hub needs: both lines must clear or
        // nothing is deducted (atomic).
        val hold = Cargo(mapOf(ResourceType.IRON_ORE to 20, ResourceType.SILICON to 7), Cargo.DEFAULT_CAPACITY)
        val result =
            StationBuilder.resolve(
                registry = StationRegistry.EMPTY,
                credits = 5000L,
                cargo = hold,
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.FoundStation(commerceHub),
            )

        assertFalseChanged(result)
        assertEquals("no station founded", 0, result.registry.size)
        assertEquals("credits untouched despite being sufficient (atomic)", 5000L, result.credits)
        assertSame("cargo untouched — IRON_ORE not deducted because SILICON fell short", hold, result.cargo)
    }

    @Test
    fun `a station that is not build-capable is a no-op`() {
        val hold = fullHold()
        val result =
            StationBuilder.resolve(
                registry = StationRegistry.EMPTY,
                credits = 5000L,
                cargo = hold,
                // the docked station does not offer station building
                buildsStations = false,
                sector = sector,
                order = StationBuildOrder.FoundStation(commerceHub),
            )

        assertFalseChanged(result)
        assertEquals("nothing founded at a non-build-capable station (AC#1)", 0, result.registry.size)
        assertSame("cargo untouched", hold, result.cargo)
    }

    @Test
    fun `an unknown module is a no-op`() {
        val hold = fullHold()
        val result =
            StationBuilder.resolve(
                registry = StationRegistry.EMPTY,
                credits = 5000L,
                cargo = hold,
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.FoundStation(StationModuleId("not-a-real-module")),
            )

        assertFalseChanged(result)
        assertEquals("nothing founded for an uncatalogued module", 0, result.registry.size)
    }

    @Test
    fun `BuildModule onto a station the player does not own is a no-op`() {
        val hold = fullHold()
        val result =
            StationBuilder.resolve(
                // owns nothing
                registry = StationRegistry.EMPTY,
                credits = 5000L,
                cargo = hold,
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.BuildModule(StationId(7), retrofitBay),
            )

        assertFalseChanged(result)
        assertEquals("nothing changed for a not-owned station", 0, result.registry.size)
        assertSame("cargo untouched", hold, result.cargo)
    }

    @Test
    fun `None returns its inputs unchanged, same instances`() {
        val registry = StationRegistry.EMPTY
        val hold = fullHold()
        val result =
            StationBuilder.resolve(
                registry = registry,
                credits = 5000L,
                cargo = hold,
                buildsStations = true,
                sector = sector,
                order = StationBuildOrder.None,
            )

        assertFalseChanged(result)
        assertSame("registry is the same instance on a no-op", registry, result.registry)
        assertSame("cargo is the same instance on a no-op", hold, result.cargo)
        assertEquals("credits unchanged", 5000L, result.credits)
    }

    @Test
    fun `nextStationId allocation increments across successive founds`() {
        var registry = StationRegistry.EMPTY
        var credits = 100_000L
        var cargo = Cargo(mapOf(ResourceType.IRON_ORE to 100, ResourceType.SILICON to 100), 200)
        val ids = mutableListOf<StationId>()
        repeat(3) {
            val result =
                StationBuilder.resolve(
                    registry = registry,
                    credits = credits,
                    cargo = cargo,
                    buildsStations = true,
                    sector = sector,
                    order = StationBuildOrder.FoundStation(commerceHub),
                )
            registry = result.registry
            credits = result.credits
            cargo = result.cargo
            ids += registry.stations.maxByOrNull { it.id.value }!!.id
        }
        assertEquals("ids allocated 0,1,2 (max+1, deterministic)", listOf(StationId(0), StationId(1), StationId(2)), ids)
    }

    /** Assert a no-op result: not changed. (A small readability helper used by the gate tests.) */
    private fun assertFalseChanged(result: StationBuildResult) {
        assertFalse("a no-op must report changed = false", result.changed)
    }
}
