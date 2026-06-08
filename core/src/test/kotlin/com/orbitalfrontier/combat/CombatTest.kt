package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Combat.step] (UC13 AC#1/#3/#7/#8) — the shared real-time combat tick.
 *
 * Two contracts:
 *  - the **anchor**: an inactive encounter ([CombatState.NONE]) is a total no-op — same instances, no
 *    RNG advance, the empty event list — even with a [FireAction.FIRE] intent, so pre-UC13 fixtures
 *    replay byte-identically;
 *  - the **integration**: a player fixed-fire shot flies, strikes the hostile, deals RNG-located section
 *    damage, and destroys it (clearing the encounter), while a hostile shot records sectional damage on
 *    the player (AC#8: "the hostile is gone and any sectional damage taken is recorded").
 */
class CombatTest {
    private val starterType = ShipRoster.STARTER
    private val dt = 1f / 30f

    /** A combat-params set that funnels every hit to HULL, so destruction is deterministic in a unit test. */
    private val hullOnlyParams = CombatParams(sectionHitWeights = mapOf(ShipSection.HULL to 1))

    private fun player(
        position: Vec2 = Vec2(0f, 0f),
        heading: Float = 0f,
        weapons: WeaponLoadout = ShipStats.weaponLoadout(starterType, Loadout.EMPTY),
        sectionDamage: SectionDamage = SectionDamages.PRISTINE,
    ): PlayerCombatInput =
        PlayerCombatInput(
            kinematics = ShipKinematics(position = position, headingRadians = heading),
            weapons = weapons,
            maxSectionHp = ShipStats.sectionHpMap(starterType, Loadout.EMPTY),
            crew = 0,
            sectionDamage = sectionDamage,
        )

    // --- Anchor: inactive combat is a total no-op (the byte-identical contract) ---

    @Test
    fun `a NONE step with no fire intent is a total no-op returning the same instances`() {
        val input = player()
        val result = Combat.step(CombatState.NONE, input, FireAction.NONE, CombatParams(), dt)

        assertSame("the SAME combat instance is returned", CombatState.NONE, result.combat)
        assertSame("the SAME section-damage instance is returned", input.sectionDamage, result.sectionDamage)
        assertFalse("not destroyed", result.destroyed)
        assertTrue("no events", result.events.isEmpty())
    }

    @Test
    fun `an inactive step ignores a FIRE intent (still a no-op)`() {
        val input = player()
        val result = Combat.step(CombatState.NONE, input, FireAction.FIRE, CombatParams(), dt)
        assertSame("FIRE on no encounter does nothing", CombatState.NONE, result.combat)
        assertTrue(result.events.isEmpty())
    }

    // --- Integration: fire -> projectile -> hit -> section damage -> destroy ---

    /** An active fight with one RAIDER just ahead of the player (so a forward shot reaches it in one tick). */
    private fun fightWithRaiderAhead(): CombatState =
        CombatState(active = true, zoneId = "t", rngState = CombatRng.seeded("encounter:t:0"))
            .spawnHostile(HostileArchetypes.RAIDER.id, ShipKinematics(position = Vec2(40f, 0f)))

    @Test
    fun `firing destroys an adjacent hostile and clears the encounter`() {
        // A strong, fast fixed weapon one-shots the RAIDER's 30-HP hull (all damage funnelled to HULL).
        val bigGun =
            WeaponLoadout(fixed = listOf(FixedWeapon(damage = 100, cooldownSeconds = 0.5f, projectileSpeed = 1000f, range = 1000f)))
        val result = Combat.step(fightWithRaiderAhead(), player(weapons = bigGun), FireAction.FIRE, hullOnlyParams, dt)

        assertTrue("the player fixed weapon fired", result.events.any { it is CombatEvent.PlayerFired && !it.turret })
        assertTrue("a player shot struck the hostile", result.events.any { it is CombatEvent.HostileHit })
        assertTrue("the hostile was destroyed", result.events.any { it is CombatEvent.HostileDestroyed })
        assertTrue("the encounter cleared once the last hostile died", result.events.any { it is CombatEvent.EncounterCleared })
        assertSame("a cleared encounter returns to NONE", CombatState.NONE, result.combat)
        assertFalse("the player was not destroyed", result.destroyed)
    }

    @Test
    fun `a hostile hit records sectional damage on the player (AC#8 - damage taken is recorded)`() {
        // The RAIDER is within its engage range (480) and adjacent, so it fires and the shot reaches the
        // player this tick. The player holds fire so the hostile survives to land its hit.
        val result = Combat.step(fightWithRaiderAhead(), player(), FireAction.NONE, hullOnlyParams, dt)

        assertTrue("the hostile fired", result.events.any { it is CombatEvent.HostileFired })
        assertTrue("the player was hit", result.events.any { it is CombatEvent.PlayerHit })
        // Damage is recorded on the returned section-damage map (HULL took the funnelled hit).
        assertEquals(
            "the player's HULL took the 4-damage RAIDER hit",
            96,
            SectionDamages.currentHp(result.sectionDamage, ShipSection.HULL, 100),
        )
    }

    @Test
    fun `replaying the same active step twice is deterministic`() {
        val combat = fightWithRaiderAhead()
        val a = Combat.step(combat, player(), FireAction.FIRE, CombatParams(), dt)
        val b = Combat.step(combat, player(), FireAction.FIRE, CombatParams(), dt)
        assertEquals("same inputs -> same combat state", a.combat, b.combat)
        assertEquals("same inputs -> same player damage", a.sectionDamage, b.sectionDamage)
        assertEquals("same inputs -> same events", a.events, b.events)
    }

    @Test
    fun `a disabled WEAPON section stops fixed fire`() {
        // Destroy the WEAPON mount (0 HP) -> fixed fire is suppressed even with a FIRE intent.
        val weaponMax = ShipStats.sectionHp(starterType, Loadout.EMPTY, ShipSection.WEAPON)
        val input = player(sectionDamage = SectionDamages.setHp(SectionDamages.PRISTINE, ShipSection.WEAPON, 0, weaponMax))
        val result = Combat.step(fightWithRaiderAhead(), input, FireAction.FIRE, hullOnlyParams, dt)
        assertFalse("a destroyed weapon mount cannot fire", result.events.any { it is CombatEvent.PlayerFired && !it.turret })
    }
}
