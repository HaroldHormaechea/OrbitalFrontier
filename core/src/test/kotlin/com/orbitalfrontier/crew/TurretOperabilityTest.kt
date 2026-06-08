package com.orbitalfrontier.crew

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TurretOperability.turretsOperable] (UC11 AC#3/#5) — the pure derived crew-gating a
 * future combat model (UC13) consumes.
 *
 * Pins the contract: a turret needing no crew (requiredCrew <= 0) is always operable; otherwise it is
 * operable iff `crew >= requiredCrew`; and — the load-bearing MVP case — at the authored requirement
 * of 1 the **first** hire flips a turret from inoperable to operable on the starter ship.
 */
class TurretOperabilityTest {
    @Test
    fun `a turret requiring no crew is always operable`() {
        for (crew in listOf(0, 1, 5)) {
            assertTrue("requiredCrew 0 ⇒ operable at crew=$crew", TurretOperability.turretsOperable(crew, requiredCrew = 0))
            assertTrue("a negative requirement also means no crew needed", TurretOperability.turretsOperable(crew, requiredCrew = -3))
        }
    }

    @Test
    fun `crew at or above the requirement is operable`() {
        assertTrue("crew == requirement is operable", TurretOperability.turretsOperable(crew = 2, requiredCrew = 2))
        assertTrue("crew above requirement is operable", TurretOperability.turretsOperable(crew = 5, requiredCrew = 2))
    }

    @Test
    fun `crew below the requirement is inoperable`() {
        assertFalse("crew below requirement is inoperable", TurretOperability.turretsOperable(crew = 1, requiredCrew = 2))
        assertFalse("zero crew is inoperable when the turret needs some", TurretOperability.turretsOperable(crew = 0, requiredCrew = 2))
    }

    @Test
    fun `at the MVP requirement the first hire flips a turret from inoperable to operable`() {
        // The default requirement is MVP_TURRET_CREW_REQUIREMENT (1): an uncrewed ship's turret is
        // inoperable, and hiring the first crew makes it operable.
        assertFalse("an uncrewed ship's turret is inoperable", TurretOperability.turretsOperable(crew = 0))
        assertTrue("the first hire flips it operable", TurretOperability.turretsOperable(crew = 1))
    }
}
