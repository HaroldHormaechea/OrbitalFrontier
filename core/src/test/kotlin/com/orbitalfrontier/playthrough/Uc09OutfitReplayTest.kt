package com.orbitalfrontier.playthrough

import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.sim.SimulationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC09 outfitting playthrough (AC#8), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc09-outfit` artifact starts docked at Alpha Station and, one docked order per tick,
 * buys + installs an engine upgrade, buys a second ship, and switches the active ship. This test pins
 * the two AC#8 claims directly on the replayed state:
 *  - **buying the engine raises max speed**: immediately after the BuyInstall (while the upgraded
 *    starter is still active) the active ship's effective max speed is strictly greater than its
 *    pre-purchase baseline; and
 *  - **switching changes the active ship**: the final [com.orbitalfrontier.ship.Fleet.activeShipId]
 *    differs from the initial one (0 → the bought ship's id).
 *
 * Plus the determinism contract: replaying the artifact twice yields bit-identical end states. The
 * artifact is reproduced from [PlaythroughFixtures.uc09Outfit] and guarded by [PlaythroughFixtureTest].
 */
class Uc09OutfitReplayTest {
    private fun loadOutfit(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC09_OUTFIT)

    /** The active ship's effective forward max speed under the playthrough's pinned movement params. */
    private fun activeMaxSpeed(
        state: SimulationState,
        playthrough: Playthrough,
    ): Float {
        val active = state.fleet.active
        return ShipStats.effectiveMovementParams(playthrough.config.toParams(), active.type, active.loadout).maxSpeed
    }

    @Test
    fun `buying an engine upgrade raises the active ship's max speed`() {
        val playthrough = loadOutfit()
        val states = ReplayRunner().run(playthrough, capturePerTickStates = true).perTickStates

        val initial = states.first()
        val baseline = activeMaxSpeed(initial, playthrough)

        // The state right after the engine BuyInstall (tick 0) — the upgraded starter is still active.
        val afterEngineBuy = states[1]
        assertEquals(
            "the engine ship is still active right after the buy",
            OwnedShip.STARTER_SHIP_ID,
            afterEngineBuy.fleet.activeShipId,
        )
        assertEquals(
            "the engine occupies one engine slot on the active ship",
            1,
            afterEngineBuy.fleet.active.loadout.installedCount(SlotCategory.ENGINES),
        )
        assertTrue(
            "installing the engine must raise max speed: baseline=$baseline after=${activeMaxSpeed(afterEngineBuy, playthrough)}",
            activeMaxSpeed(afterEngineBuy, playthrough) > baseline,
        )
    }

    @Test
    fun `switching the active ship changes the active ship`() {
        val playthrough = loadOutfit()
        val result = ReplayRunner().run(playthrough)
        val initial = playthrough.initialState!!.toSimulationState()
        val finalState = result.finalState

        // A second ship was bought…
        assertEquals("the fleet grew to two ships", 2, finalState.fleet.ships.size)
        // …and the active ship changed from the starter (id 0) to the bought Swift (id 1).
        assertNotEquals(
            "the active ship must have changed after the switch",
            initial.fleet.activeShipId,
            finalState.fleet.activeShipId,
        )
        assertEquals("the Swift (id 1) is now active", ShipId(1), finalState.fleet.activeShipId)
        assertEquals("the active ship is the Swift type", ShipRoster.SWIFT.id, finalState.fleet.active.type.id)

        // The engine the player installed persists on the (now-inactive) starter — each ship keeps its
        // own loadout across a switch (AC#5).
        val starter = finalState.fleet.ship(OwnedShip.STARTER_SHIP_ID)!!
        assertEquals(
            "the starter keeps its installed engine after the switch",
            1,
            starter.loadout.installedCount(SlotCategory.ENGINES),
        )
    }

    @Test
    fun `replay through outfitting and fleet changes is deterministic`() {
        val first = ReplayRunner().run(loadOutfit()).finalState
        val second = ReplayRunner().run(loadOutfit()).finalState

        // SimulationState data-class equality covers the whole fleet (ships, loadouts, active id).
        assertEquals(first, second)
    }
}
