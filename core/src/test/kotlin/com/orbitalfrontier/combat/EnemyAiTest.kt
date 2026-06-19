package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

/**
 * Unit tests for [EnemyAi] (UC13 AC#4 — **no ML**) — the rule-based, data-driven enemy decision.
 *
 * Pins the two MVP behaviours: [AiBehavior.AGGRESSIVE] always closes and fires within the archetype's
 * engageRange; [AiBehavior.FLEE_WHEN_DAMAGED] closes while healthy but turns 180° away and holds fire
 * once its hull drops below the archetype's threshold. The thresholds/ranges come from archetype data,
 * so difficulty is data-driven, not branch-driven.
 */
class EnemyAiTest {
    private val raider = HostileArchetypes.RAIDER // AGGRESSIVE, engageRange 480
    private val scavenger = HostileArchetypes.SCAVENGER // FLEE_WHEN_DAMAGED, hull 18, fleeHullFraction 0.34

    // RETREAT_AND_REGROUP, hull 36, fleeHullFraction 0.34, regroupRange 700, engageRange 480, leashRange 1400.
    private val regroup = HostileArchetypes.REGROUP_MARAUDER

    /** Hull current-HP below the regroup marauder's flee threshold (0.34 * 36 ≈ 12.2), so it's "damaged". */
    private val regroupDamaged = mapOf(ShipSection.HULL to 10)

    private fun hostile(
        archetype: HostileArchetype,
        position: Vec2,
        damage: SectionDamage = SectionDamages.PRISTINE,
    ): Hostile =
        Hostile(
            id = HostileId(0),
            archetypeId = archetype.id,
            kinematics = ShipKinematics(position = position),
            sectionDamage = damage,
        )

    @Test
    fun `an aggressive hostile turns toward the player and thrusts`() {
        val h = hostile(raider, Vec2(0f, 0f))
        val player = Vec2(300f, 100f)
        val decision = EnemyAi.decide(h, raider, player)

        assertTrue("aggressive closes on the player", decision.thrust)
        assertEquals("heads straight at the player", atan2(100f, 300f), decision.desiredHeading, 1e-4f)
    }

    @Test
    fun `an aggressive hostile fires only within its engage range`() {
        val h = hostile(raider, Vec2(0f, 0f))

        // Inside engageRange (480) -> fires.
        assertTrue("fires inside engage range", EnemyAi.decide(h, raider, Vec2(400f, 0f)).wantsToFire)
        // Outside engageRange -> closes but holds fire.
        val far = EnemyAi.decide(h, raider, Vec2(600f, 0f))
        assertFalse("holds fire beyond engage range", far.wantsToFire)
        assertTrue("still thrusts to close the distance", far.thrust)
    }

    @Test
    fun `a healthy flee-when-damaged hostile behaves aggressively`() {
        // Pristine hull (fraction 1.0 >= 0.34) -> not fleeing -> closes + fires in range.
        val healthy = hostile(scavenger, Vec2(0f, 0f))
        val decision = EnemyAi.decide(healthy, scavenger, Vec2(200f, 0f))
        assertTrue("a healthy scavenger closes", decision.thrust)
        assertTrue("a healthy scavenger fires in range", decision.wantsToFire)
        assertEquals("heads at the player while healthy", 0f, decision.desiredHeading, 1e-4f)
    }

    @Test
    fun `a damaged flee-when-damaged hostile runs directly away and holds fire`() {
        // Hull 5 of 18 = 0.277 < 0.34 -> fleeing.
        val damaged = hostile(scavenger, Vec2(0f, 0f), damage = mapOf(ShipSection.HULL to 5))
        assertTrue("precondition: hull below the flee threshold", damaged.hullFraction(scavenger) < scavenger.fleeHullFraction)

        val player = Vec2(200f, 0f)
        val decision = EnemyAi.decide(damaged, scavenger, player)

        assertTrue("a fleeing scavenger still thrusts (to escape)", decision.thrust)
        assertFalse("a fleeing scavenger holds fire", decision.wantsToFire)
        // Player is at +x; fleeing heads -x (180° away). Compare the heading's direction vector (±π are the
        // same bearing) rather than the raw radians, which differ in sign for a -0 component.
        val dir = Vec2.fromAngle(decision.desiredHeading)
        assertEquals("flees in -x (directly away from the player)", -1f, dir.x, 1e-4f)
        assertEquals("no lateral drift on a head-on flee", 0f, dir.y, 1e-4f)
    }

    @Test
    fun `an aggressive archetype never flees even when nearly destroyed`() {
        // RAIDER fleeHullFraction is 0 -> a 1-HP raider is still aggressive (the data drives behaviour).
        val nearlyDead = hostile(raider, Vec2(0f, 0f), damage = mapOf(ShipSection.HULL to 1))
        val decision = EnemyAi.decide(nearlyDead, raider, Vec2(200f, 0f))
        assertTrue("an aggressive archetype keeps firing in range regardless of hull", decision.wantsToFire)
    }

    // --- RETREAT_AND_REGROUP (UC45 AC#2): engage while healthy; retreat inside regroupRange when damaged;
    //     re-engage from beyond regroupRange. The AGGRESSIVE / FLEE branches above stay byte-identical. ---

    @Test
    fun `a healthy retreat-and-regroup hostile engages like an aggressive one`() {
        // Pristine hull (fraction 1.0 >= 0.34) -> not retreating -> closes + fires in range.
        val healthy = hostile(regroup, Vec2(0f, 0f))
        val decision = EnemyAi.decide(healthy, regroup, Vec2(300f, 0f))
        assertTrue("a healthy regroup marauder closes", decision.thrust)
        assertTrue("a healthy regroup marauder fires inside engage range", decision.wantsToFire)
        assertEquals("heads at the player while healthy", 0f, decision.desiredHeading, 1e-4f)
    }

    @Test
    fun `a damaged retreat-and-regroup hostile inside regroup range runs away and holds fire`() {
        // Hull below the flee threshold AND distance (300) < regroupRange (700) -> retreat.
        val damaged = hostile(regroup, Vec2(0f, 0f), damage = regroupDamaged)
        assertTrue("precondition: hull below the flee threshold", damaged.hullFraction(regroup) < regroup.fleeHullFraction)

        val decision = EnemyAi.decide(damaged, regroup, Vec2(300f, 0f))
        assertTrue("a retreating marauder still thrusts (to open the distance)", decision.thrust)
        assertFalse("a retreating marauder holds fire", decision.wantsToFire)
        val dir = Vec2.fromAngle(decision.desiredHeading)
        assertEquals("retreats in -x (directly away from the player)", -1f, dir.x, 1e-4f)
        assertEquals("no lateral drift on a head-on retreat", 0f, dir.y, 1e-4f)
    }

    @Test
    fun `a damaged retreat-and-regroup hostile beyond regroup range turns back to re-engage`() {
        // Same damaged hull, but distance (900) > regroupRange (700): it has reached its standoff distance,
        // so it re-engages — turning back TOWARD the player and thrusting (the regroup, not a permanent flee).
        val damaged = hostile(regroup, Vec2(0f, 0f), damage = regroupDamaged)
        val decision = EnemyAi.decide(damaged, regroup, Vec2(900f, 0f))

        assertTrue("a re-engaging marauder thrusts back toward the player", decision.thrust)
        val dir = Vec2.fromAngle(decision.desiredHeading)
        assertEquals("turns back TOWARD the player (+x), not fleeing", 1f, dir.x, 1e-4f)
        assertEquals("no lateral drift heading straight back", 0f, dir.y, 1e-4f)
        // 900 wu is beyond engageRange (480), so it holds fire until it has closed back into range.
        assertFalse("holds fire until back inside engage range", decision.wantsToFire)
    }

    @Test
    fun `a healthy retreat-and-regroup hostile beyond regroup range still engages`() {
        // Pristine hull: never retreats, so even far out it closes (and fires only once inside engage range).
        val healthy = hostile(regroup, Vec2(0f, 0f))
        val decision = EnemyAi.decide(healthy, regroup, Vec2(900f, 0f))
        assertTrue("a healthy marauder closes the distance", decision.thrust)
        assertEquals("heads toward the player", 0f, decision.desiredHeading, 1e-4f)
        assertFalse("holds fire beyond engage range", decision.wantsToFire)
    }
}
