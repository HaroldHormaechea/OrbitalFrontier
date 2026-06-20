package com.orbitalfrontier.playthrough

import com.orbitalfrontier.crew.WageParams
import com.orbitalfrontier.crew.Wages
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC50 crew-wage playthrough (AC#2), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc50-wages` artifact starts the ship **docked at Alpha Station** with the starter crewed
 * to [PlaythroughFixtures.UC50_CREW] and a [PlaythroughFixtures.UC50_STARTING_CREDITS] wallet, pinned to a
 * non-zero [PlaythroughFixtures.UC50_WAGE_PARAMS]. The drain keys on the integer tick, so over the 5-tick
 * run it fires at tick 2 and tick 4 — the **same** tick-keyed cadence and clamp-at-0 rule the device's
 * PlayScreen mirrors (challenger #2). This test replays it headlessly and pins:
 *  - the wage drains the **exact** bill (`rate × totalCrew`) at each wage period, against a [Wages] control;
 *  - the non-wage ticks (1, 3) leave credits untouched (the cadence is real, not a per-tick leak);
 *  - the crew **count** — what the drain reads — is unchanged across the run; and
 *  - the replay is **bit-for-bit deterministic** (credits are part of replay equality), so the recorded
 *    drain reproduces precisely (record→replay credit parity).
 *
 * Amounts are derived from the pinned [WageParams] + crew count rather than magic numbers. The artifact is
 * reproduced from [PlaythroughFixtures.uc50Wages] and guarded by [PlaythroughFixtureTest].
 */
class Uc50WagesReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC50_WAGES)

    /** The wage tuning the fixture was recorded under (pinned in its config). */
    private fun params(): WageParams = load().wageConfig.toWageParams()

    @Test
    fun `the fixture genuinely starts docked at Alpha Station with a crewed ship, a known wallet, and a non-zero wage rate`() {
        val initial = requireNotNull(load().initialState) { "the wage fixture must carry an initial snapshot" }

        assertEquals("precondition: starts docked at Alpha Station", "alpha-station", initial.dockedStation)
        assertEquals("precondition: starts with the authored wallet", PlaythroughFixtures.UC50_STARTING_CREDITS, initial.credits)

        val state = initial.toSimulationState()
        assertEquals("precondition: the ship is crewed to the wage base", PlaythroughFixtures.UC50_CREW, state.fleet.totalCrew)
        assertTrue("precondition: the pinned wage rate is non-zero (the drain actually fires)", params().creditsPerCrewPerPeriod > 0L)
    }

    @Test
    fun `the wage drains the exact bill at each wage period and leaves non-wage ticks untouched`() {
        val states = ReplayRunner().run(load(), capturePerTickStates = true).perTickStates

        val params = params()
        val crew = PlaythroughFixtures.UC50_CREW
        // The control: one period's drain against a full wallet, computed by the production resolver.
        val owedPerPeriod = Wages.resolve(PlaythroughFixtures.UC50_STARTING_CREDITS, crew, params).paid
        assertTrue("the control bill is a real, non-zero drain", owedPerPeriod > 0L)
        assertEquals("sanity: owed = rate * crew", params.creditsPerCrewPerPeriod * crew, owedPerPeriod)

        // index 0 = initial; index k = state AFTER processing tick (k-1). The 2-tick cadence (never tick 0)
        // fires while processing state.tick 2 and 4 — i.e. at result indices 3 and 5.
        val start = PlaythroughFixtures.UC50_STARTING_CREDITS
        assertEquals("initial wallet", start, states[0].credits)
        assertEquals("tick 0 is not a wage tick", start, states[1].credits)
        assertEquals("tick 1 is not a wage tick", start, states[2].credits)
        assertEquals("first wage drain at tick 2", start - owedPerPeriod, states[3].credits)
        assertEquals("tick 3 leaves credits untouched (cadence, not a per-tick leak)", start - owedPerPeriod, states[4].credits)
        assertEquals("second wage drain at tick 4", start - 2 * owedPerPeriod, states[5].credits)

        // The crew COUNT — the only thing the drain reads — is unchanged across the run (identities, if any,
        // never enter the simulation snapshot).
        assertTrue("the crew count is constant across the wage run", states.all { it.fleet.totalCrew == crew })
    }

    @Test
    fun `the final wallet equals the start minus two full wage periods`() {
        val finalState = ReplayRunner().run(load()).finalState
        val owedPerPeriod = params().creditsPerCrewPerPeriod * PlaythroughFixtures.UC50_CREW
        assertEquals(
            "two wage periods drain off the starting wallet",
            PlaythroughFixtures.UC50_STARTING_CREDITS - 2 * owedPerPeriod,
            finalState.credits,
        )
    }

    @Test
    fun `replay through the wage drain is deterministic — record to replay credit parity`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState
        // SimulationState data-class equality covers credits (part of replay equality), so identical replays
        // prove the recorded drain reproduces bit-for-bit — the record→replay parity the plan requires.
        assertEquals("two replays are bit-for-bit identical", first, second)
    }

    @Test
    fun `the last docked station is unaffected by the wage drain`() {
        // A sanity guard that the wage path threads only credits — the rest of the docked state is stable.
        val finalState = ReplayRunner().run(load()).finalState
        assertEquals("still docked at Alpha Station after the run", PoiId("alpha-station"), finalState.dockedStation)
    }
}
