package com.orbitalfrontier.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LootTable] (UC42 AC#1/#4) — the data-driven, pure, seed-deterministic loot generator.
 *
 * Pins the determinism contract the salvage replay rests on: a roll is a pure function of
 * `(archetypeId, seedKey)` — identical inputs always yield the same [LootResult] (AC#4) — and the
 * authored catalogs bound what each archetype can drop (AC#1). Unknown archetypes degrade to the small
 * [LootTable.DEFAULT] profile rather than crashing.
 *
 * The seed key follows the production convention `"salvage:$zoneId:${hostileId.value}"` (see
 * [Salvage.spawn]); these tests use representative keys of that shape.
 */
class LootTableTest {
    private val raider = HostileArchetypes.RAIDER.id
    private val scavenger = HostileArchetypes.SCAVENGER.id

    @Test
    fun `the same archetype and seed key always rolls the identical loot (AC#4)`() {
        val a = LootTable.roll(raider, "salvage:alpha-raider-picket:0")
        val b = LootTable.roll(raider, "salvage:alpha-raider-picket:0")
        assertEquals("identical inputs must yield identical loot", a, b)
    }

    @Test
    fun `roll is order-independent - interleaving other rolls does not change a result`() {
        val expected = LootTable.roll(raider, "salvage:z:7")
        // Draw a bunch of unrelated rolls between the two identical calls.
        repeat(25) { LootTable.roll(scavenger, "salvage:other:$it") }
        val again = LootTable.roll(raider, "salvage:z:7")
        assertEquals("the generator carries no hidden state across calls", expected, again)
    }

    @Test
    fun `different seed keys roll independently (the stream varies across keys)`() {
        // Determinism alone is satisfied by a constant function; assert the key actually drives the
        // result by showing a spread of distinct outcomes across many keys.
        val results = (0 until 40).map { LootTable.roll(raider, "salvage:alpha-raider-picket:$it") }
        assertTrue("different seed keys must produce more than one distinct loot result", results.toSet().size > 1)
    }

    @Test
    fun `the seed key fully determines the result - zone and hostile id both matter`() {
        val byHostile = LootTable.roll(raider, "salvage:z:0") != LootTable.roll(raider, "salvage:z:1")
        val byZone = LootTable.roll(raider, "salvage:zoneA:0") != LootTable.roll(raider, "salvage:zoneB:0")
        assertTrue("changing the hostile id changes the seed stream", byHostile)
        assertTrue("changing the zone id changes the seed stream", byZone)
    }

    @Test
    fun `raider credits always fall inside the authored credit span (AC#1)`() {
        val span = LootTable.RAIDER
        repeat(200) { i ->
            val credits = LootTable.roll(raider, "salvage:z:$i").credits
            assertTrue("credits $credits in [${span.creditsMin}, ${span.creditsMax}]", credits in span.creditsMin..span.creditsMax)
        }
    }

    @Test
    fun `scavenger credits always fall inside its (smaller) authored credit span (AC#1)`() {
        val span = LootTable.SCAVENGER
        repeat(200) { i ->
            val credits = LootTable.roll(scavenger, "salvage:z:$i").credits
            assertTrue("credits $credits in [${span.creditsMin}, ${span.creditsMax}]", credits in span.creditsMin..span.creditsMax)
        }
    }

    @Test
    fun `rolled resources only ever come from the archetype catalog and respect each drop's quantity range`() {
        val loot = LootTable.RAIDER
        val byResource = loot.drops.associateBy { it.resource }
        repeat(300) { i ->
            val resources = LootTable.roll(raider, "salvage:z:$i").resources
            for ((resource, units) in resources) {
                val drop = byResource[resource]
                assertTrue("rolled resource $resource is in the raider catalog", drop != null)
                assertTrue("units $units > 0 (no zero-unit entries authored)", units > 0)
                assertTrue("units $units in [${drop!!.minUnits}, ${drop.maxUnits}]", units in drop.minUnits..drop.maxUnits)
            }
        }
    }

    @Test
    fun `a resource that never rolls is simply absent - the map never carries a zero entry`() {
        // Whatever rolls, every value must be strictly positive (the loot generator drops zero-unit entries).
        repeat(300) { i ->
            val resources = LootTable.roll(raider, "salvage:z:$i").resources
            assertTrue("no zero/negative unit entries: $resources", resources.values.all { it > 0 })
        }
    }

    @Test
    fun `an un-catalogued archetype degrades to the DEFAULT profile (modest credits, no resources)`() {
        val unknown = HostileArchetypeId("not-a-real-archetype")
        assertEquals("unknown id resolves to DEFAULT", LootTable.DEFAULT, LootTable.lootFor(unknown))
        repeat(100) { i ->
            val result = LootTable.roll(unknown, "salvage:z:$i")
            assertTrue(
                "DEFAULT credits $${result.credits} in span",
                result.credits in LootTable.DEFAULT.creditsMin..LootTable.DEFAULT.creditsMax,
            )
            assertTrue("DEFAULT profile drops no resources", result.resources.isEmpty())
        }
    }

    @Test
    fun `lootFor resolves each catalogued archetype to its own authored profile`() {
        assertEquals(LootTable.RAIDER, LootTable.lootFor(raider))
        assertEquals(LootTable.SCAVENGER, LootTable.lootFor(scavenger))
    }
}
