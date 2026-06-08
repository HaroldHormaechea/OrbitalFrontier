package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC06 mining playthrough (AC#7), following the record→persist→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc06-mine` artifact thrusts the ship into the authored `alpha-belt` field and holds
 * MINE until the hold fills. This test replays it headlessly and asserts:
 *  - **conservation**: the units in cargo equal the units removed from the field
 *    (`Σ(initial deposits) − Σ(remaining)`), so mining neither creates nor destroys matter;
 *  - the exact per-resource cargo (ordinal-order greedy fill to capacity);
 *  - the hold ends **full** (the capacity stop, AC#3);
 *  - the field is **partially depleted**, not emptied (remaining > 0, AC#4) — the field holds more
 *    than the hold's capacity, so a full hold still leaves deposits behind;
 *  - the replay is bit-for-bit deterministic across two runs.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc06Mine] and guarded by [PlaythroughFixtureTest].
 */
class Uc06MineReplayTest {
    private val alphaBelt = PoiId("alpha-belt")

    private fun loadMine(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC06_MINE)

    /** The authored pristine deposits of the alpha-belt, read from the production map. */
    private fun initialDeposits(): Map<ResourceType, Int> =
        MvpSectorMap.build().sector(MvpSectorMap.START_SECTOR).asteroidField(alphaBelt)!!.deposits

    @Test
    fun `mining the alpha-belt fills the hold and partially depletes the field, conserving units`() {
        val state = ReplayRunner().run(loadMine()).finalState

        val cargo = state.cargo
        val remaining = state.fieldDepletion[alphaBelt]
        assertTrue("the run should have depleted the alpha-belt", remaining != null)

        // AC#3: the hold ends full.
        assertTrue("the hold should end full", cargo.isFull)
        assertEquals("a full starter hold holds 50 units", 50, cargo.usedUnits)

        // Exact per-resource cargo: ordinal greedy fill of the 50-unit hold from the 70-unit field —
        // all 20 Hydrogen, all 15 Water-Ice, then 15 of the Iron-Ore; no Copper (capacity reached).
        assertEquals(
            mapOf(ResourceType.HYDROGEN to 20, ResourceType.WATER_ICE to 15, ResourceType.IRON_ORE to 15),
            cargo.contents,
        )

        // Conservation: cargo units == units removed from the field.
        val initialSum = initialDeposits().values.sum()
        val remainingSum = remaining!!.values.sum()
        assertEquals("mined units must equal field units removed", initialSum - remainingSum, cargo.usedUnits)

        // AC#4: the field is partially depleted, not emptied (it holds more than the hold's capacity).
        assertTrue("the field should still hold deposits", remainingSum > 0)
        assertEquals("20 units remain in the field (70 − 50)", initialSum - 50, remainingSum)
    }

    @Test
    fun `replay through mining is deterministic`() {
        val first = ReplayRunner().run(loadMine()).finalState
        val second = ReplayRunner().run(loadMine()).finalState

        // SimulationState data-class equality covers cargo + fieldDepletion as well as kinematics.
        assertEquals(first, second)
    }
}
