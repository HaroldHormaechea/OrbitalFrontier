package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.LootTable
import com.orbitalfrontier.world.MvpSectorMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC42 loot-&-salvage playthrough (AC#5), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc42-loot-salvage` artifact flies the player into the natural `alpha-raider-picket` zone,
 * destroys the ambushing RAIDER with the auto-aim turret, then loiters so the wreck it drops is collected.
 * This test replays it headlessly and asserts the AC#5 contract:
 *  - a wreck actually **existed** at some tick (`perTickStates.any { it.salvage.isNotEmpty() }`, AC#1);
 *  - it was **collected** — the world is clear of salvage at the end (`finalState.salvage` empty, AC#2);
 *  - the player's final **credits** and **cargo** equal the values the production salvage path derives from
 *    [LootTable.roll] for that exact kill — **derived, never a magic literal**, so a future loot retune
 *    fails this test loudly instead of silently drifting (AC#1/#2/#4);
 *  - the replay is **bit-for-bit deterministic** across two runs (AC#4).
 *
 * The fixture uses a **natural** encounter zone, NOT a bounty zone, so the only credit source in the run is
 * salvage — the gain is attributable to the loot table alone, side-stepping the UC41 bounty double-count
 * pitfall. The zone's single RAIDER is the first hostile spawned, so its [com.orbitalfrontier.combat.HostileId]
 * is `0`, and the production seed key is `"salvage:${zone.id}:0"` (see
 * [com.orbitalfrontier.combat.Salvage.spawn]).
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc42LootSalvage] and guarded by
 * [PlaythroughFixtureTest]; it is also loadable via the `playtest` skill
 * (`-Dplaythrough.name=uc42-loot-salvage`).
 */
class Uc42LootSalvageReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC42_LOOT_SALVAGE)

    /** The one natural encounter zone of the START_SECTOR — the kill site the fixture flies into. */
    private val zone = MvpSectorMap.encounterZones(MvpSectorMap.START_SECTOR).single()

    /**
     * The loot the production salvage path rolls for this kill: the zone's archetype keyed by the
     * deterministic seed `"salvage:${zone.id}:${hostileId.value}"`. The single ambushing hostile is the first
     * spawned in the encounter, so its id value is `0`. The replayed credits + cargo are asserted **equal to
     * this** — no magic numbers, so a loot retune (which would regenerate the fixture) trips this assertion.
     */
    private val expectedLoot = LootTable.roll(zone.archetypeId, "salvage:${zone.id}:0")

    @Test
    fun `destroying a hostile drops salvage that the player collects into credits and cargo`() {
        val result = ReplayRunner().run(load(), capturePerTickStates = true)
        val finalState = result.finalState

        // Sanity: this kill must actually yield something collectable, else the assertions below are vacuous
        // (and a credits-and-resources-empty drop would never be removed, failing the "collected" check anyway).
        assertTrue(
            "the pinned loot table must yield something collectable for the test to be meaningful",
            expectedLoot.credits > 0L || expectedLoot.resources.isNotEmpty(),
        )

        // AC#1: a wreck existed in the world at some intermediate tick (a kill dropped salvage).
        val wreckSpawned = result.perTickStates.any { it.salvage.isNotEmpty() }
        assertTrue("a destroyed hostile dropped a salvage wreck", wreckSpawned)

        // AC#2: the wreck was collected — no salvage remains floating at the end of the run.
        assertTrue("the wreck was collected (no salvage left in the world)", finalState.salvage.isEmpty())

        // AC#1/#2/#4: credits + cargo equal exactly what LootTable.roll yields for this kill (derived, not a
        // literal). Credits started at 0 with no other income source (natural zone, no bounty), so the whole
        // balance is salvage.
        assertEquals("final credits equal the rolled salvage credits", expectedLoot.credits, finalState.credits)
        assertEquals("final cargo equals the rolled salvage resources", expectedLoot.resources, finalState.cargo.contents)
    }

    @Test
    fun `the loot-salvage replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(load(), capturePerTickStates = true)
        val second = ReplayRunner().run(load(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }
}
