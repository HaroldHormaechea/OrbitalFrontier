package com.orbitalfrontier.combat

import com.orbitalfrontier.ship.ShipMovementParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [CombatLimitedMovement] (UC13 AC#3) — engine damage scales the speed caps, the same
 * way [com.orbitalfrontier.ship.FuelLimitedMovement] scales for fuel.
 *
 * The load-bearing invariant is the **byte-identical** one: a pristine engine returns the base params
 * **unchanged (same instance)**, so a ship that has taken no engine damage moves exactly as before and
 * the pre-UC13 fixtures replay bit-for-bit. A damaged engine scales speed by
 * `minFactor + (1-minFactor)*hpFraction`; a wrecked engine still crawls at the floor.
 */
class CombatLimitedMovementTest {
    private val base = ShipMovementParams()
    private val params = CombatParams() // minEngineSpeedFactor 0.35
    private val maxEngineHp = 40

    @Test
    fun `a pristine engine returns the base params unchanged (same instance)`() {
        val result = CombatLimitedMovement.effectiveParams(base, SectionDamages.PRISTINE, maxEngineHp, params)
        assertSame("a pristine engine must not allocate or change params (byte-identical)", base, result)
    }

    @Test
    fun `engine damage scales the speed caps by the hp fraction`() {
        // Half HP -> factor = 0.35 + 0.65 * 0.5 = 0.675.
        val damage = mapOf(ShipSection.ENGINE to 20)
        val result = CombatLimitedMovement.effectiveParams(base, damage, maxEngineHp, params)
        val factor = 0.675f

        assertEquals("max speed scales by the engine factor", base.maxSpeed * factor, result.maxSpeed, 1e-3f)
        assertEquals("reverse speed scales by the engine factor", base.maxReverseSpeed * factor, result.maxReverseSpeed, 1e-3f)
        // Handling params are untouched — only the speed caps scale.
        assertEquals("acceleration is untouched", base.maxAcceleration, result.maxAcceleration, 0f)
        assertEquals("rotation is untouched", base.maxRotationSpeed, result.maxRotationSpeed, 0f)
    }

    @Test
    fun `a fully-wrecked engine still crawls at the floor factor (never stranded)`() {
        val wrecked = mapOf(ShipSection.ENGINE to 0)
        val result = CombatLimitedMovement.effectiveParams(base, wrecked, maxEngineHp, params)
        assertEquals("a dead engine crawls at minEngineSpeedFactor", base.maxSpeed * params.minEngineSpeedFactor, result.maxSpeed, 1e-3f)
    }

    @Test
    fun `a model with no engine HP is not scaled (returns base unchanged)`() {
        // maxEngineHp <= 0 -> the archetype has no modelled engine; never scale.
        val result = CombatLimitedMovement.effectiveParams(base, mapOf(ShipSection.ENGINE to 5), maxEngineHp = 0, params = params)
        assertSame(base, result)
    }

    @Test
    fun `more engine damage means a strictly lower speed cap (monotonic)`() {
        val light = CombatLimitedMovement.effectiveParams(base, mapOf(ShipSection.ENGINE to 30), maxEngineHp, params)
        val heavy = CombatLimitedMovement.effectiveParams(base, mapOf(ShipSection.ENGINE to 10), maxEngineHp, params)
        org.junit.Assert.assertTrue("heavier engine damage yields a lower speed cap", heavy.maxSpeed < light.maxSpeed)
    }
}
