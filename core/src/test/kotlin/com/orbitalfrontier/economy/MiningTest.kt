package com.orbitalfrontier.economy

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.world.AsteroidField
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.Mining
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Sector
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.SectorWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Mining] (UC06 AC#2/#4/#6) — the pure, deterministic asteroid-mining resolution.
 *
 * Built on a tiny hand-authored single-sector [SectorWorld] (no gates) so the geometry and deposits
 * are fully controlled, exercising:
 *  - extraction of up to the per-tick budget in [ResourceType] ordinal order (AC#2);
 *  - the running remaining-capacity thread within a tick (budget spilling across resources);
 *  - the capacity stop (mining never overfills the hold, AC#3);
 *  - field depletion (a deposit is never over-mined; depletion persists into the result map, AC#4);
 *  - the no-op cases (out of range, [MineAction.NONE], full hold, empty field) returning the inputs
 *    unchanged;
 *  - determinism (identical inputs → identical result).
 *
 * Mining is engine-free, so these are plain JVM unit tests (AC#6).
 */
class MiningTest {
    private val sectorId = SectorId("test")
    private val fieldId = PoiId("test-belt")
    private val origin = Vec2(0f, 0f)

    /** Pristine deposits: Hydrogen 20, Water-Ice 15, Iron-Ore 25, Copper 10 (total 70). */
    private val pristineDeposits =
        mapOf(
            ResourceType.HYDROGEN to 20,
            ResourceType.WATER_ICE to 15,
            ResourceType.IRON_ORE to 25,
            ResourceType.COPPER to 10,
        )

    private fun worldWith(deposits: Map<ResourceType, Int> = pristineDeposits): SectorWorld {
        val field = AsteroidField(id = fieldId, position = origin, miningRadius = 100f, deposits = deposits)
        val sector = Sector(id = sectorId, displayName = "Test Sector", pois = listOf(field), contentExtent = 1000f)
        return SectorWorld(listOf(sector))
    }

    private fun resolve(
        world: SectorWorld,
        cargo: Cargo,
        fieldDepletion: Map<PoiId, Map<ResourceType, Int>> = emptyMap(),
        shipPosition: Vec2 = origin,
        action: MineAction = MineAction.MINE,
        params: MiningParams = MiningParams(),
    ) = Mining.resolve(world, sectorId, shipPosition, cargo, fieldDepletion, action, params)

    @Test
    fun `availableField reports the field in range and null out of range`() {
        val world = worldWith()

        assertEquals(fieldId, Mining.availableField(world, sectorId, origin)?.id)
        // 100 wu away along +x == exactly the radius (inclusive) -> in range.
        assertEquals(fieldId, Mining.availableField(world, sectorId, Vec2(100f, 0f))?.id)
        // Just outside the radius -> no field.
        assertEquals(null, Mining.availableField(world, sectorId, Vec2(101f, 0f)))
    }

    @Test
    fun `one tick extracts up to the budget in ordinal order from the first resource`() {
        val result = resolve(worldWith(), Cargo.empty(), params = MiningParams(extractionUnitsPerTick = 2))

        assertEquals(2, result.minedUnits)
        // Hydrogen is the lowest ordinal, so the 2-unit budget is taken from it first.
        assertEquals(mapOf(ResourceType.HYDROGEN to 2), result.cargo.contents)
        // The field's Hydrogen drops by 2; the other deposits are untouched.
        assertEquals(
            mapOf(
                ResourceType.HYDROGEN to 18,
                ResourceType.WATER_ICE to 15,
                ResourceType.IRON_ORE to 25,
                ResourceType.COPPER to 10,
            ),
            result.fieldDepletion[fieldId],
        )
    }

    @Test
    fun `the per-tick budget spills across resources in ordinal order (running capacity thread)`() {
        // Only 1 Hydrogen left; a 2-unit budget takes that 1, then spills to the next resource.
        val depletion = mapOf(fieldId to mapOf(ResourceType.HYDROGEN to 1, ResourceType.WATER_ICE to 15))

        val result =
            resolve(
                worldWith(),
                Cargo.empty(),
                fieldDepletion = depletion,
                params = MiningParams(extractionUnitsPerTick = 2),
            )

        assertEquals(2, result.minedUnits)
        assertEquals(mapOf(ResourceType.HYDROGEN to 1, ResourceType.WATER_ICE to 1), result.cargo.contents)
        assertEquals(0, result.fieldDepletion[fieldId]?.get(ResourceType.HYDROGEN))
        assertEquals(14, result.fieldDepletion[fieldId]?.get(ResourceType.WATER_ICE))
    }

    @Test
    fun `mining stops at cargo capacity and never overfills the hold`() {
        // A near-unbounded budget but a tiny 5-unit hold: only 5 units are taken (capacity stop).
        val hold = Cargo.empty(capacity = 5)

        val result = resolve(worldWith(), hold, params = MiningParams(extractionUnitsPerTick = 1000))

        assertEquals(5, result.minedUnits)
        assertTrue("the hold is full", result.cargo.isFull)
        assertEquals(5, result.cargo.usedUnits)
        // The 5 units come from Hydrogen (first ordinal); 15 remain in the field's Hydrogen.
        assertEquals(mapOf(ResourceType.HYDROGEN to 5), result.cargo.contents)
        assertEquals(15, result.fieldDepletion[fieldId]?.get(ResourceType.HYDROGEN))
    }

    @Test
    fun `a deposit is never over-mined and an emptied field then no-ops`() {
        // A small field (total 3 units) with a budget far exceeding it: only 3 units come out.
        val world = worldWith(mapOf(ResourceType.HYDROGEN to 2, ResourceType.IRON_ORE to 1))

        val first = resolve(world, Cargo.empty(), params = MiningParams(extractionUnitsPerTick = 1000))
        assertEquals("can't mine more than the field holds", 3, first.minedUnits)
        assertEquals(mapOf(ResourceType.HYDROGEN to 2, ResourceType.IRON_ORE to 1), first.cargo.contents)
        assertEquals(0, first.fieldDepletion[fieldId]?.values?.sum())

        // Mining the now-empty field again is a no-op.
        val second =
            resolve(
                world,
                first.cargo,
                fieldDepletion = first.fieldDepletion,
                params = MiningParams(extractionUnitsPerTick = 1000),
            )
        assertEquals(0, second.minedUnits)
        assertSame("an empty field returns the same cargo instance", first.cargo, second.cargo)
        assertSame("an empty field returns the same depletion map", first.fieldDepletion, second.fieldDepletion)
    }

    @Test
    fun `out of range is a no-op`() {
        val cargo = Cargo.empty()

        val result = resolve(worldWith(), cargo, shipPosition = Vec2(500f, 0f))

        assertEquals(0, result.minedUnits)
        assertSame(cargo, result.cargo)
        assertTrue(result.fieldDepletion.isEmpty())
    }

    @Test
    fun `a NONE action is a no-op even in range`() {
        val cargo = Cargo.empty()

        val result = resolve(worldWith(), cargo, action = MineAction.NONE)

        assertEquals(0, result.minedUnits)
        assertSame(cargo, result.cargo)
    }

    @Test
    fun `mining with a full hold is a no-op`() {
        val full = Cargo(mapOf(ResourceType.IRON_ORE to 50), capacity = 50)

        val result = resolve(worldWith(), full)

        assertEquals(0, result.minedUnits)
        assertSame(full, result.cargo)
    }

    @Test
    fun `resolution is deterministic for identical inputs`() {
        val world = worldWith()
        val cargo = Cargo.empty()
        val params = MiningParams(extractionUnitsPerTick = 7)

        val a = resolve(world, cargo, params = params)
        val b = resolve(world, cargo, params = params)

        assertEquals(a.minedUnits, b.minedUnits)
        assertEquals(a.cargo, b.cargo)
        assertEquals(a.fieldDepletion, b.fieldDepletion)
    }

    @Test
    fun `mining to a full hold drains lower ordinals first and leaves the field partially depleted`() {
        // Fill a default 50-unit hold from the 70-unit field in one big tick.
        val result = resolve(worldWith(), Cargo.empty(), params = MiningParams(extractionUnitsPerTick = 50))

        assertTrue(result.cargo.isFull)
        // Ordinal greedy: all 20 Hydrogen + all 15 Water-Ice + 15 of the Iron-Ore = 50; no Copper.
        assertEquals(
            mapOf(ResourceType.HYDROGEN to 20, ResourceType.WATER_ICE to 15, ResourceType.IRON_ORE to 15),
            result.cargo.contents,
        )
        val remaining = result.fieldDepletion.getValue(fieldId)
        assertEquals(0, remaining[ResourceType.HYDROGEN])
        assertEquals(0, remaining[ResourceType.WATER_ICE])
        assertEquals(10, remaining[ResourceType.IRON_ORE])
        assertEquals(10, remaining[ResourceType.COPPER])
        assertTrue("the field is only partially depleted", remaining.values.sum() > 0)
    }
}
