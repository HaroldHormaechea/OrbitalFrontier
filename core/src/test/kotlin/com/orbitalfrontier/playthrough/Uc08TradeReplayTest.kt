package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC08 trade playthrough (AC#1/#7), following the record→persist→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc08-trade` artifact starts the ship **docked at Alpha Station** carrying Titanium and
 * a non-zero wallet, and sells all the Titanium to the station at tick 0. This test replays it
 * headlessly and asserts the central UC08 claim (AC#7): selling docked cargo **increases credits** by
 * exactly `unitsSold * sellPrice` and **reduces the cargo** to empty — and that the wallet then stays
 * put across the trailing held-while-docked ticks. The expected sell price is read from the production
 * [MvpSectorMap] so the test tracks the authored economy rather than hard-coding a magic number.
 *
 * It also pins the determinism contract: replaying the artifact twice yields bit-identical end states.
 * The artifact is reproduced from [PlaythroughFixtures.uc08Trade] and guarded by [PlaythroughFixtureTest].
 */
class Uc08TradeReplayTest {
    private val alphaStation = PoiId("alpha-station")

    /** The authored Titanium sell price at Alpha Station, read from the production map (not hard-coded). */
    private val titaniumSellPrice: Long =
        MvpSectorMap.build()
            .sector(MvpSectorMap.START_SECTOR)
            .station(alphaStation)!!
            .market
            .offerFor(ResourceType.TITANIUM)!!
            .sellPrice

    private fun loadTrade(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC08_TRADE)

    @Test
    fun `selling docked cargo increases credits and empties the hold`() {
        val playthrough = loadTrade()

        // Precondition: the fixture genuinely starts docked, with Titanium aboard and a known balance.
        val initial = requireNotNull(playthrough.initialState) { "the trade fixture must carry an initial snapshot" }
        assertEquals("precondition: starts docked at Alpha Station", alphaStation.value, initial.dockedStation)
        assertEquals(
            "precondition: starts with the authored Titanium units",
            PlaythroughFixtures.UC08_TITANIUM_UNITS,
            initial.cargo[ResourceType.TITANIUM.name],
        )
        assertEquals("precondition: starts with the authored wallet", PlaythroughFixtures.UC08_STARTING_CREDITS, initial.credits)

        val finalState = ReplayRunner().run(playthrough).finalState

        val unitsSold = PlaythroughFixtures.UC08_TITANIUM_UNITS
        val expectedGain = unitsSold * titaniumSellPrice

        // AC#7: credits rose by exactly unitsSold * sellPrice.
        assertEquals(
            "credits must rise by unitsSold * sellPrice",
            PlaythroughFixtures.UC08_STARTING_CREDITS + expectedGain,
            finalState.credits,
        )
        // Sanity: the gain is real (the sale actually moved credits).
        assertTrue("the sale should have increased credits", finalState.credits > PlaythroughFixtures.UC08_STARTING_CREDITS)

        // AC#7: the sold cargo is gone.
        assertNull("the Titanium is fully sold", finalState.cargo.contents[ResourceType.TITANIUM])
        assertEquals("the hold is empty after the sale", 0, finalState.cargo.usedUnits)

        // The ship never undocked and never left the start sector (it was docked the whole run).
        assertEquals(alphaStation, finalState.dockedStation)
        assertEquals(MvpSectorMap.START_SECTOR, finalState.currentSector)
    }

    @Test
    fun `replay through a trade is deterministic`() {
        val first = ReplayRunner().run(loadTrade()).finalState
        val second = ReplayRunner().run(loadTrade()).finalState

        // SimulationState data-class equality covers credits + cargo as well as kinematics.
        assertEquals(first, second)
    }
}
