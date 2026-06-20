package com.orbitalfrontier.station

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StationBuildCost.canAfford] (UC51) — the **atomic** affordability check that is the
 * single source of truth shared by [StationBuilder] (before it deducts) and [StationBuildMenu] (the
 * per-option preview flag).
 *
 * The contract is all-or-nothing: credits AND every resource line must be covered; a shortfall in any
 * single line makes the whole cost unaffordable (so the builder never partially draws down the
 * wallet/hold).
 */
class StationBuildCostTest {
    private fun cargoOf(vararg pairs: Pair<ResourceType, Int>): Cargo = Cargo(pairs.toMap(), capacity = 500)

    private val cost =
        StationBuildCost(
            credits = 1000,
            resources = mapOf(ResourceType.IRON_ORE to 10, ResourceType.SILICON to 5),
        )

    @Test
    fun `affordable when credits and every resource line are covered`() {
        assertTrue(cost.canAfford(1000, cargoOf(ResourceType.IRON_ORE to 10, ResourceType.SILICON to 5)))
        assertTrue("surplus is fine", cost.canAfford(5000, cargoOf(ResourceType.IRON_ORE to 99, ResourceType.SILICON to 99)))
    }

    @Test
    fun `unaffordable when credits fall short`() {
        assertFalse(cost.canAfford(999, cargoOf(ResourceType.IRON_ORE to 10, ResourceType.SILICON to 5)))
    }

    @Test
    fun `unaffordable when any single resource line falls short (atomic)`() {
        assertFalse(
            "one ore short ⇒ the whole cost is unaffordable (no partial deduction)",
            cost.canAfford(1000, cargoOf(ResourceType.IRON_ORE to 9, ResourceType.SILICON to 5)),
        )
        assertFalse(
            "a missing resource counts as 0 held",
            cost.canAfford(1000, cargoOf(ResourceType.IRON_ORE to 10)),
        )
    }

    @Test
    fun `a free cost is always affordable`() {
        val free = StationBuildCost()
        assertTrue(free.canAfford(0, Cargo.empty()))
    }

    @Test
    fun `a credits-only cost ignores cargo`() {
        val creditsOnly = StationBuildCost(credits = 500)
        assertTrue(creditsOnly.canAfford(500, Cargo.empty()))
        assertFalse(creditsOnly.canAfford(499, Cargo.empty()))
    }
}
