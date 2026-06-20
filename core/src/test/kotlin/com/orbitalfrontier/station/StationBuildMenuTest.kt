package com.orbitalfrontier.station

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StationBuildMenu] (UC51 AC#1) — the pure build/edit-screen state behind
 * [com.orbitalfrontier.screen.StationBuildScreen].
 *
 * Pins: building is offered only where the docked station is build-capable (empty menu otherwise — the
 * same gate the builder applies); the option set is one FoundStation per catalogued module plus one
 * BuildModule expansion per (owned station × module); and each option's `affordable` flag is the SAME
 * pure [StationBuildCost.canAfford] the builder deducts against (so the preview never disagrees with the
 * charge).
 */
class StationBuildMenuTest {
    private val catalog = StationModuleCatalog.MVP
    private val alpha = SectorId("alpha")
    private val richCargo =
        Cargo(
            mapOf(
                ResourceType.IRON_ORE to 50,
                ResourceType.SILICON to 50,
                ResourceType.TITANIUM to 50,
                ResourceType.ALUMINUM to 50,
            ),
            capacity = 500,
        )

    @Test
    fun `the menu is empty at a non-build-capable station`() {
        val options =
            StationBuildMenu.options(
                catalog = catalog,
                registry = StationRegistry.EMPTY,
                credits = 100_000L,
                cargo = richCargo,
                buildsStations = false,
            )
        assertTrue("no build options where the station can't build (the builder's gate)", options.isEmpty())
    }

    @Test
    fun `with no owned stations the menu is one found option per catalogued module`() {
        val options =
            StationBuildMenu.options(
                catalog = catalog,
                registry = StationRegistry.EMPTY,
                credits = 100_000L,
                cargo = richCargo,
                buildsStations = true,
            )
        assertEquals(catalog.all.size, options.size)
        assertTrue("every option founds a new station", options.all { it.order is StationBuildOrder.FoundStation })
        assertEquals(
            "the found options cover every module in authored order",
            catalog.all.map { it.id },
            options.map { (it.order as StationBuildOrder.FoundStation).moduleType },
        )
    }

    @Test
    fun `an owned station adds one expansion option per catalogued module`() {
        val owned = OwnedStation.founded(StationId(0), alpha, StationModuleCatalog.COMMERCE_HUB)
        val options =
            StationBuildMenu.options(
                catalog = catalog,
                registry = StationRegistry(listOf(owned)),
                credits = 100_000L,
                cargo = richCargo,
                buildsStations = true,
            )
        // found (one per module) + expand (one per owned station × module).
        assertEquals(catalog.all.size + catalog.all.size, options.size)
        val expansions = options.mapNotNull { it.order as? StationBuildOrder.BuildModule }
        assertEquals("one expansion per catalogued module for the single owned station", catalog.all.size, expansions.size)
        assertTrue("every expansion targets the owned station", expansions.all { it.stationId == StationId(0) })
    }

    @Test
    fun `affordability mirrors StationBuildCost canAfford`() {
        val brokeOptions =
            StationBuildMenu.options(
                catalog = catalog,
                registry = StationRegistry.EMPTY,
                credits = 0L,
                cargo = Cargo.empty(),
                buildsStations = true,
            )
        assertTrue("with no credits / no cargo nothing is affordable", brokeOptions.none { it.affordable })

        val richOptions =
            StationBuildMenu.options(
                catalog = catalog,
                registry = StationRegistry.EMPTY,
                credits = 100_000L,
                cargo = richCargo,
                buildsStations = true,
            )
        assertTrue("with ample credits + cargo everything is affordable", richOptions.all { it.affordable })

        // Spot-check the flag equals the pure cost check for each module (no drift between preview + charge).
        for (option in richOptions) {
            assertEquals(option.cost.canAfford(100_000L, richCargo), option.affordable)
        }
    }

    @Test
    fun `each option carries the module's own cost as its preview`() {
        val options =
            StationBuildMenu.options(
                catalog = catalog,
                registry = StationRegistry.EMPTY,
                credits = 100_000L,
                cargo = richCargo,
                buildsStations = true,
            )
        for (option in options) {
            val moduleId = (option.order as StationBuildOrder.FoundStation).moduleType
            assertEquals(catalog.module(moduleId)!!.cost, option.cost)
        }
        assertFalse("labels are populated", options.any { it.label.isBlank() })
    }
}
