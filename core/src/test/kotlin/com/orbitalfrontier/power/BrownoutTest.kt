package com.orbitalfrontier.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (engine-free, JVM-only) coverage for the UC49 power-budget resolver [Brownout] and its
 * [BrownoutResult] (docs/adr/0037).
 *
 * The resolver is rate-based with a **protected floor**: HELM (the always-on base/hotel load plus
 * thrust/helm power) is never shed, so the ship can never be bricked; SCANNER then WEAPONS are
 * sheddable, shed lowest-[PowerSystem.shedPriority]-first when budget demand exceeds reactor output.
 * The sheddable [PowerParams.weaponsDraw] / [PowerParams.scannerDraw] are budget-only — they never
 * enter [PowerModel.drawAt] / fuel burn — so at their default 0 the resolver is a no-op (no brownout),
 * which is exactly what keeps every pre-UC49 fixture byte-identical.
 *
 * ACs / invariants covered:
 *  - **AC#1/#4** — over-budget demand sheds load (deterministically) instead of ignoring the overage.
 *  - **shed order** — SCANNER is shed before WEAPONS.
 *  - **no-deadlock pitfall (AC#4)** — HELM stays powered under every brownout, including the degenerate
 *    protected-over-output case (all sheddable shed, brownout flagged, ship still powered to fly).
 *  - **byte-identity ground** — at the default (0) sheddable draws nothing is shed and `isBrownout` is
 *    false, so the scan/combat seams are untouched.
 */
class BrownoutTest {
    // --- shed order is fixed: SCANNER (lower priority) is shed before WEAPONS --------------------------

    @Test
    fun `the scanner is a lower shed priority than weapons`() {
        assertTrue(
            "SCANNER must shed before WEAPONS (ascending shedPriority)",
            PowerSystem.SCANNER.shedPriority < PowerSystem.WEAPONS.shedPriority,
        )
        assertTrue("HELM is the protected floor", PowerSystem.HELM.isProtected)
        assertFalse("SCANNER is sheddable", PowerSystem.SCANNER.isProtected)
        assertFalse("WEAPONS is sheddable", PowerSystem.WEAPONS.isProtected)
    }

    // --- AC#4 byte-identity ground: default (0) sheddable draws -> no brownout, nothing shed -----------

    @Test
    fun `default power params never brown out whether thrusting or coasting`() {
        val params = PowerParams() // weaponsDraw = scannerDraw = 0
        for (thrusting in listOf(false, true)) {
            val result = Brownout.resolve(thrusting, params)
            assertFalse("default draws must not brown out (thrusting=$thrusting)", result.isBrownout)
            assertTrue("nothing is shed at the default draws", result.shedSystems.isEmpty())
            assertEquals(
                "every system stays powered at the default draws",
                PowerSystem.entries.toSet(),
                result.poweredSystems,
            )
            // Demand is exactly the protected base(+thrust) draw — the sheddable draws add nothing.
            assertEquals(PowerModel.drawAt(thrusting, params), result.totalDemand, 1e-4f)
            assertEquals(params.reactorOutput, result.reactorOutput, 1e-4f)
        }
    }

    // --- AC#1: an over-budget overage sheds the lowest-priority system first ---------------------------

    @Test
    fun `shedding the scanner alone clears the overage and keeps weapons powered`() {
        // base(0.0139) + scanner(1.5) + weapons(1.0) = 2.5139 > output 2.0; dropping SCANNER (1.5) leaves
        // 1.0139 <= 2.0, so the resolver stops after the scanner and WEAPONS stays up.
        val params = PowerParams(weaponsDraw = 1.0f, scannerDraw = 1.5f)
        val result = Brownout.resolve(thrusting = false, params = params)

        assertTrue("demand exceeds output -> brownout", result.isBrownout)
        assertEquals("only the scanner is shed", setOf(PowerSystem.SCANNER), result.shedSystems)
        assertEquals(
            "HELM + WEAPONS stay powered after shedding only the scanner",
            setOf(PowerSystem.HELM, PowerSystem.WEAPONS),
            result.poweredSystems,
        )
        assertFalse("a shed scanner is not powered", result.isPowered(PowerSystem.SCANNER))
        assertTrue("weapons remain powered", result.isPowered(PowerSystem.WEAPONS))
        assertEquals(params.baseModuleDraw + 1.5f + 1.0f, result.totalDemand, 1e-4f)
    }

    // --- AC#1: a larger overage sheds BOTH sheddable systems, in order --------------------------------

    @Test
    fun `a heavy overage sheds the scanner then the weapons`() {
        // base(0.0139) + scanner(3) + weapons(3) = 6.0139 > 2.0; after dropping SCANNER (3.0139) still
        // > 2.0, so WEAPONS is shed too. HELM is never touched.
        val params = PowerParams(weaponsDraw = 3.0f, scannerDraw = 3.0f)
        val result = Brownout.resolve(thrusting = false, params = params)

        assertTrue(result.isBrownout)
        assertEquals(
            "both sheddable systems are shed under the heavy overage",
            setOf(PowerSystem.SCANNER, PowerSystem.WEAPONS),
            result.shedSystems,
        )
        assertEquals("only the protected HELM stays powered", setOf(PowerSystem.HELM), result.poweredSystems)
        assertTrue("HELM is never shed", result.isPowered(PowerSystem.HELM))
    }

    // --- AC#4 no-deadlock: the protected HELM survives even a full brownout ----------------------------

    @Test
    fun `HELM stays powered under a full brownout`() {
        val params = PowerParams(weaponsDraw = 5.0f, scannerDraw = 5.0f)
        for (thrusting in listOf(false, true)) {
            val result = Brownout.resolve(thrusting, params)
            assertTrue("HELM must remain powered (no-deadlock) thrusting=$thrusting", result.isPowered(PowerSystem.HELM))
        }
    }

    // --- AC#4 degenerate case: protected draw alone exceeds output (documented, deliberate) ------------

    @Test
    fun `the degenerate protected-over-output case sheds all sheddable yet keeps HELM powered`() {
        // The protected base load (5.0) alone exceeds the reactor output (1.0): the budget can never be
        // met, but the resolver still sheds every sheddable system and keeps HELM up rather than bricking
        // the ship (ADR 0037).
        val params = PowerParams(reactorOutput = 1.0f, baseModuleDraw = 5.0f, thrustDraw = 0.0f, weaponsDraw = 1.0f, scannerDraw = 1.0f)
        val result = Brownout.resolve(thrusting = false, params = params)

        assertTrue("the protected load alone exceeds output -> brownout", result.isBrownout)
        assertEquals("every sheddable system is shed", setOf(PowerSystem.SCANNER, PowerSystem.WEAPONS), result.shedSystems)
        assertEquals("HELM stays powered even though the budget can't be met", setOf(PowerSystem.HELM), result.poweredSystems)
        assertEquals("demand is the protected draw plus both sheddable draws", 7.0f, result.totalDemand, 1e-4f)
    }

    // --- the FULL_POWER default snapshot ---------------------------------------------------------------

    @Test
    fun `FULL_POWER is a no-load all-powered snapshot`() {
        val full = BrownoutResult.FULL_POWER
        assertFalse("FULL_POWER is not a brownout", full.isBrownout)
        assertEquals("every system is powered", PowerSystem.entries.toSet(), full.poweredSystems)
        assertTrue("nothing is shed", full.shedSystems.isEmpty())
        assertEquals("no load", 0f, full.totalDemand, 1e-4f)
        assertEquals("the default reactor output", PowerParams.DEFAULT_REACTOR_OUTPUT, full.reactorOutput, 1e-4f)
        for (system in PowerSystem.entries) assertTrue("$system powered at FULL_POWER", full.isPowered(system))
    }

    // --- a thrust spike adds to demand but is still part of the protected (never-shed) floor -----------

    @Test
    fun `thrusting raises demand by the thrust draw and never sheds HELM`() {
        val params = PowerParams(weaponsDraw = 1.0f, scannerDraw = 1.0f)
        val coasting = Brownout.resolve(thrusting = false, params = params)
        val thrusting = Brownout.resolve(thrusting = true, params = params)
        assertEquals(
            "thrusting adds exactly the thrust draw to demand",
            params.thrustDraw,
            thrusting.totalDemand - coasting.totalDemand,
            1e-4f,
        )
        assertTrue("HELM (which carries thrust) is never shed", thrusting.isPowered(PowerSystem.HELM))
    }
}
