package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.LootTable
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.mission.BountyParams
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.world.MvpSectorMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC41 combat-bounty playthrough (AC#5), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc41-combat-bounty` artifact starts the player in flight just south of the
 * `bounty-alpha-raider` zone and in radio range of Alpha Station, accepts the broadcast bounty (UC12 radio),
 * crosses into the zone to spawn the contracted RAIDER, then holds FIRE while loitering so the crew-gated
 * auto-aim turret destroys it. This test replays it headlessly and asserts the AC#5 contract:
 *  - a fight actually happened: at some tick the encounter was **active** with a hostile present (AC#2);
 *  - the bounty ends **COMPLETED** with `killProgress == killTarget` (AC#3) and is gone from the offer list;
 *  - the player's credits rose by **exactly** the bounty reward **plus** the salvage the same kill now drops
 *    (UC42): the contract reward and the wreck's scrap credits are **distinct, stacking** income sources, so
 *    the final balance is `BountyParams.reward(1) + LootTable.roll(...).credits` — derived, no magic literal —
 *    and no *other* wallet movement occurs (AC#3);
 *  - **reputation is unchanged** — a bounty grants no standing today (the UC43 seam);
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * **UC42 interaction.** Before UC42 the only income was the 800-credit bounty reward. UC42 makes the
 * destroyed raider also drop a collectible wreck; the fixture player loiters on the kill spot, so within the
 * default pickup radius the wreck's scrap credits are collected and stack on top of the bounty. The total is
 * derived from the same [LootTable.roll] the production salvage path uses for this kill (zone
 * `bounty-alpha-raider`, the first-spawned hostile ⇒ id `0`) so a loot retune fails this assertion loudly
 * rather than silently shifting the balance.
 *
 * The RAIDER is AGGRESSIVE (never flees) and the player loiters inside the zone, so the only way the
 * encounter can clear is the raider's **destruction** — a cleared final state with the bounty COMPLETED
 * therefore means it was killed by the player.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc41CombatBounty] and guarded by
 * [PlaythroughFixtureTest]; it is also loadable via the `playtest` skill
 * (`-Dplaythrough.name=uc41-combat-bounty`).
 */
class Uc41CombatBountyReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC41_COMBAT_BOUNTY)

    /** The bounty target zone the contract spawns its RAIDER in — the kill site for both the reward and salvage. */
    private val bountyZone = MvpSectorMap.bountyTargetZone("bounty-alpha-raider")!!

    /** The contract payout: the default bounty tuning's reward for the kill-1 `bounty-alpha-raider` contract. */
    private val bountyReward: Long = BountyParams().reward(killTarget = 1)

    /**
     * The salvage credits the **same** kill drops (UC42), rolled from the production seed
     * `"salvage:${zone.id}:${hostileId.value}"` — the contracted raider is the first hostile spawned, so its id
     * value is `0`. Derived, not a literal: a loot retune (which regenerates the fixture) trips the assertion.
     */
    private val salvageCredits: Long = LootTable.roll(bountyZone.archetypeId, "salvage:${bountyZone.id}:0").credits

    /** The expected final balance: the bounty reward and the wreck's scrap credits stacked (distinct sources). */
    private val expectedCredits: Long = bountyReward + salvageCredits

    @Test
    fun `accepting a bounty, flying in, and destroying the target completes the bounty and pays the reward`() {
        val result = ReplayRunner().run(load(), capturePerTickStates = true)
        val finalState = result.finalState

        // AC#2: accepting spawned the contracted hostile — some intermediate tick had an active encounter.
        val fought = result.perTickStates.any { it.combat.active && it.combat.hostiles.isNotEmpty() }
        assertTrue("the contracted raider spawned and a fight occurred", fought)

        // The encounter cleared (raider never flees + player loitered ⇒ a cleared encounter means destroyed).
        assertFalse("the encounter is cleared at the end", finalState.combat.active)
        assertTrue("no hostiles remain", finalState.combat.hostiles.isEmpty())

        // AC#3: the bounty auto-completed and its kill quota is fully met.
        val bounty = finalState.missions.accepted.firstOrNull { it.type == MissionType.BOUNTY }
        assertNotNull("the accepted bounty is present in the log", bounty)
        assertEquals("the bounty is COMPLETED", MissionStatus.COMPLETED, bounty!!.status)
        assertEquals("kill progress reached the target", bounty.killTarget, bounty.killProgress)
        assertEquals("the kill target is the authored kill-1 contract", 1, bounty.killTarget)
        // A completed bounty is terminal, so it no longer surfaces as an available offer.
        assertTrue("the completed bounty is not re-offered", finalState.missions.available.none { it.id == bounty.id })

        // AC#3 (UC42-aware): credits rose by EXACTLY the bounty reward PLUS the salvage the kill drops — two
        // distinct, stacking income sources (started at 0, no other wallet movement this run). The salvage
        // credits are non-zero under the pinned loot table, so this genuinely asserts the stack, not just the
        // bounty; both terms are derived (no magic literal like 843).
        assertTrue("the UC42 salvage stack is exercised (the kill drops scrap credits)", salvageCredits > 0L)
        assertEquals("credits rose by exactly the bounty reward + salvage scrap", expectedCredits, finalState.credits)

        // UC43 seam: a bounty grants NO reputation today — the standing is still neutral.
        assertEquals("the bounty granted no reputation (UC43 seam)", Reputation.EMPTY, finalState.reputation)
    }

    @Test
    fun `the bounty replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(load(), capturePerTickStates = true)
        val second = ReplayRunner().run(load(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }
}
