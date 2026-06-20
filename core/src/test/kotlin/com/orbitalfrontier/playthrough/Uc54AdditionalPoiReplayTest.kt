package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.LootTable
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.world.DistressEvent
import com.orbitalfrontier.world.DistressOutcome
import com.orbitalfrontier.world.DistressParams
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC54 additional-POI-types playthrough (AC#5), following the record→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc54-additional-poi` artifact flies the player through Beta's deep-south cluster — starting
 * inside the hazard, scavenging the derelict, then crossing into the distress beacon. This test replays it
 * headlessly and asserts the **concrete** outcome of each new POI type (Condition 3 — never just "an event
 * happened"):
 *  - **derelict (AC#2/#4):** the wreck is marked consumed and the hold gained exactly the loot the production
 *    [LootTable.DERELICT] path rolls for it — derived, no magic literal;
 *  - **hazard (AC#2):** the ship was inside the hazard radius at some tick (it was traversed) and fuel drained
 *    over the run;
 *  - **distress (AC#2/#4):** the signal is marked consumed and resolved to its authored branch (the production
 *    branch is **AMBUSH**, asserted as `combat.active` + a spawned hostile; the expected branch is derived
 *    from the real resolver so a future re-tuning surfaces here rather than drifting silently);
 *  - bit-for-bit determinism across two replays.
 *
 * The cluster is geometrically disjoint from every other committed fixture's Beta path, so this is the ONLY
 * fixture that touches these POIs — every existing replay stays byte-identical (the zero-fixture-regen lever).
 * Reproduced from [PlaythroughFixtures.uc54AdditionalPoi] and guarded by [PlaythroughFixtureTest].
 */
class Uc54AdditionalPoiReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC54_ADDITIONAL_POI)

    private val world = MvpSectorMap.build()
    private val beta = SectorId("beta")
    private val derelict = world.sector(beta).derelicts.single()
    private val distress = world.sector(beta).distressSignals.single()
    private val hazard = world.sector(beta).hazardZones.single()

    /** The loot the production scavenge path rolls for this wreck — derived, never a magic literal. */
    private val expectedLoot = LootTable.roll(LootTable.DERELICT, "derelict:${derelict.id.value}")

    /** The branch the production distress resolver assigns to this signal id (derived, not hard-coded). */
    private val expectedDistressOutcome: DistressOutcome =
        DistressEvent.resolve(
            world, beta,
            previousPosition = distress.position + Vec2(distress.triggerRadius * 4f, 0f),
            newPosition = distress.position,
            consumedPois = emptySet(),
            combat = CombatState.NONE,
            cargo = Cargo.empty(),
            credits = 0L,
            spawnTick = 0,
            combatParams = CombatParams(),
            params = DistressParams(),
        ).outcome!!

    @Test
    fun `the playthrough scavenges the derelict, traverses the hazard, and triggers the distress signal`() {
        val result = ReplayRunner().run(load(), capturePerTickStates = true)
        val finalState = result.finalState

        // --- Derelict (AC#2/#4): consumed + the exact rolled loot in the hold ---
        assertTrue(
            "the DERELICT loot profile must yield resources for the test to be meaningful",
            expectedLoot.resources.isNotEmpty(),
        )
        assertTrue("the derelict is scavenged (marked consumed)", derelict.id in finalState.consumedPois)
        val expectedHold = Salvage.fillCargo(Cargo.empty(), expectedLoot.resources).cargo
        assertEquals("the hold gained exactly the rolled derelict loot", expectedHold.contents, finalState.cargo.contents)
        assertTrue("the scavenge actually moved cargo", finalState.cargo.contents.isNotEmpty())

        // --- Hazard (AC#2): the ship was inside the hazard radius at some tick + fuel drained over the run ---
        val traversedHazard =
            result.perTickStates.any { (it.ship.position - hazard.position).length <= hazard.radius }
        assertTrue("the ship traversed the hazard zone at some tick", traversedHazard)
        val startFuel = result.perTickStates.first().fuel.level
        assertTrue("fuel drained over the hazard-traversing run", finalState.fuel.level < startFuel)

        // --- Distress (AC#2/#4): consumed + the concrete authored branch outcome ---
        assertTrue("the distress signal is triggered (marked consumed)", distress.id in finalState.consumedPois)
        when (expectedDistressOutcome) {
            DistressOutcome.AMBUSH -> {
                assertTrue("the AMBUSH branch leaves combat active", finalState.combat.active)
                assertTrue("the AMBUSH branch spawned a hostile", finalState.combat.hostiles.isNotEmpty())
                // No reward income on the ambush branch, and the derelict yields no credits ⇒ wallet stays 0.
                assertEquals("an ambush grants no credits", 0L, finalState.credits)
            }
            DistressOutcome.REWARD -> {
                // Derelict gives no credits, so the whole wallet is the distress reward (derived from params).
                assertEquals("the reward branch grants exactly the params reward", DistressParams().rewardCredits, finalState.credits)
            }
        }
    }

    @Test
    fun `the additional-POI replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(load(), capturePerTickStates = true)
        val second = ReplayRunner().run(load(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }
}
