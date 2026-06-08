package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Respawn] (UC13 AC#5 — **no permadeath**) — the forgiving destruction rule.
 *
 * Pins: respawn relocates the player to the last docked station at rest, jettisons
 * `floor(usedUnits * fraction)` cargo units **deterministically in [ResourceType] order**, fully
 * repairs the ship, and clears the encounter. The penalty is only cargo — there is no permadeath.
 */
class RespawnTest {
    private val params = CombatParams() // respawnCargoLossFraction 0.25

    @Test
    fun `respawn relocates to the station at rest, repairs sections, and clears combat`() {
        val station = Vec2(0f, 600f)
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 8), Cargo.DEFAULT_CAPACITY)

        val result = Respawn.respawn(station, cargo, params)

        assertEquals("reappears at the station", station, result.kinematics.position)
        assertEquals("reappears at rest", Vec2.ZERO, result.kinematics.velocity)
        assertSame("sections are fully repaired (pristine)", SectionDamages.PRISTINE, result.sectionDamage)
        assertEquals("the encounter is cleared", CombatState.NONE, result.combat)
    }

    @Test
    fun `a quarter of the hold is jettisoned (floored), the rest is kept`() {
        // 8 units, 0.25 -> floor(2.0) = 2 lost, 6 kept.
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 8), Cargo.DEFAULT_CAPACITY)
        val result = Respawn.respawn(Vec2(0f, 600f), cargo, params)

        assertEquals("two units are lost", 2, result.unitsLost)
        assertEquals("six units survive (forgiving)", 6, result.cargo.usedUnits)
        assertEquals("the survivors are the same resource", 6, result.cargo.contents[ResourceType.HYDROGEN])
    }

    @Test
    fun `the loss is floored - a tiny hold can lose nothing`() {
        // 3 units * 0.25 = 0.75 -> floor 0 lost.
        val cargo = Cargo(mapOf(ResourceType.IRON_ORE to 3), Cargo.DEFAULT_CAPACITY)
        val result = Respawn.respawn(Vec2.ZERO, cargo, params)
        assertEquals("a small hold loses nothing", 0, result.unitsLost)
        assertEquals("the hold is untouched", 3, result.cargo.usedUnits)
    }

    @Test
    fun `cargo is jettisoned deterministically in ResourceType declaration order`() {
        // 16 used units -> floor(16*0.25) = 4 lost, removed from the FIRST ResourceType(s) in declaration order.
        val ordered = ResourceType.entries
        val first = ordered[0]
        val second = ordered[1]
        val cargo = Cargo(mapOf(first to 3, second to 13), Cargo.DEFAULT_CAPACITY)

        val result = Respawn.respawn(Vec2.ZERO, cargo, params)

        assertEquals("four units lost in total", 4, result.unitsLost)
        // All 3 of the first resource go, then 1 of the second.
        assertEquals("the first declared resource is jettisoned first", 0, result.cargo.contents[first] ?: 0)
        assertEquals("the remainder comes off the next resource", 12, result.cargo.contents[second])
        assertEquals("12 units survive", 12, result.cargo.usedUnits)
    }

    @Test
    fun `respawn is deterministic - identical inputs yield an identical result`() {
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 9, ResourceType.IRON_ORE to 7), Cargo.DEFAULT_CAPACITY)
        val a = Respawn.respawn(Vec2(10f, 20f), cargo, params)
        val b = Respawn.respawn(Vec2(10f, 20f), cargo, params)
        assertEquals(a, b)
    }

    @Test
    fun `an empty hold respawns with nothing lost (never negative)`() {
        val result = Respawn.respawn(Vec2.ZERO, Cargo.empty(Cargo.DEFAULT_CAPACITY), params)
        assertEquals(0, result.unitsLost)
        assertTrue("the hold stays empty", result.cargo.contents.isEmpty())
    }
}
