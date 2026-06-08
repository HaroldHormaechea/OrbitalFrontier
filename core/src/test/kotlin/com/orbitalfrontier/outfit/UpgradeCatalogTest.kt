package com.orbitalfrontier.outfit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UpgradeCatalog] (UC09 AC#2/#3) — the data-driven master list resolved from the
 * [UpgradeId] a [Loadout] persists.
 *
 * Pins: id → [Upgrade] resolution, graceful null for an uncatalogued id (a removed part in an old
 * save resolves to null and is skipped — never stranded), per-category filtering, duplicate-id
 * rejection at construction (fail fast on an authoring error), and the MVP catalog's authored shape.
 */
class UpgradeCatalogTest {
    private val catalog = UpgradeCatalog.MVP

    @Test
    fun `resolves a catalogued id to its full upgrade`() {
        val upgrade = catalog.upgrade(UpgradeCatalog.ENGINE_TUNE_I)

        assertEquals(UpgradeCatalog.ENGINE_TUNE_I, upgrade?.id)
        assertEquals(SlotCategory.ENGINES, upgrade?.category)
        assertEquals(300L, upgrade?.price)
    }

    @Test
    fun `an uncatalogued id resolves to null`() {
        assertNull(catalog.upgrade(UpgradeId("nonexistent-part")))
    }

    @Test
    fun `upgradesIn returns only the upgrades of that category in authored order`() {
        val engines = catalog.upgradesIn(SlotCategory.ENGINES)

        assertEquals(listOf(UpgradeCatalog.ENGINE_TUNE_I, UpgradeCatalog.ENGINE_TUNE_II), engines.map { it.id })
        assertTrue("every result is an engine part", engines.all { it.category == SlotCategory.ENGINES })
    }

    @Test
    fun `a category with no catalogued parts returns empty`() {
        // Weapons/comms/hull/crew exist as slot categories but ship no MVP upgrades yet.
        assertTrue(catalog.upgradesIn(SlotCategory.WEAPONS).isEmpty())
        assertTrue(catalog.upgradesIn(SlotCategory.CREW_QUARTERS).isEmpty())
    }

    @Test
    fun `constructing a catalog with a duplicate id fails fast`() {
        val dup = Upgrade(UpgradeId("dup"), SlotCategory.ENGINES, "Dup", 100, StatDelta(maxSpeed = 1f))

        assertThrows(IllegalArgumentException::class.java) {
            UpgradeCatalog(listOf(dup, dup))
        }
    }

    @Test
    fun `every MVP upgrade has a non-negative price and a known id`() {
        for (upgrade in catalog.all) {
            assertTrue("price must be >= 0 for ${upgrade.id.value}", upgrade.price >= 0)
            assertEquals("upgrade(${upgrade.id.value}) round-trips", upgrade, catalog.upgrade(upgrade.id))
        }
    }

    @Test
    fun `the MVP catalog exposes the expected authored ids`() {
        val ids = catalog.all.map { it.id }.toSet()
        assertEquals(
            setOf(
                UpgradeCatalog.ENGINE_TUNE_I,
                UpgradeCatalog.ENGINE_TUNE_II,
                UpgradeCatalog.CARGO_POD_I,
                UpgradeCatalog.CARGO_POD_II,
                UpgradeCatalog.FUEL_TANK_I,
                UpgradeCatalog.SCANNER_I,
            ),
            ids,
        )
    }
}
