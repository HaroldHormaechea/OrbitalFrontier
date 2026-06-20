package com.orbitalfrontier.world

import com.orbitalfrontier.combat.LootTable
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [DerelictSalvage] resolver (UC54 AC#2/#4) — scavenging a [Derelict] wreck.
 *
 * All against the production [MvpSectorMap] (the Beta `beta-derelict` wreck), so the geometry tracks the
 * real authored map. The headline contract:
 *  - a [ScavengeAction.SCAVENGE] within [Derelict.salvageRadius] of an **un-consumed** derelict rolls the
 *    shared [LootTable.DERELICT] profile keyed `"derelict:$id"`, pours it into the hold via the **shared**
 *    [Salvage.fillCargo] helper (capacity-respecting), and marks the wreck consumed (AC#4);
 *  - the loot is **derived** from the production loot path (no magic literals), so a loot retune trips it;
 *  - an already-consumed wreck yields nothing; an out-of-range / [ScavengeAction.NONE] tick is a strict
 *    same-instance no-op (byte-identical).
 */
class DerelictSalvageTest {
    private val world: SectorWorld = MvpSectorMap.build()
    private val beta: SectorId = SectorId("beta")

    /** The authored Beta derelict — real geometry, read from the production map. */
    private val derelict: Derelict = world.sector(beta).derelicts.single()

    /** The loot the production path rolls for this wreck — derived, never a magic literal. */
    private val expectedLoot = LootTable.roll(LootTable.DERELICT, "derelict:${derelict.id.value}")

    private fun resolve(
        shipPosition: Vec2,
        cargo: Cargo = Cargo.empty(),
        consumedPois: Set<PoiId> = emptySet(),
        action: ScavengeAction = ScavengeAction.SCAVENGE,
    ) = DerelictSalvage.resolve(world, beta, shipPosition, cargo, consumedPois, action)

    // --- AC#2/#4: scavenge within range of an un-consumed derelict ---

    @Test
    fun `scavenging an un-consumed derelict in range rolls loot, fills cargo, and marks it consumed`() {
        // Sanity: the pinned loot table must yield something, else the assertions are vacuous.
        assertTrue(
            "the DERELICT loot profile must yield resources for the test to be meaningful",
            expectedLoot.resources.isNotEmpty(),
        )

        val result = resolve(derelict.position)

        // The exact wreck was scavenged and marked consumed (AC#4).
        assertEquals("the in-range derelict is the scavenged target", derelict.id, result.scavenged)
        assertTrue("the scavenged derelict is marked consumed", derelict.id in result.consumedPois)

        // Cargo equals exactly what the shared fill helper yields for this loot (derived — mirrors production).
        val expectedFill = Salvage.fillCargo(Cargo.empty(), expectedLoot.resources)
        assertEquals("the hold equals the shared-fill result for the rolled loot", expectedFill.cargo, result.cargo)
        assertEquals("acceptedUnits matches the shared fill", expectedFill.acceptedUnits, result.acceptedUnits)
        assertEquals("no overflow into a default-capacity empty hold", expectedFill.leftover.isNotEmpty(), result.overflow)
        assertFalse("a default-capacity hold does not overflow on a single small wreck", result.overflow)
    }

    @Test
    fun `the loot roll is seed-deterministic — identical inputs yield identical results`() {
        val a = resolve(derelict.position)
        val b = resolve(derelict.position)
        assertEquals("same seed → same cargo", a.cargo, b.cargo)
        assertEquals("same seed → same consumed set", a.consumedPois, b.consumedPois)
        assertEquals("same seed → same accepted units", a.acceptedUnits, b.acceptedUnits)
    }

    // --- AC#4: a consumed derelict yields nothing ---

    @Test
    fun `an already-consumed derelict yields nothing (a scavenged wreck stays empty)`() {
        val cargo = Cargo.empty()
        val consumed = setOf(derelict.id)
        val result = resolve(derelict.position, cargo = cargo, consumedPois = consumed)

        assertNull("a consumed wreck is not re-scavenged", result.scavenged)
        assertSame("the SAME cargo instance threads through (byte-identical no-op)", cargo, result.cargo)
        assertSame("the SAME consumed set threads through", consumed, result.consumedPois)
        assertEquals("nothing is accepted", 0, result.acceptedUnits)
        assertFalse("no overflow on a no-op", result.overflow)
    }

    // --- proximity gating + action gating: strict same-instance no-ops ---

    @Test
    fun `a SCAVENGE out of range is a same-instance no-op`() {
        val cargo = Cargo.empty()
        val consumed = emptySet<PoiId>()
        val outOfRange = derelict.position + Vec2(derelict.salvageRadius + 1f, 0f)
        val result = DerelictSalvage.resolve(world, beta, outOfRange, cargo, consumed, ScavengeAction.SCAVENGE)

        assertNull("nothing in range to scavenge", result.scavenged)
        assertSame("the SAME cargo instance threads through", cargo, result.cargo)
        assertSame("the SAME consumed set threads through", consumed, result.consumedPois)
    }

    @Test
    fun `a NONE action is a same-instance no-op even in range`() {
        val cargo = Cargo.empty()
        val consumed = emptySet<PoiId>()
        val result = resolve(derelict.position, cargo = cargo, consumedPois = consumed, action = ScavengeAction.NONE)

        assertNull("NONE never scavenges", result.scavenged)
        assertSame("the SAME cargo instance threads through", cargo, result.cargo)
        assertSame("the SAME consumed set threads through", consumed, result.consumedPois)
    }

    // --- AC#2: cargo fill respects capacity (overflow) via the shared helper ---

    @Test
    fun `a wreck larger than the remaining hold overflows, accepting only what fits`() {
        // A near-full hold (capacity 1, empty) forces an overflow if the wreck yields more than 1 unit.
        val totalLoot = expectedLoot.resources.values.sum()
        assertTrue("the rolled loot must exceed 1 unit for the overflow test to bite", totalLoot > 1)

        val tinyHold = Cargo.empty(capacity = 1)
        val result = resolve(derelict.position, cargo = tinyHold)

        // The wreck is still consumed ("picked clean") even on a partial fill, and the fill matches the helper.
        assertEquals("the wreck is scavenged", derelict.id, result.scavenged)
        assertTrue("the wreck is consumed even on overflow", derelict.id in result.consumedPois)
        assertTrue("a hold-full wreck reports overflow", result.overflow)

        val expectedFill = Salvage.fillCargo(tinyHold, expectedLoot.resources)
        assertEquals("the partial fill equals the shared-fill result", expectedFill.cargo, result.cargo)
        assertEquals("acceptedUnits matches the shared (capped) fill", expectedFill.acceptedUnits, result.acceptedUnits)
        assertTrue("at most the capacity is accepted", result.acceptedUnits <= 1)
    }
}
