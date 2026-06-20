package com.orbitalfrontier.crew

import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.StatDelta
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the UC50 / challenger #1 invariant: **crew capacity is immovable by MVP outfitting**.
 *
 * No upgrade in [UpgradeCatalog.MVP] is a crew-quarters part — every part contributes a zero crew delta —
 * so a ship's crew capacity is a constant of its *type* alone, never moved by installing or removing a
 * loadout. This is what makes [com.orbitalfrontier.ship.OwnedShip.withLoadout]'s crew re-clamp a **dead
 * path** today: a refit can never shrink crew capacity below the seated crew, so it can never strand a
 * [CrewRoster] row (which would otherwise break the `forShip(s).size == s.crew` invariant). If a future
 * use case adds a crew-quarters upgrade, this guard fails loudly — a signal to wire the refit re-clamp
 * through [CrewAssignment] (and reconcile the roster) rather than leaving it a silent dead path.
 */
class Uc50CrewCapacityImmovableTest {
    @Test
    fun `no MVP upgrade contributes a crew-capacity delta`() {
        for (upgrade in UpgradeCatalog.MVP.all) {
            assertEquals(
                "MVP upgrade '${upgrade.id.value}' must not move crew capacity (no crew-quarters part in the MVP)",
                0,
                upgrade.statDeltas.crew,
            )
        }
    }

    @Test
    fun `even the sum of every MVP upgrade delta moves crew capacity by zero`() {
        val total = UpgradeCatalog.MVP.all.fold(StatDelta.NONE) { acc, u -> acc + u.statDeltas }
        assertEquals("installing every MVP part at once still adds 0 crew capacity", 0, total.crew)
    }

    @Test
    fun `crew capacity is a constant of the ship type — independent of any MVP loadout`() {
        // For every ship type, the empty-loadout crew capacity equals its base, and no MVP upgrade can raise
        // it — so the per-type capacity is fixed and a refit never re-clamps seated crew below the roster.
        for (type in ShipRoster.all) {
            val base = ShipStats.crewCapacity(type, com.orbitalfrontier.outfit.Loadout.EMPTY, UpgradeCatalog.MVP)
            assertEquals("an empty loadout yields the type's base crew capacity", type.baseCrewCapacity, base)
            assertTrue("the base crew capacity is positive (a starter can seat crew)", base >= 1)
        }
    }

    @Test
    fun `a fully-crewed ship keeps every seat across an outfit install (no re-clamp strands a roster row)`() {
        // Crew a starter to its capacity, then install a (non-crew) cargo pod via the single mutation point:
        // because no MVP part touches crew capacity, the crew count survives the refit unchanged — the
        // re-clamp in withLoadout is a proven no-op, so a paired CrewRoster row is never orphaned.
        val full = ShipStats.crewCapacity(ShipRoster.STARTER, com.orbitalfrontier.outfit.Loadout.EMPTY, UpgradeCatalog.MVP)
        val ship = OwnedShip.starter().withCrew(full)
        assertEquals("precondition: the starter is crewed to capacity", full, ship.crew)

        val installed =
            com.orbitalfrontier.outfit.Loadout.EMPTY.install(
                com.orbitalfrontier.outfit.SlotCategory.CARGO,
                ShipRoster.STARTER.slotCount(com.orbitalfrontier.outfit.SlotCategory.CARGO),
                UpgradeCatalog.CARGO_POD_I,
            )
        val refitted = ship.withLoadout((installed as com.orbitalfrontier.outfit.InstallResult.Installed).loadout)
        assertEquals("the crew count survives a (non-crew) refit unchanged — no seat is re-clamped away", full, refitted.crew)
    }
}
