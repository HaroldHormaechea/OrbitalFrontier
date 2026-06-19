package com.orbitalfrontier.render

import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.Hostile
import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.combat.HostileId
import com.orbitalfrontier.combat.SectionDamage
import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.combat.TargetingPriority
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CombatHudState.build] (UC44 AC#1/#2/#5) — the pure derivation of the combat-HUD
 * overlay from the live combat model. The two load-bearing contracts:
 *
 *  - **Lock parity with the firing point (PIN #1).** [CombatHudState.lockedTargetId] MUST equal the
 *    turret's own auto-aim pick — [TargetingPriority.selectTarget] at the SAME player kinematic position
 *    [com.orbitalfrontier.combat.Combat.step] feeds the turrets — and be suppressed whenever no turret
 *    would actually fire (no operable turret, or the TURRET section disabled), or combat is inactive.
 *  - **Honest, uncluttered bars (AC#2 + the multiple-hostiles pitfall).** Each bar's fill is the
 *    hostile's real [Hostile.hullFraction]; the set is capped to the [CombatHudState.MAX_BARS] nearest;
 *    and a bar's distance fade falls off (to 0 beyond [CombatHudState.FAR_RANGE]) so distant hostiles
 *    dim instead of crowding the screen.
 *
 * All inputs are pure value data (no libGDX types), so the derivation is fully JVM-unit-testable
 * (ADR 0001) against the same combat model the simulation runs.
 */
class CombatHudStateTest {
    private val raider = HostileArchetypes.RAIDER

    private fun hostile(
        id: Long,
        position: Vec2,
        sectionDamage: SectionDamage = SectionDamages.PRISTINE,
    ): Hostile =
        Hostile(
            id = HostileId(id),
            archetypeId = raider.id,
            kinematics = ShipKinematics(position = position),
            sectionDamage = sectionDamage,
        )

    private fun combatWith(vararg hostiles: Hostile): CombatState = CombatState(active = true, hostiles = hostiles.toList())

    // --- AC#1 / PIN #1: the lock equals the real turret target at the same firing point -----------------

    @Test
    fun `locked target equals TargetingPriority selection at the same player position (PIN 1 parity)`() {
        // The SAME playerPos the production path derives from physics.readKinematics() — the firing point.
        val playerPos = Vec2(40f, -15f)
        val near = hostile(7, Vec2(140f, -15f)) // closest → the turret's pick
        val far = hostile(2, Vec2(40f, 600f))
        val combat = combatWith(near, far)

        val state = CombatHudState.build(combat, playerPos, hasOperableTurrets = true, turretDisabled = false)

        // Must be byte-for-byte the same selection the turrets fire through (not a separate heuristic).
        assertEquals(
            TargetingPriority.selectTarget(playerPos, combat.hostiles)?.id,
            state.lockedTargetId,
        )
        assertEquals("the nearest hostile is the locked target", near.id, state.lockedTargetId)
    }

    // --- AC#1: the reticle is suppressed whenever no turret would actually fire -------------------------

    @Test
    fun `no lock when there is no operable turret`() {
        val playerPos = Vec2.ZERO
        val combat = combatWith(hostile(1, Vec2(100f, 0f)))

        val state = CombatHudState.build(combat, playerPos, hasOperableTurrets = false, turretDisabled = false)

        assertNull("an un-crewed / absent turret fires nothing, so the reticle is honest and absent", state.lockedTargetId)
        // Bars still derive — only the lock is gated on the turret state.
        assertEquals(1, state.bars.size)
    }

    @Test
    fun `no lock when the turret section is disabled`() {
        val playerPos = Vec2.ZERO
        val combat = combatWith(hostile(1, Vec2(100f, 0f)))

        val state = CombatHudState.build(combat, playerPos, hasOperableTurrets = true, turretDisabled = true)

        assertNull("a destroyed TURRET section disables fire, so the reticle is suppressed", state.lockedTargetId)
    }

    @Test
    fun `inactive combat yields the empty overlay`() {
        val combat = CombatState(active = false, hostiles = listOf(hostile(1, Vec2(100f, 0f))))

        val state = CombatHudState.build(combat, Vec2.ZERO, hasOperableTurrets = true, turretDisabled = false)

        assertEquals(CombatHudState.EMPTY, state)
        assertNull(state.lockedTargetId)
        assertTrue(state.bars.isEmpty())
    }

    @Test
    fun `an active encounter with no hostiles yields the empty overlay`() {
        val combat = CombatState(active = true, hostiles = emptyList())

        val state = CombatHudState.build(combat, Vec2.ZERO, hasOperableTurrets = true, turretDisabled = false)

        assertEquals(CombatHudState.EMPTY, state)
    }

    // --- AC#2: each bar reflects the hostile's real hull integrity --------------------------------------

    @Test
    fun `each bar's hull fraction equals the hostile's model hull fraction`() {
        // RAIDER hull max = 30; set current HULL HP to 15 → a damaged hostile at fraction 0.5.
        val damaged =
            hostile(
                id = 1,
                position = Vec2(120f, 0f),
                sectionDamage = mapOf(ShipSection.HULL to 15),
            )
        val pristine = hostile(id = 2, position = Vec2(150f, 0f))
        val combat = combatWith(damaged, pristine)

        val state = CombatHudState.build(combat, Vec2.ZERO, hasOperableTurrets = true, turretDisabled = false)

        val byId = state.bars.associateBy { it.id }
        assertEquals(
            "bar fill must be the model hull fraction, not an invented value",
            damaged.hullFraction(raider),
            byId.getValue(damaged.id).hullFraction,
            0f,
        )
        assertEquals(0.5f, byId.getValue(damaged.id).hullFraction, 1e-6f)
        assertEquals(1f, byId.getValue(pristine.id).hullFraction, 1e-6f)
    }

    // --- AC#2 pitfall: cap the bars to the MAX_BARS nearest so a crowd does not clutter ----------------

    @Test
    fun `bars are capped to the MAX_BARS nearest hostiles`() {
        val playerPos = Vec2.ZERO
        // Eight hostiles strung out along +x at distances 100..800; id == distance / 100.
        val hostiles = (1..8).map { i -> hostile(i.toLong(), Vec2(i * 100f, 0f)) }
        val combat = combatWith(*hostiles.toTypedArray())

        val state = CombatHudState.build(combat, playerPos, hasOperableTurrets = true, turretDisabled = false)

        assertEquals("only MAX_BARS bars are drawn", CombatHudState.MAX_BARS, state.bars.size)
        // Kept set = the MAX_BARS nearest, in nearest-first order (the TargetingPriority total order).
        val expectedNearest = (1L..CombatHudState.MAX_BARS.toLong()).toList()
        assertEquals(expectedNearest, state.bars.map { it.id.value })
    }

    // --- AC#2 pitfall: distant hostiles fade out (and vanish beyond FAR_RANGE) --------------------------

    @Test
    fun `fade decreases with distance and reaches zero beyond FAR_RANGE`() {
        val playerPos = Vec2.ZERO
        val near = hostile(1, Vec2(100f, 0f)) // well inside NEAR_RANGE (600) → full
        val mid = hostile(2, Vec2(1000f, 0f)) // between NEAR and FAR (1600) → partial
        val beyond = hostile(3, Vec2(2000f, 0f)) // past FAR_RANGE → faded out
        val combat = combatWith(near, mid, beyond)

        val state = CombatHudState.build(combat, playerPos, hasOperableTurrets = true, turretDisabled = false)
        val fade = state.bars.associateBy({ it.id }, { it.fade })

        assertEquals("a hostile inside NEAR_RANGE is fully opaque", 1f, fade.getValue(near.id), 1e-6f)
        assertEquals("a hostile past FAR_RANGE has faded fully out", 0f, fade.getValue(beyond.id), 1e-6f)
        assertTrue("fade is strictly monotone-decreasing with distance", fade.getValue(near.id) > fade.getValue(mid.id))
        assertTrue(fade.getValue(mid.id) > fade.getValue(beyond.id))
        assertTrue("the mid-range fade is a genuine partial value", fade.getValue(mid.id) in 0f..1f)
    }
}
