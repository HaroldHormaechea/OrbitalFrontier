package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.PricingParams
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC46 dynamic-pricing playthrough (AC#1/#4/#5), following the
 * record→persist→replay→assert pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc46-dynamic-pricing` artifact starts the ship **docked at Alpha Station** holding
 * Titanium and sells it in equal lots on consecutive ticks. As supply/demand pressure accumulates the
 * per-unit price drops between lots — the living economy this use case introduces. This test replays it
 * headlessly (capturing the per-tick snapshots) and asserts the central UC46 claim: the **second lot's
 * unit price is strictly below the first's** (price MOVED, vs. UC08's fixed-price economy), every lot
 * stays within the authored `[minMul, maxMul]` swing band, the leftover supply/demand pressure is
 * non-zero (so save/reload has something to round-trip, AC#3), and the replay is bit-for-bit
 * deterministic (AC#4). Base prices are read from the production [MvpSectorMap] so the test tracks the
 * authored economy rather than hard-coding magic numbers.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc46DynamicPricing] and guarded by
 * [PlaythroughFixtureTest].
 */
class Uc46DynamicPricingReplayTest {
    private val alphaStation = PoiId("alpha-station")
    private val params = PricingParams()

    /** The authored Titanium **base** sell price at Alpha Station, read from the production map. */
    private val titaniumBaseSell: Long =
        MvpSectorMap.build()
            .sector(MvpSectorMap.START_SECTOR)
            .station(alphaStation)!!
            .market
            .offerFor(ResourceType.TITANIUM)!!
            .sellPrice

    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC46_DYNAMIC_PRICING)

    /**
     * The per-lot unit sell price observed in a replay: lot `i` is sold on tick `i`, so the credit gain
     * between the pre-tick and post-tick snapshots, divided by the lot size, is its unit price.
     */
    private fun lotUnitPrices(): List<Long> {
        val perTick = ReplayRunner().run(load(), capturePerTickStates = true).perTickStates
        return (0 until PlaythroughFixtures.UC46_LOTS).map { lot ->
            val gain = perTick[lot + 1].credits - perTick[lot].credits
            assertEquals(
                "lot $lot must sell exactly the whole lot (credit gain divisible by the lot size)",
                0L,
                gain % PlaythroughFixtures.UC46_LOT_UNITS,
            )
            gain / PlaythroughFixtures.UC46_LOT_UNITS
        }
    }

    @Test
    fun `the fixture genuinely starts docked with sellable Titanium and a known wallet`() {
        val initial = requireNotNull(load().initialState) { "the dynamic-pricing fixture must carry an initial snapshot" }

        assertEquals("precondition: starts docked at Alpha Station", alphaStation.value, initial.dockedStation)
        assertEquals(
            "precondition: starts with enough Titanium for every lot",
            PlaythroughFixtures.UC46_LOTS * PlaythroughFixtures.UC46_LOT_UNITS,
            initial.cargo[ResourceType.TITANIUM.name],
        )
        assertEquals("precondition: starts with the authored wallet", PlaythroughFixtures.UC46_STARTING_CREDITS, initial.credits)
    }

    @Test
    fun `the first lot sells at the authored base price`() {
        // At tick 0 / pressure 0 / epoch 0 / neutral standing every multiplier is exactly 1.0, so the
        // first lot must transact at the authored base sell price — the byte-identity anchor (UC46 risk #1).
        assertEquals("the first lot prices at the authored base", titaniumBaseSell, lotUnitPrices().first())
    }

    @Test
    fun `the second lot's unit price is strictly below the first's (price moved)`() {
        val prices = lotUnitPrices()

        assertNotEquals("the price MOVED between lots (not a fixed-price economy)", prices[0], prices[1])
        assertTrue("oversupply from the first sale drops the second lot's unit price", prices[1] < prices[0])
    }

    @Test
    fun `every lot's unit price stays within the authored swing band`() {
        val floor = (titaniumBaseSell * params.minMul).toLong()
        val ceiling = (titaniumBaseSell * params.maxMul).toLong()

        for ((lot, price) in lotUnitPrices().withIndex()) {
            assertTrue("lot $lot price $price is at or above the minMul floor ($floor)", price >= floor)
            assertTrue("lot $lot price $price is at or below the maxMul ceiling ($ceiling)", price <= ceiling)
        }
    }

    @Test
    fun `the prices decline monotonically as pressure accumulates`() {
        val prices = lotUnitPrices()

        for (i in 1 until prices.size) {
            assertTrue("lot $i ($prices) prices at or below the previous lot as oversupply grows", prices[i] <= prices[i - 1])
        }
        assertTrue("across the run the price genuinely fell", prices.last() < prices.first())
    }

    @Test
    fun `selling leaves a non-zero supply-demand pressure to persist`() {
        val finalState = ReplayRunner().run(load()).finalState

        // Net positive (oversupply) pressure for the sold resource at the docked station — the dynamic
        // state UC46 AC#3 round-trips through the save.
        val pressure = finalState.marketState.pressureFor(alphaStation)[ResourceType.TITANIUM]
        assertEquals(
            "the accumulated pressure equals the total units sold (all SELLs ⇒ oversupply)",
            PlaythroughFixtures.UC46_LOTS * PlaythroughFixtures.UC46_LOT_UNITS,
            pressure,
        )
    }

    @Test
    fun `replay through dynamic pricing is deterministic`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState

        // SimulationState data-class equality covers credits + cargo + the marketState pressure map.
        assertEquals("two replays are bit-for-bit identical", first, second)
    }
}
