package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.ProjectileOwner
import com.orbitalfrontier.power.Brownout
import com.orbitalfrontier.power.PowerSystem
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC49 power-brownout playthrough (AC#5), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc49-power-brownout` artifact flies the player into the alpha-raider-picket to spawn a
 * RAIDER while holding FIRE + SCAN, under a deliberately over-drawn power budget that sheds **both**
 * SCANNER and WEAPONS for the whole run (base+thrust stays under reactor output, the budget-only
 * weapons/scanner draws push it over). This is the **visible brownout effect** proof obligation:
 *  - **SCANNER shed ⇒ scan reveals nothing**: the in-range `alpha-derelict` is never revealed.
 *  - **WEAPONS shed ⇒ fire emits no projectile**: no PLAYER projectile is ever produced, so the raider is
 *    never damaged and the encounter stays active (the ship is never bricked — the protected HELM stays
 *    powered, so it still flies).
 *  - **`isBrownout == true`** under the artifact's pinned over-draw budget.
 *
 * Causation is proven against a **full-power control** — the SAME script replayed under the default
 * [com.orbitalfrontier.power.PowerParams] — where the scan DOES reveal the derelict and the built-in
 * forward gun DOES emit a PLAYER projectile. That rules out a vacuous pass (e.g. "no projectile because
 * the encounter never spawned" or "nothing in scan range").
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc49PowerBrownout] and guarded by
 * [PlaythroughFixtureTest].
 */
class Uc49PowerBrownoutReplayTest {
    private val derelict = PoiId("alpha-derelict")

    private fun loadBrownout(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC49_POWER_BROWNOUT)

    /** The full-power control: the identical recorded script, but replayed under the DEFAULT power budget. */
    private fun fullPowerControl(): Playthrough = loadBrownout().copy(powerConfig = PowerParamsDto.DEFAULT)

    private fun playerProjectileEverFired(perTick: List<SimulationState>): Boolean =
        perTick.any { state -> state.combat.projectiles.any { it.owner == ProjectileOwner.PLAYER } }

    // --- the pinned budget resolves to a real brownout that sheds both sheddable systems --------------

    @Test
    fun `the pinned over-draw budget sheds the scanner and weapons while keeping HELM powered`() {
        val params = loadBrownout().powerConfig.toPowerParams()
        for (thrusting in listOf(false, true)) {
            val result = Brownout.resolve(thrusting, params)
            assertTrue("the over-draw budget is a brownout (thrusting=$thrusting)", result.isBrownout)
            assertFalse("SCANNER is shed", result.isPowered(PowerSystem.SCANNER))
            assertFalse("WEAPONS is shed", result.isPowered(PowerSystem.WEAPONS))
            assertTrue("HELM stays powered (no-deadlock)", result.isPowered(PowerSystem.HELM))
        }
    }

    // --- AC#5: SCANNER shed ⇒ the held scan reveals nothing (vs. the control, which reveals it) --------

    @Test
    fun `a shed scanner reveals nothing while the full-power control reveals the in-range derelict`() {
        val brownout = ReplayRunner().run(loadBrownout()).finalState
        val control = ReplayRunner().run(fullPowerControl()).finalState

        // Control: the scanner is powered, so the held SCAN reveals the in-range derelict — proving it WAS
        // in range and the script DOES scan (no vacuous pass).
        assertTrue("the full-power control reveals the in-range derelict", derelict in control.revealedContacts)

        // Brownout: the scanner is shed, so the SAME held SCAN reveals nothing.
        assertFalse("a shed scanner must not reveal the derelict", derelict in brownout.revealedContacts)
        assertTrue("a shed scanner reveals nothing at all", brownout.revealedContacts.isEmpty())
    }

    // --- AC#5: WEAPONS shed ⇒ the held fire emits no projectile (vs. the control, which fires) ---------

    @Test
    fun `a shed weapon fires no projectile and never harms the raider while the control fires`() {
        val brownout = ReplayRunner().run(loadBrownout(), capturePerTickStates = true)
        val control = ReplayRunner().run(fullPowerControl(), capturePerTickStates = true)

        // A real fight happened under both budgets (the encounter spawned) — so a "no projectile" result is
        // about the shed weapon, not a missing encounter.
        assertTrue(
            "the player was ambushed by the picket raider (brownout run)",
            brownout.perTickStates.any { it.combat.active && it.combat.hostiles.isNotEmpty() },
        )

        // Control: the powered built-in forward gun fires — a PLAYER projectile appears at some tick.
        assertTrue("the full-power control fires a player projectile", playerProjectileEverFired(control.perTickStates))

        // Brownout: the shed weapon never fires — no PLAYER projectile is ever produced...
        assertFalse(
            "a shed weapon must never emit a player projectile",
            playerProjectileEverFired(brownout.perTickStates),
        )
        // ...so the raider is never damaged and the encounter is still active at the end (raider survives).
        val finalState = brownout.finalState
        assertTrue("the encounter is still active (the unharmed raider was not destroyed)", finalState.combat.active)
        assertTrue("the raider is still present", finalState.combat.hostiles.isNotEmpty())
    }

    // --- determinism (the standard replay contract) ---------------------------------------------------

    @Test
    fun `the brownout replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(loadBrownout(), capturePerTickStates = true)
        val second = ReplayRunner().run(loadBrownout(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }
}
