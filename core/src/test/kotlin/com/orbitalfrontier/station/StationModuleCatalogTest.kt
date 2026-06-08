package com.orbitalfrontier.station

import com.orbitalfrontier.economy.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for the authored [StationModuleCatalog] (UC15 AC#1/#2). Covers id lookup, the unknown → null
 * contract, and the authored shape + [TUNE] costs of the MVP catalog (commerce hub + retrofit bay), so a
 * later retune that changes a cost is caught here (the single place those numbers are asserted).
 */
class StationModuleCatalogTest {
    private val catalog = StationModuleCatalog.MVP

    @Test
    fun `module looks up a catalogued module by id`() {
        val commerce = catalog.module(StationModuleCatalog.COMMERCE_HUB)
        assertEquals(StationModuleCatalog.COMMERCE_HUB, commerce?.id)
        assertEquals(StationFunction.COMMERCE, commerce?.function)

        val retrofit = catalog.module(StationModuleCatalog.RETROFIT_BAY)
        assertEquals(StationModuleCatalog.RETROFIT_BAY, retrofit?.id)
        assertEquals(StationFunction.RETROFIT, retrofit?.function)
    }

    @Test
    fun `an unknown module id resolves to null`() {
        assertNull(catalog.module(StationModuleId("does-not-exist")))
    }

    @Test
    fun `the MVP catalog has exactly the two authored modules`() {
        assertEquals(2, catalog.all.size)
        assertEquals(
            listOf(StationModuleCatalog.COMMERCE_HUB, StationModuleCatalog.RETROFIT_BAY),
            catalog.all.map { it.id },
        )
    }

    @Test
    fun `the commerce hub has its authored TUNE cost`() {
        val cost = catalog.module(StationModuleCatalog.COMMERCE_HUB)!!.cost
        assertEquals("commerce-hub-i credit price", 1500L, cost.credits)
        assertEquals(
            "commerce-hub-i resource bill",
            mapOf(ResourceType.IRON_ORE to 15, ResourceType.SILICON to 8),
            cost.resources,
        )
    }

    @Test
    fun `the retrofit bay has its authored TUNE cost`() {
        val cost = catalog.module(StationModuleCatalog.RETROFIT_BAY)!!.cost
        assertEquals("retrofit-bay-i credit price", 2000L, cost.credits)
        assertEquals(
            "retrofit-bay-i resource bill",
            mapOf(ResourceType.TITANIUM to 6, ResourceType.ALUMINUM to 10),
            cost.resources,
        )
    }

    @Test
    fun `the slug values are the stable persisted module_type strings`() {
        assertEquals("commerce-hub-i", StationModuleCatalog.COMMERCE_HUB.value)
        assertEquals("retrofit-bay-i", StationModuleCatalog.RETROFIT_BAY.value)
    }

    @Test
    fun `a duplicate module id in a catalog is rejected at construction`() {
        val dup =
            StationModule(
                id = StationModuleCatalog.COMMERCE_HUB,
                function = StationFunction.COMMERCE,
                displayName = "Dup",
                cost = StationBuildCost(credits = 1L),
            )
        assertThrows(IllegalArgumentException::class.java) {
            StationModuleCatalog(listOf(dup, dup))
        }
    }
}
