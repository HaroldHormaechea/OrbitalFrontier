package com.orbitalfrontier.economy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Cargo] (UC06 AC#3/#5/#6) — the pure, immutable, capacity-limited ship hold.
 *
 * Cargo is engine-free and deterministic, so these are plain JVM unit tests (AC#6). They pin the
 * contract mining relies on: a partial accept when the hold is nearly full (so the extraction budget
 * is threaded exactly), the derived [Cargo.usedUnits]/[Cargo.remainingCapacity]/[Cargo.isFull]
 * accessors, and value immutability (every mutation returns a new [Cargo]).
 */
class CargoTest {
    private val capacity = 50

    @Test
    fun `an empty hold is empty, not full, and has full remaining capacity`() {
        val cargo = Cargo.empty(capacity)

        assertEquals(0, cargo.usedUnits)
        assertEquals(capacity, cargo.remainingCapacity)
        assertFalse(cargo.isFull)
        assertTrue(cargo.contents.isEmpty())
    }

    @Test
    fun `adding units below capacity accepts them all and updates the derived totals`() {
        val result = Cargo.empty(capacity).add(ResourceType.IRON_ORE, 12)

        assertEquals("all requested units fit", 12, result.acceptedUnits)
        assertEquals(12, result.cargo.usedUnits)
        assertEquals(capacity - 12, result.cargo.remainingCapacity)
        assertFalse(result.cargo.isFull)
        assertEquals(mapOf(ResourceType.IRON_ORE to 12), result.cargo.contents)
    }

    @Test
    fun `adding the same resource twice accumulates its units`() {
        val once = Cargo.empty(capacity).add(ResourceType.HYDROGEN, 5).cargo
        val twice = once.add(ResourceType.HYDROGEN, 7)

        assertEquals(7, twice.acceptedUnits)
        assertEquals(mapOf(ResourceType.HYDROGEN to 12), twice.cargo.contents)
    }

    @Test
    fun `adding more than remaining capacity accepts only what fits (partial load)`() {
        // 45 of 50 used; only 5 more fit even though 20 are offered.
        val nearlyFull = Cargo(mapOf(ResourceType.IRON_ORE to 45), capacity)

        val result = nearlyFull.add(ResourceType.COPPER, 20)

        assertEquals("only the remaining 5 units are accepted", 5, result.acceptedUnits)
        assertTrue("the hold is now full", result.cargo.isFull)
        assertEquals(capacity, result.cargo.usedUnits)
        assertEquals(0, result.cargo.remainingCapacity)
        assertEquals(mapOf(ResourceType.IRON_ORE to 45, ResourceType.COPPER to 5), result.cargo.contents)
    }

    @Test
    fun `adding to a full hold accepts nothing and returns the original cargo`() {
        val full = Cargo(mapOf(ResourceType.IRON_ORE to capacity), capacity)
        assertTrue("precondition: the hold is full", full.isFull)

        val result = full.add(ResourceType.HYDROGEN, 10)

        assertEquals("a full hold accepts nothing", 0, result.acceptedUnits)
        assertEquals("the cargo is returned unchanged", full, result.cargo)
    }

    @Test
    fun `adding zero units is a no-op`() {
        val cargo = Cargo(mapOf(ResourceType.SILICON to 3), capacity)

        val result = cargo.add(ResourceType.SILICON, 0)

        assertEquals(0, result.acceptedUnits)
        assertEquals(cargo, result.cargo)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adding a negative number of units fails fast`() {
        Cargo.empty(capacity).add(ResourceType.NICKEL, -1)
    }

    @Test
    fun `add does not mutate the original cargo (immutability)`() {
        val original = Cargo.empty(capacity)

        val result = original.add(ResourceType.PLATINUM, 4)

        assertEquals("the original hold is untouched", 0, original.usedUnits)
        assertTrue("the original contents map is unchanged", original.contents.isEmpty())
        assertNotSame("a new Cargo instance is returned", original, result.cargo)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative capacity is rejected`() {
        Cargo(emptyMap(), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative unit count in the contents is rejected`() {
        Cargo(mapOf(ResourceType.IRON_ORE to -3), capacity)
    }

    @Test
    fun `isFull is true exactly when used units reach capacity`() {
        assertFalse(Cargo(mapOf(ResourceType.IRON_ORE to capacity - 1), capacity).isFull)
        assertTrue(Cargo(mapOf(ResourceType.IRON_ORE to capacity), capacity).isFull)
    }
}
