package com.orbitalfrontier.economy

import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [StationMarketState] (UC46 AC#1/#3) — the pure, immutable, save-wide per-station
 * supply/demand pressure behind the dynamic-pricing engine.
 *
 * The type is engine-free and deterministic, so these are plain JVM unit tests (UC46 AC#4). They pin
 * the two contracts the simulation relies on:
 *  - [StationMarketState.withTrade] folds a **clamped fill** in with the right sign per [TradeKind],
 *    drops an entry that lands back on 0, and is a **same-instance no-op** on a 0-unit / null-kind fill
 *    (the byte-identity discipline, UC46 risk #1).
 *  - [StationMarketState.decayed] mean-reverts every pressure toward 0 with a `max(1, …)` floor that
 *    guarantees finite-time recovery, never overshoots, is a **same-instance no-op** at an empty map or
 *    off a decay tick, and collapses a fully-recovered state back to [EMPTY].
 */
class StationMarketStateTest {
    private val alpha = PoiId("alpha-station")
    private val beta = PoiId("beta-station")
    private val titanium = ResourceType.TITANIUM
    private val iron = ResourceType.IRON_ORE
    private val params = PricingParams()

    // --- withTrade: sign by TradeKind ---

    @Test
    fun `a SELL adds positive pressure (oversupply)`() {
        val after = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 6)

        assertEquals(6, after.pressureFor(alpha)[titanium])
    }

    @Test
    fun `a BUY adds negative pressure (scarcity)`() {
        val after = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.BUY, 4)

        assertEquals(-4, after.pressureFor(alpha)[titanium])
    }

    @Test
    fun `successive same-side fills accumulate`() {
        val after =
            StationMarketState.EMPTY
                .withTrade(alpha, titanium, TradeKind.SELL, 6)
                .withTrade(alpha, titanium, TradeKind.SELL, 6)

        assertEquals("two sells of 6 accumulate to +12 pressure", 12, after.pressureFor(alpha)[titanium])
    }

    @Test
    fun `pressure is tracked per station and per resource independently`() {
        val after =
            StationMarketState.EMPTY
                .withTrade(alpha, titanium, TradeKind.SELL, 6)
                .withTrade(alpha, iron, TradeKind.BUY, 3)
                .withTrade(beta, titanium, TradeKind.SELL, 2)

        assertEquals(6, after.pressureFor(alpha)[titanium])
        assertEquals(-3, after.pressureFor(alpha)[iron])
        assertEquals(2, after.pressureFor(beta)[titanium])
    }

    // --- withTrade: no-op (same-instance) discipline ---

    @Test
    fun `a 0-unit fill returns the same instance`() {
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 6)

        assertSame("a 0-unit fill is a same-instance no-op", seeded, seeded.withTrade(alpha, titanium, TradeKind.SELL, 0))
    }

    @Test
    fun `a negative-unit fill returns the same instance`() {
        assertSame(
            "a negative-unit fill is a same-instance no-op",
            StationMarketState.EMPTY,
            StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, -3),
        )
    }

    @Test
    fun `a null-kind fill returns the same instance`() {
        assertSame(
            "a null-kind fill (a no-op trade) is a same-instance no-op",
            StationMarketState.EMPTY,
            StationMarketState.EMPTY.withTrade(alpha, titanium, null, 6),
        )
    }

    // --- withTrade: canonical map (zero/empty entries pruned) ---

    @Test
    fun `a sell-then-buy round-trip collapses back to EMPTY`() {
        val afterSell = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 6)
        val roundTripped = afterSell.withTrade(alpha, titanium, TradeKind.BUY, 6)

        assertSame("a fully-cancelled pressure collapses to the EMPTY singleton", StationMarketState.EMPTY, roundTripped)
        assertTrue(roundTripped.pressureByStation.isEmpty())
    }

    @Test
    fun `an entry that lands back on 0 is dropped but other resources survive`() {
        val state =
            StationMarketState.EMPTY
                .withTrade(alpha, titanium, TradeKind.SELL, 6)
                .withTrade(alpha, iron, TradeKind.SELL, 5)
                .withTrade(alpha, titanium, TradeKind.BUY, 6)

        assertNull("the cancelled titanium entry is pruned", state.pressureFor(alpha)[titanium])
        assertEquals("the unrelated iron pressure survives", 5, state.pressureFor(alpha)[iron])
    }

    @Test
    fun `pressureFor an untraded station is empty`() {
        assertTrue(StationMarketState.EMPTY.pressureFor(alpha).isEmpty())
    }

    // --- decayed: no-op discipline ---

    @Test
    fun `decay on an empty map returns the same instance`() {
        assertSame("decay of EMPTY is a same-instance no-op", StationMarketState.EMPTY, StationMarketState.EMPTY.decayed(0, params))
    }

    @Test
    fun `decay off a decay-period tick returns the same instance`() {
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 50)

        // decayPeriodTicks defaults to 64; tick 1 is not a multiple, so decay is a strict no-op.
        assertSame("an off-period tick does not decay", seeded, seeded.decayed(1, params))
    }

    @Test
    fun `decay on a decay-period tick does move pressure`() {
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 50)

        // tick 0 is a decay tick (0 % 64 == 0); with a non-empty map decay actually steps.
        val decayed = seeded.decayed(0, params)
        assertTrue("a non-empty map on a decay tick moves toward 0", abs(decayed.pressureFor(alpha)[titanium]!!) < 50)
    }

    // --- decayed: never overshoots, max(1,…) floor, finite recovery ---

    @Test
    fun `decay never overshoots zero or flips sign`() {
        // A small positive pressure: step = max(1, 3 * 1 / 10) = max(1, 0) = 1, so it steps 3 -> 2, never past 0.
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 3)

        val decayed = seeded.decayed(0, params)
        assertEquals("a +3 pressure steps to +2 (floor step of 1), never crossing 0", 2, decayed.pressureFor(alpha)[titanium])
    }

    @Test
    fun `decay of a negative pressure steps toward 0 without flipping sign`() {
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.BUY, 3)

        val decayed = seeded.decayed(0, params)
        assertEquals("a -3 pressure steps to -2 (floor step of 1), never crossing 0", -2, decayed.pressureFor(alpha)[titanium])
    }

    @Test
    fun `the max(1) floor guarantees finite-time recovery to EMPTY`() {
        var state: StationMarketState = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 137)

        // Repeatedly apply decay on decay ticks; the max(1,…) floor guarantees it reaches EMPTY in
        // finite steps (a pure-fractional decay could stall at 1 forever — this proves it does not).
        var guard = 0
        while (state !== StationMarketState.EMPTY) {
            state = state.decayed(0, params)
            guard++
            assertTrue("decay must converge in finite steps (no infinite floor/ceiling pin)", guard < 1000)
        }

        assertSame("a fully-recovered state collapses back to EMPTY", StationMarketState.EMPTY, state)
    }

    @Test
    fun `a large pressure decays by the proportional step, not just the floor`() {
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 100)

        // step = max(1, 100 * 1 / 10) = 10, so 100 -> 90 on the first decay tick.
        val decayed = seeded.decayed(0, params)
        assertEquals("a large pressure decays by abs(p)*decayNum/decayDen", 90, decayed.pressureFor(alpha)[titanium])
    }

    @Test
    fun `decay drops resources that reach 0 and prunes emptied stations`() {
        // A +1 pressure: step = max(1, 0) = 1, so it steps to 0 and is dropped; the station empties out.
        val seeded = StationMarketState.EMPTY.withTrade(alpha, titanium, TradeKind.SELL, 1)

        val decayed = seeded.decayed(0, params)
        assertSame("a single emptied station collapses the whole state to EMPTY", StationMarketState.EMPTY, decayed)
    }
}
