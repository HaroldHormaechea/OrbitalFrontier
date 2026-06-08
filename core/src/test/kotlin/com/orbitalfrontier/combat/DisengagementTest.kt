package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AC#6 disengage path — the player can break off a fight by outrunning the hostiles
 * past their leash, and is **not re-ambushed** while staying outside the zone.
 *
 * Two model behaviours combine to deliver AC#6:
 *  - [Combat.step] breaks off (and clears) any hostile that drifts beyond its archetype leash range —
 *    the "outrun" escape;
 *  - [EncounterSpawner] is edge-triggered, so once the fight has cleared, sitting outside (no
 *    outside→inside crossing) never re-spawns it — no instant re-ambush. Only re-entering can ambush again.
 *
 * (Disengaging by **dock/gate** is enforced one layer up, in the sim/PlayScreen: combat is not stepped
 * while docked, and a jump moves the player to another sector's gate away from the zone. The pure model
 * piece those rely on — clearing once no hostiles remain — is exercised here.)
 */
class DisengagementTest {
    private val params = CombatParams()
    private val starterType = ShipRoster.STARTER

    private val zone =
        EncounterZone(
            id = "alpha-raider-picket",
            sectorId = "alpha",
            center = Vec2(900f, 0f),
            radius = 260f,
            archetypeId = HostileArchetypes.RAIDER.id,
            hostileCount = 1,
        )

    private fun playerInput(position: Vec2): PlayerCombatInput =
        PlayerCombatInput(
            kinematics = ShipKinematics(position = position),
            weapons = ShipStats.weaponLoadout(starterType, Loadout.EMPTY),
            maxSectionHp = ShipStats.sectionHpMap(starterType, Loadout.EMPTY),
            crew = 0,
            sectionDamage = SectionDamages.PRISTINE,
        )

    /** An active fight with one RAIDER at [hostilePosition]. */
    private fun fightWithHostileAt(hostilePosition: Vec2): CombatState =
        CombatState(active = true, zoneId = zone.id, rngState = CombatRng.seeded("encounter:${zone.id}:0"))
            .spawnHostile(HostileArchetypes.RAIDER.id, ShipKinematics(position = hostilePosition))

    @Test
    fun `outrunning a hostile beyond its leash breaks it off and clears the encounter`() {
        // Player at the origin; the RAIDER has drifted out to 2000 wu — beyond its 1400 leash.
        val combat = fightWithHostileAt(Vec2(2000f, 0f))
        val result = Combat.step(combat, playerInput(Vec2(0f, 0f)), FireAction.NONE, params, dt = 1f / 30f)

        assertTrue("the leashed hostile breaks off", result.events.any { it is CombatEvent.HostileBrokeOff })
        assertTrue("with no hostiles left, the encounter clears", result.events.any { it is CombatEvent.EncounterCleared })
        assertSame("a cleared encounter returns to NONE", CombatState.NONE, result.combat)
    }

    @Test
    fun `after clearing, staying outside the zone does not re-ambush`() {
        // The fight has cleared (NONE). The player loiters OUTSIDE the zone (no crossing) for several ticks.
        var combat = CombatState.NONE
        val outsideA = Vec2(500f, 0f)
        val outsideB = Vec2(450f, 0f)
        repeat(10) {
            val spawned = EncounterSpawner.naturalSpawn(combat, zone, outsideA, outsideB, spawnTick = it, params = params)
            assertSame("loitering outside never re-spawns the fight", combat, spawned)
            combat = spawned
        }
        assertFalse("still no encounter after loitering outside", combat.active)
    }

    @Test
    fun `after clearing, lingering inside the zone does not instantly re-spawn`() {
        // Sitting INSIDE after a cleared fight (inside->inside, no fresh crossing) must not re-ambush.
        val inside = Vec2(900f, 0f)
        val stillInside = Vec2(880f, 0f)
        assertSame(CombatState.NONE, EncounterSpawner.naturalSpawn(CombatState.NONE, zone, inside, stillInside, 0, params))
    }

    @Test
    fun `re-entering the zone CAN ambush again (the edge-trigger is not a permanent disable)`() {
        // Contrast: a fresh outside->inside crossing after clearing does spawn again.
        val spawned = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, Vec2(500f, 0f), Vec2(900f, 0f), spawnTick = 7, params = params)
        assertTrue("re-entry ambushes again", spawned.active)
    }
}
