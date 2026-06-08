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
}
