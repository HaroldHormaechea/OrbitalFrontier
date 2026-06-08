package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AC#2 contract — auto-aim turrets fire only when the ship has the crew to operate
 * them (via the shared [com.orbitalfrontier.crew.TurretOperability]); below the requirement they are
 * inoperable and never fire. The starter ship carries a built-in turret needing 1 crew, so at the
 * new-game crew of 0 it is INOPERABLE (the AC#2 demo), and the first hire flips it operable.
 */
class TurretFiringTest {
    private val params = CombatParams()
    private val starterType = ShipRoster.STARTER

    private fun playerInput(crew: Int): PlayerCombatInput =
        PlayerCombatInput(
            kinematics = ShipKinematics(position = Vec2(0f, 0f)),
            weapons = ShipStats.weaponLoadout(starterType, Loadout.EMPTY),
            maxSectionHp = ShipStats.sectionHpMap(starterType, Loadout.EMPTY),
            crew = crew,
            sectionDamage = SectionDamages.PRISTINE,
        )

    /** An active fight with one RAIDER inside the player's turret range (560 wu). */
    private fun fightWithHostileInRange(): CombatState =
        CombatState(active = true, zoneId = "t", rngState = CombatRng.seeded("encounter:t:0"))
            .spawnHostile(HostileArchetypes.RAIDER.id, ShipKinematics(position = Vec2(120f, 0f)))

    @Test
    fun `the starter turret is inoperable at crew 0`() {
        val turret = ShipStats.weaponLoadout(starterType, Loadout.EMPTY).turrets.single()
        assertFalse("requiredCrew 1 turret is inoperable with 0 crew", turret.operableWith(0))
        assertTrue("it becomes operable with 1 crew", turret.operableWith(1))
        assertTrue(
            "a ship with 0 crew has no operable turrets",
            ShipStats.weaponLoadout(starterType, Loadout.EMPTY).operableTurrets(0).isEmpty(),
        )
    }

    @Test
    fun `an uncrewed ship's turret does not auto-fire`() {
        // fireAction NONE so ONLY a turret could fire — and it must not, with no crew.
        val result = Combat.step(fightWithHostileInRange(), playerInput(crew = 0), FireAction.NONE, params, dt = 1f / 30f)
        assertFalse("no turret shot with 0 crew", result.events.any { it is CombatEvent.PlayerFired && it.turret })
        // No player projectile was emitted (the hostile is too far to have fired this first tick).
        assertTrue("no player projectile exists", result.combat.projectiles.none { it.owner == ProjectileOwner.PLAYER })
    }

    @Test
    fun `a crewed ship's turret auto-fires at the hostile without a fire action`() {
        val result = Combat.step(fightWithHostileInRange(), playerInput(crew = 1), FireAction.NONE, params, dt = 1f / 30f)
        assertTrue("the operable turret auto-fires", result.events.any { it is CombatEvent.PlayerFired && it.turret })
        assertTrue("a player projectile was launched by the turret", result.combat.projectiles.any { it.owner == ProjectileOwner.PLAYER })
    }

    @Test
    fun `a disabled TURRET section stops the turret firing even with crew`() {
        // Destroy the TURRET section (0 HP) -> turretDisabled -> no auto-fire despite enough crew.
        val turretMax = ShipStats.sectionHp(starterType, Loadout.EMPTY, ShipSection.TURRET)
        val input =
            playerInput(crew = 1).copy(sectionDamage = SectionDamages.setHp(SectionDamages.PRISTINE, ShipSection.TURRET, 0, turretMax))
        val result = Combat.step(fightWithHostileInRange(), input, FireAction.NONE, params, dt = 1f / 30f)
        assertFalse("a destroyed turret mount cannot fire", result.events.any { it is CombatEvent.PlayerFired && it.turret })
    }
}
