package com.orbitalfrontier.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [WeakestSection] (UC45 AC#2) — the pure "which section is the player's weakest" pick a
 * weakest-section-targeting hostile stamps onto its fire at fire time.
 *
 * Contract pinned here:
 *  - **weakest = lowest current-HP *fraction*** among the sections the ship actually has (positive max HP);
 *  - ties broken by **ascending [ShipSection.name]** (the stable string, never ordinal/`hashCode`), matching
 *    [DamageModel]'s ordering discipline;
 *  - a ship with **no positive-max-HP section** returns `null` (the caller then falls back to the RNG path).
 */
class WeakestSectionTest {
    /** A representative full-ship max-HP map (mirrors the starter ship's authored section HP). */
    private val fullMax: Map<ShipSection, Int> =
        mapOf(
            ShipSection.HULL to 100,
            ShipSection.ENGINE to 40,
            ShipSection.TURRET to 30,
            ShipSection.WEAPON to 30,
        )

    @Test
    fun `picks the section with the lowest current-HP fraction`() {
        // TURRET at 5/30 = 0.167 is the lowest fraction; HULL 90/100 = 0.9, the rest pristine (1.0).
        val damage = mapOf(ShipSection.HULL to 90, ShipSection.TURRET to 5)
        assertEquals(ShipSection.TURRET, WeakestSection.of(damage, fullMax))
    }

    @Test
    fun `compares by fraction, not by absolute current HP`() {
        // ENGINE 8/40 = 0.20 vs HULL 30/100 = 0.30: HULL has FEWER absolute HP but a HIGHER fraction, so the
        // lower-fraction ENGINE is the weakest. This is the load-bearing "fraction not absolute" assertion.
        val damage = mapOf(ShipSection.ENGINE to 8, ShipSection.HULL to 30)
        assertEquals(ShipSection.ENGINE, WeakestSection.of(damage, fullMax))
    }

    @Test
    fun `a pristine ship resolves the all-equal tie by ascending section name`() {
        // Every section pristine ⇒ all fractions 1.0 ⇒ tie ⇒ ascending name: ENGINE < HULL < TURRET < WEAPON.
        assertEquals(ShipSection.ENGINE, WeakestSection.of(SectionDamages.PRISTINE, fullMax))
    }

    @Test
    fun `an equal-fraction tie is broken by ascending section name`() {
        // HULL and ENGINE both at fraction 0.5 (50/100 and 20/40); the others pristine. ENGINE wins the tie.
        val damage = mapOf(ShipSection.HULL to 50, ShipSection.ENGINE to 20)
        assertEquals(ShipSection.ENGINE, WeakestSection.of(damage, fullMax))
    }

    @Test
    fun `sections with no max HP are never chosen`() {
        // Only HULL exists on this ship (others absent ⇒ max 0). Even pristine, HULL is the only candidate.
        val onlyHull = mapOf(ShipSection.HULL to 100)
        assertEquals(ShipSection.HULL, WeakestSection.of(SectionDamages.PRISTINE, onlyHull))
        // A heavily damaged HULL is still the only candidate (the zero-max sections are skipped).
        assertEquals(ShipSection.HULL, WeakestSection.of(mapOf(ShipSection.HULL to 1), onlyHull))
    }

    @Test
    fun `a ship with no damageable section returns null`() {
        assertNull("no positive-max section ⇒ no weakest", WeakestSection.of(SectionDamages.PRISTINE, emptyMap()))
        assertNull(
            "all-zero max HP ⇒ no candidate",
            WeakestSection.of(SectionDamages.PRISTINE, mapOf(ShipSection.HULL to 0, ShipSection.ENGINE to 0)),
        )
    }

    @Test
    fun `a section destroyed to zero HP is the weakest (fraction 0)`() {
        val damage = mapOf(ShipSection.ENGINE to 0)
        assertEquals(ShipSection.ENGINE, WeakestSection.of(damage, fullMax))
    }
}
