package com.orbitalfrontier.outfit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Loadout] (UC09 AC#1/#2) — the gap-tolerant `(category, slotIndex) → UpgradeId` map.
 *
 * Pins the contract the outfitting resolver and persistence rely on: install fills the **lowest free
 * slot** and refills a gap before growing; remove leaves a **real gap** (indices are never compacted),
 * so slot identity is stable across a junkyard removal; an emptied category drops out so the
 * representation stays canonical; and equality is order-insensitive (record/replay determinism).
 */
class LoadoutTest {
    private val engineA = UpgradeId("engine-tune-i")
    private val engineB = UpgradeId("engine-tune-ii")
    private val cargo = UpgradeId("cargo-pod-i")

    @Test
    fun `the empty loadout has nothing installed`() {
        assertTrue(Loadout.EMPTY.isEmpty)
        assertEquals(0, Loadout.EMPTY.installedCount(SlotCategory.ENGINES))
        assertNull(Loadout.EMPTY.upgradeAt(SlotCategory.ENGINES, 0))
        assertTrue(Loadout.EMPTY.allInstalled().isEmpty())
    }

    @Test
    fun `install fills the lowest free slot index`() {
        val first = Loadout.EMPTY.install(SlotCategory.ENGINES, slotCount = 2, engineA)
        first as InstallResult.Installed
        assertEquals("first install takes slot 0", 0, first.slotIndex)

        val second = first.loadout.install(SlotCategory.ENGINES, slotCount = 2, engineB)
        second as InstallResult.Installed
        assertEquals("second install takes slot 1", 1, second.slotIndex)

        assertEquals(engineA, second.loadout.upgradeAt(SlotCategory.ENGINES, 0))
        assertEquals(engineB, second.loadout.upgradeAt(SlotCategory.ENGINES, 1))
        assertEquals(2, second.loadout.installedCount(SlotCategory.ENGINES))
    }

    @Test
    fun `install into a full category returns NoFreeSlot and changes nothing`() {
        val full =
            (Loadout.EMPTY.install(SlotCategory.ENGINES, slotCount = 1, engineA) as InstallResult.Installed).loadout

        val result = full.install(SlotCategory.ENGINES, slotCount = 1, engineB)

        assertSame(InstallResult.NoFreeSlot, result)
    }

    @Test
    fun `install respects a zero slot count for the category`() {
        val result = Loadout.EMPTY.install(SlotCategory.WEAPONS, slotCount = 0, engineA)
        assertSame(InstallResult.NoFreeSlot, result)
    }

    @Test
    fun `remove leaves a real gap and does not compact later indices`() {
        // Fill slots 0,1,2.
        var loadout = Loadout.EMPTY
        loadout = (loadout.install(SlotCategory.ENGINES, 3, engineA) as InstallResult.Installed).loadout
        loadout = (loadout.install(SlotCategory.ENGINES, 3, engineB) as InstallResult.Installed).loadout
        loadout = (loadout.install(SlotCategory.ENGINES, 3, cargo) as InstallResult.Installed).loadout

        // Remove the MIDDLE slot (index 1).
        val removed = loadout.remove(SlotCategory.ENGINES, 1)
        removed as RemoveResult.Removed
        assertEquals(engineB, removed.removed)

        // Slot 1 is now a gap; slots 0 and 2 keep their indices (no compaction).
        assertEquals(engineA, removed.loadout.upgradeAt(SlotCategory.ENGINES, 0))
        assertNull("slot 1 is a real gap", removed.loadout.upgradeAt(SlotCategory.ENGINES, 1))
        assertEquals(cargo, removed.loadout.upgradeAt(SlotCategory.ENGINES, 2))
        assertEquals(2, removed.loadout.installedCount(SlotCategory.ENGINES))
    }

    @Test
    fun `install refills a gap before growing`() {
        var loadout = Loadout.EMPTY
        loadout = (loadout.install(SlotCategory.ENGINES, 3, engineA) as InstallResult.Installed).loadout
        loadout = (loadout.install(SlotCategory.ENGINES, 3, engineB) as InstallResult.Installed).loadout
        // Free slot 0.
        loadout = (loadout.remove(SlotCategory.ENGINES, 0) as RemoveResult.Removed).loadout

        // Next install should reuse the freed slot 0, not slot 2.
        val refilled = loadout.install(SlotCategory.ENGINES, 3, cargo)
        refilled as InstallResult.Installed
        assertEquals("the freed gap (slot 0) is refilled before growing", 0, refilled.slotIndex)
    }

    @Test
    fun `removing an empty slot returns EmptySlot and changes nothing`() {
        assertSame(RemoveResult.EmptySlot, Loadout.EMPTY.remove(SlotCategory.ENGINES, 0))

        val filled =
            (Loadout.EMPTY.install(SlotCategory.ENGINES, 2, engineA) as InstallResult.Installed).loadout
        assertSame("removing an empty index in a non-empty category", RemoveResult.EmptySlot, filled.remove(SlotCategory.ENGINES, 1))
    }

    @Test
    fun `removing the last part in a category drops the category entirely`() {
        val filled =
            (Loadout.EMPTY.install(SlotCategory.ENGINES, 1, engineA) as InstallResult.Installed).loadout
        val emptied = (filled.remove(SlotCategory.ENGINES, 0) as RemoveResult.Removed).loadout

        // Canonical empty representation — the emptied category is not retained as an empty inner map.
        assertTrue(emptied.isEmpty)
        assertEquals(Loadout.EMPTY, emptied)
    }

    @Test
    fun `two loadouts with the same parts in the same slots are equal regardless of build order`() {
        val a =
            (
                (Loadout.EMPTY.install(SlotCategory.ENGINES, 2, engineA) as InstallResult.Installed).loadout
                    .install(SlotCategory.CARGO, 2, cargo) as InstallResult.Installed
            ).loadout
        val b =
            (
                (Loadout.EMPTY.install(SlotCategory.CARGO, 2, cargo) as InstallResult.Installed).loadout
                    .install(SlotCategory.ENGINES, 2, engineA) as InstallResult.Installed
            ).loadout

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
