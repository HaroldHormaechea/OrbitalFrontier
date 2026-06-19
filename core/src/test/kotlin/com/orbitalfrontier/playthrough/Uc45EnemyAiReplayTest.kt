package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC45 richer-enemy-AI playthrough (AC#5), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc45-enemy-ai` artifact flies the player into the authored multi-archetype
 * `gamma-marauder-pack` zone — spawning an [HostileArchetypes.INDEPENDENT_MARAUDER], a
 * [HostileArchetypes.REGROUP_MARAUDER] and a [HostileArchetypes.PRECISION_RAIDER] at once — then holds FIRE
 * while the crew-gated auto-aim turret grinds the pack down. This test replays it headlessly and asserts:
 *  - a genuine **multi-hostile** fight occurred: some tick held ≥2 hostiles simultaneously, and all three
 *    UC45 archetypes appeared across the encounter;
 *  - the **richer AI manifested in the recording**: the [HostileArchetypes.REGROUP_MARAUDER] was seen below
 *    its flee threshold (retreat-and-regroup triggered), and the player's recorded damage is **multi-section**
 *    (HULL *and* ENGINE), the ENGINE being the section the weakest-section-targeting
 *    [HostileArchetypes.PRECISION_RAIDER] funnels its direct hits into;
 *  - the encounter **cleared** (combat → NONE, no hostiles) and the player **survived** (hull intact);
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * The precise, no-RNG mechanics of weakest-section targeting and the retreat/re-engage decision are pinned by
 * the pure unit tests ([com.orbitalfrontier.combat.CombatTest], [com.orbitalfrontier.combat.WeakestSectionTest],
 * [com.orbitalfrontier.combat.EnemyAiTest]); this fixture demonstrates both behaviours end-to-end in a real
 * replay. The artifact is reproduced from [PlaythroughFixtures.uc45EnemyAi] and guarded by
 * [PlaythroughFixtureTest]; it is also loadable via the `playtest` skill (`-Dplaythrough.name=uc45-enemy-ai`).
 */
class Uc45EnemyAiReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC45_ENEMY_AI)

    private val regroup = HostileArchetypes.REGROUP_MARAUDER

    @Test
    fun `flying into the pack spawns a multi-archetype fight the turret then clears`() {
        val result = ReplayRunner().run(load(), capturePerTickStates = true)
        val finalState = result.finalState

        // A real MULTI-hostile fight occurred: some tick held two or more hostiles at once.
        val maxSimultaneous = result.perTickStates.maxOf { it.combat.hostiles.size }
        assertTrue("the player was ambushed by a multi-hostile pack (>= 2 at once)", maxSimultaneous >= 2)

        // All three UC45 archetypes appeared across the encounter (the authored composition).
        val archetypesSeen = result.perTickStates.flatMap { s -> s.combat.hostiles.map { it.archetypeId } }.toSet()
        assertTrue("the independent marauder spawned", HostileArchetypes.INDEPENDENT_MARAUDER.id in archetypesSeen)
        assertTrue("the retreat-and-regroup marauder spawned", regroup.id in archetypesSeen)
        assertTrue("the weakest-section precision raider spawned", HostileArchetypes.PRECISION_RAIDER.id in archetypesSeen)

        // Retreat-and-regroup MANIFESTED: at some tick the regroup marauder was below its flee threshold (the
        // condition under which it runs and regroups — see EnemyAi / EnemyAiTest).
        val regroupRetreated =
            result.perTickStates.any { s ->
                s.combat.hostiles.any { it.archetypeId == regroup.id && it.hullFraction(regroup) < regroup.fleeHullFraction }
            }
        assertTrue("a damaged regroup marauder crossed its flee threshold (retreat triggered)", regroupRetreated)

        // The encounter CLEARED (the regroup marauder never leashes off within its regroupRange, so a cleared
        // final state means the whole pack was destroyed) and the player SURVIVED.
        assertFalse("the encounter is cleared at the end", finalState.combat.active)
        assertTrue("no hostiles remain", finalState.combat.hostiles.isEmpty())

        val active = finalState.fleet.active
        val hullHp = SectionDamages.currentHp(active.sectionDamage, ShipSection.HULL, MAX_HULL_HP)
        assertTrue("the player survived (hull not destroyed)", hullHp > 0)

        // Weakest-section targeting EFFECT in the recording: the player's damage is multi-section (NOT the
        // HULL-only pin of the other combat fixtures, condition #3), and ENGINE — the section the precision
        // raider locks onto — took damage.
        assertTrue("the player took damage on more than one section (multi-section recording)", active.sectionDamage.size >= 2)
        assertTrue("the player's HULL took damage", ShipSection.HULL in active.sectionDamage.keys)
        assertTrue(
            "the player's ENGINE took damage (the precision raider's weakest-section target)",
            ShipSection.ENGINE in active.sectionDamage.keys,
        )
    }

    @Test
    fun `the richer-AI combat replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(load(), capturePerTickStates = true)
        val second = ReplayRunner().run(load(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }

    private companion object {
        /** The starter ship's authored base HULL max HP (no HULL_PLATING fitted in this fixture). */
        const val MAX_HULL_HP: Int = 100
    }
}
