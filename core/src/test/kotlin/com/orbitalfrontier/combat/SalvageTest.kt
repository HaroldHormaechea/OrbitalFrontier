package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Salvage] (UC42) — the shared, pure salvage logic the device loop and the headless
 * replay mirror both call (the lockstep contract, project rule #1).
 *
 * Covers:
 *  - **spawn** (AC#1/#4): one deterministic, [LootTable]-rolled wreck per kill, minted in monotonic
 *    [SalvageId] order; a no-op (same instance) when nothing was destroyed.
 *  - **collect** (AC#2/#3): proximity pickup — credits to the wallet, resources into [Cargo] in
 *    [ResourceType] declaration order, deterministic overflow when the hold fills (partial pickup,
 *    leftover left behind, the drop's credits zeroed), [SalvageId]-order processing, and a true
 *    same-instance no-op when nothing is collectable this tick.
 */
class SalvageTest {
    private val raiderId = HostileArchetypes.RAIDER.id
    private val zone = "alpha-raider-picket"

    private fun hostile(
        idValue: Long,
        pos: Vec2,
    ) = DestroyedHostile(HostileId(idValue), raiderId, pos)

    // ---- spawn ---------------------------------------------------------------------------------

    @Test
    fun `spawn with no kills is a no-op returning the same list instance and unchanged allocator`() {
        val existing = listOf(SalvageDrop(SalvageId(0), Vec2(1f, 2f), 10L, emptyMap()))
        val result = Salvage.spawn(existing, nextSalvageId = 1L, zoneId = zone, destroyed = emptyList())

        assertSame("an empty kill list must return the SAME drop list (byte-identical quiet tick)", existing, result.drops)
        assertEquals("the allocator is untouched", 1L, result.nextSalvageId)
    }

    @Test
    fun `spawn mints one wreck per kill at the kill position with the loot-table roll`() {
        val pos = Vec2(900f, 12f)
        val result = Salvage.spawn(emptyList(), nextSalvageId = 0L, zoneId = zone, destroyed = listOf(hostile(0L, pos)))

        assertEquals("one kill mints one wreck", 1, result.drops.size)
        val drop = result.drops.single()
        assertEquals("the wreck takes the allocator's id", SalvageId(0L), drop.id)
        assertEquals("the wreck floats at the kill position", pos, drop.position)
        assertEquals("the allocator advanced past the minted id", 1L, result.nextSalvageId)

        // Loot is exactly the LootTable roll for this zone + hostile id (no hidden randomness, AC#4).
        val expected = LootTable.roll(raiderId, "salvage:$zone:0")
        assertEquals("credits come from the loot table", expected.credits, drop.credits)
        assertEquals("resources come from the loot table", expected.resources, drop.resources)
    }

    @Test
    fun `spawn appends multiple kills in monotonic id order seeded per hostile`() {
        val existing = listOf(SalvageDrop(SalvageId(0), Vec2.ZERO, 5L, emptyMap()))
        val kills = listOf(hostile(3L, Vec2(10f, 0f)), hostile(7L, Vec2(20f, 0f)))
        val result = Salvage.spawn(existing, nextSalvageId = 1L, zoneId = zone, destroyed = kills)

        assertEquals("existing drops are preserved, two new ones appended", 3, result.drops.size)
        assertEquals("ids are minted monotonically from the allocator", listOf(0L, 1L, 2L), result.drops.map { it.id.value })
        assertEquals("the allocator advanced by the kill count", 3L, result.nextSalvageId)

        // Each wreck is seeded by ITS OWN hostile id, not the salvage id.
        assertEquals(LootTable.roll(raiderId, "salvage:$zone:3").credits, result.drops[1].credits)
        assertEquals(LootTable.roll(raiderId, "salvage:$zone:7").credits, result.drops[2].credits)
    }

    @Test
    fun `spawn is fully deterministic - identical inputs yield identical drops (AC#4)`() {
        val kills = listOf(hostile(0L, Vec2(900f, 0f)))
        val a = Salvage.spawn(emptyList(), 0L, zone, kills)
        val b = Salvage.spawn(emptyList(), 0L, zone, kills)
        assertEquals("same inputs ⇒ same drops", a.drops, b.drops)
        assertEquals("same inputs ⇒ same allocator", a.nextSalvageId, b.nextSalvageId)
    }

    // ---- collect: no-ops -----------------------------------------------------------------------

    @Test
    fun `collect with no drops is a no-op returning the same instances`() {
        val cargo = Cargo.empty()
        val result = Salvage.collect(emptyList(), Vec2.ZERO, cargo, credits = 100L, pickupRadius = 64f)

        assertFalse(result.collectedAny)
        assertFalse(result.overflow)
        assertSame("cargo instance is unchanged", cargo, result.cargo)
        assertEquals("credits are unchanged", 100L, result.credits)
    }

    @Test
    fun `collect leaves an out-of-range drop untouched (same instances, nothing collected)`() {
        val cargo = Cargo.empty()
        val drops = listOf(SalvageDrop(SalvageId(0), Vec2(1000f, 0f), 50L, mapOf(ResourceType.IRON_ORE to 2)))
        val result = Salvage.collect(drops, playerPos = Vec2.ZERO, cargo = cargo, credits = 100L, pickupRadius = 64f)

        assertFalse("a drop beyond the pickup radius is not collected", result.collectedAny)
        assertSame("the drop list is returned unchanged", drops, result.drops)
        assertSame("cargo is unchanged", cargo, result.cargo)
        assertEquals("credits are unchanged", 100L, result.credits)
    }

    @Test
    fun `a full hold sitting on a credits-less wreck is a true no-op (no spam, same instances)`() {
        // A drop whose credits are already collected and whose remaining resources cannot fit must not
        // churn state every tick the player loiters on it.
        val full = Cargo(mapOf(ResourceType.IRON_ORE to 50), capacity = 50)
        val drops = listOf(SalvageDrop(SalvageId(0), Vec2.ZERO, credits = 0L, resources = mapOf(ResourceType.IRON_ORE to 3)))
        val result = Salvage.collect(drops, playerPos = Vec2.ZERO, cargo = full, credits = 500L, pickupRadius = 64f)

        assertFalse("nothing moved", result.collectedAny)
        assertSame("the drop list is the same instance", drops, result.drops)
        assertSame("cargo is the same instance", full, result.cargo)
        assertEquals("credits unchanged", 500L, result.credits)
    }

    // ---- collect: happy paths ------------------------------------------------------------------

    @Test
    fun `collect takes credits and loads resources for an in-range drop that fully fits, then removes it`() {
        val cargo = Cargo.empty(capacity = 50)
        val drops =
            listOf(
                SalvageDrop(
                    SalvageId(0),
                    Vec2(10f, 0f),
                    credits = 40L,
                    resources = mapOf(ResourceType.IRON_ORE to 3, ResourceType.COPPER to 2),
                ),
            )
        val result = Salvage.collect(drops, playerPos = Vec2(0f, 0f), cargo = cargo, credits = 100L, pickupRadius = 64f)

        assertTrue(result.collectedAny)
        assertFalse("everything fit, so no overflow", result.overflow)
        assertEquals("credits rose by the drop's value", 140L, result.credits)
        assertEquals("resources landed in cargo", mapOf(ResourceType.IRON_ORE to 3, ResourceType.COPPER to 2), result.cargo.contents)
        assertTrue("a fully collected drop is removed from the world", result.drops.isEmpty())
    }

    @Test
    fun `a credits-only wreck in range is collected and removed`() {
        val cargo = Cargo.empty()
        val drops = listOf(SalvageDrop(SalvageId(0), Vec2(5f, 5f), credits = 25L, resources = emptyMap()))
        val result = Salvage.collect(drops, Vec2.ZERO, cargo, credits = 0L, pickupRadius = 64f)

        assertTrue(result.collectedAny)
        assertEquals(25L, result.credits)
        assertTrue("the credits-only drop is gone", result.drops.isEmpty())
        assertSame("an empty cargo is returned as-is (no resources to add)", cargo, result.cargo)
    }

    // ---- collect: overflow (AC#3) --------------------------------------------------------------

    @Test
    fun `overflow leaves a partial pickup behind, zeroes the collected credits, and flags overflow (AC#3)`() {
        // Capacity 3, empty hold. Drop carries IRON_ORE x2 + COPPER x2 and 40 credits. ResourceType
        // declaration order is IRON_ORE before COPPER, so IRON_ORE fills first (2 units, 1 space left),
        // then COPPER takes 1, leaving COPPER x1 behind. The map below is built COPPER-first on purpose
        // to prove the fill order is ResourceType.entries, not map insertion order.
        val cargo = Cargo.empty(capacity = 3)
        val drop =
            SalvageDrop(
                SalvageId(0),
                Vec2.ZERO,
                credits = 40L,
                resources = linkedMapOf(ResourceType.COPPER to 2, ResourceType.IRON_ORE to 2),
            )
        val result = Salvage.collect(listOf(drop), Vec2.ZERO, cargo, credits = 100L, pickupRadius = 64f)

        assertTrue(result.collectedAny)
        assertTrue("the hold filled, so overflow is flagged for the UC35 notification", result.overflow)
        assertEquals("credits are always taken (they need no space)", 140L, result.credits)
        assertEquals(
            "IRON_ORE filled first (declaration order), COPPER partially",
            mapOf(ResourceType.IRON_ORE to 2, ResourceType.COPPER to 1),
            result.cargo.contents,
        )

        val leftover = result.drops.single()
        assertEquals("the drop stays in the world for a later pickup", SalvageId(0), leftover.id)
        assertEquals("its already-collected credits are zeroed so they can't be taken twice", 0L, leftover.credits)
        assertEquals("only the un-fitted remainder is left on the drop", mapOf(ResourceType.COPPER to 1), leftover.resources)
    }

    @Test
    fun `collect never accepts more units than the remaining capacity`() {
        val cargo = Cargo(mapOf(ResourceType.IRON_ORE to 48), capacity = 50)
        val drop = SalvageDrop(SalvageId(0), Vec2.ZERO, credits = 0L, resources = mapOf(ResourceType.IRON_ORE to 10))
        val result = Salvage.collect(listOf(drop), Vec2.ZERO, cargo, credits = 0L, pickupRadius = 64f)

        assertEquals("the hold is filled to capacity, never past it", 50, result.cargo.usedUnits)
        assertTrue(result.overflow)
        assertEquals("the 8 units that did not fit stay on the drop", mapOf(ResourceType.IRON_ORE to 8), result.drops.single().resources)
    }

    // ---- collect: SalvageId-order processing ---------------------------------------------------

    @Test
    fun `drops are processed in SalvageId order so a tight hold fills the lower-id wreck first`() {
        // Two in-range wrecks, capacity 4. Walking in id order: drop 0 (IRON_ORE x3) fits fully and is
        // removed; drop 1 (IRON_ORE x3) then takes the last unit and keeps IRON_ORE x2. Both drops' credits
        // are taken regardless. Reversing the processing order would change which drop survives, so this
        // pins the deterministic id-order contract.
        val cargo = Cargo.empty(capacity = 4)
        val drops =
            listOf(
                SalvageDrop(SalvageId(0), Vec2.ZERO, credits = 10L, resources = mapOf(ResourceType.IRON_ORE to 3)),
                SalvageDrop(SalvageId(1), Vec2.ZERO, credits = 20L, resources = mapOf(ResourceType.IRON_ORE to 3)),
            )
        val result = Salvage.collect(drops, Vec2.ZERO, cargo, credits = 0L, pickupRadius = 64f)

        assertEquals("both wrecks' credits were taken", 30L, result.credits)
        assertEquals("the hold filled to capacity", 4, result.cargo.usedUnits)
        assertTrue(result.overflow)
        val survivor = result.drops.single()
        assertEquals("the lower-id wreck was fully collected; the higher-id one partially survives", SalvageId(1), survivor.id)
        assertEquals("the survivor keeps only its remainder", mapOf(ResourceType.IRON_ORE to 2), survivor.resources)
        assertEquals("the survivor's credits are zeroed (already collected)", 0L, survivor.credits)
    }

    @Test
    fun `an in-range drop is collected while an out-of-range drop is left in place`() {
        val cargo = Cargo.empty()
        val near = SalvageDrop(SalvageId(0), Vec2(10f, 0f), credits = 15L, resources = emptyMap())
        val far = SalvageDrop(SalvageId(1), Vec2(5000f, 0f), credits = 99L, resources = mapOf(ResourceType.IRON_ORE to 1))
        val result = Salvage.collect(listOf(near, far), Vec2.ZERO, cargo, credits = 0L, pickupRadius = 64f)

        assertTrue(result.collectedAny)
        assertEquals("only the near drop's credits were taken", 15L, result.credits)
        assertEquals("the far drop remains untouched in the world", listOf(far), result.drops)
    }
}
