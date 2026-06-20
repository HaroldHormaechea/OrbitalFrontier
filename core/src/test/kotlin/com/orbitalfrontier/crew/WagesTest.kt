package com.orbitalfrontier.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the crew wage / upkeep drain (UC50 AC#2) — the pure [Wages] resolver and its
 * [WageParams] cadence + [WageResult] outcome.
 *
 * Pins: the drain math (`creditsPerCrewPerPeriod × totalCrew`), the **clamp-at-0** unpaid rule (no debt,
 * no desertion — ADR 0038), the **rate-0 no-op** that keeps every pre-UC50 fixture byte-identical, and
 * the tick-keyed [WageParams.isWageTick] cadence the live + replay paths share (challenger #2).
 */
class WagesTest {
    @Test
    fun `a fully-affordable period drains the exact bill across multiple crew`() {
        val result = Wages.resolve(credits = 1000L, totalCrew = 3, params = WageParams(creditsPerCrewPerPeriod = 50L, periodTicks = 10))
        assertEquals("owed = 50 * 3 = 150 is paid in full", 150L, result.paid)
        assertEquals("no shortfall", 0L, result.unpaid)
        assertEquals("balance after the drain", 850L, result.credits)
        assertTrue("a real drain reports changed", result.changed)
    }

    @Test
    fun `an unaffordable period pays what it can, clamps at 0, and reports the shortfall (no debt)`() {
        val result = Wages.resolve(credits = 120L, totalCrew = 2, params = WageParams(creditsPerCrewPerPeriod = 100L, periodTicks = 10))
        assertEquals("owed = 200, only 120 available", 120L, result.paid)
        assertEquals("the 80 shortfall is reported unpaid", 80L, result.unpaid)
        assertEquals("the balance clamps at 0 — never negative (no debt)", 0L, result.credits)
        assertTrue("draining the wallet to 0 is a real change", result.changed)
    }

    @Test
    fun `a rate-0 params is a same-value no-op (the byte-identity lever)`() {
        val params = WageParams() // default rate 0
        val result = Wages.resolve(credits = 500L, totalCrew = 4, params = params)
        assertEquals("credits unchanged", 500L, result.credits)
        assertEquals(0L, result.paid)
        assertEquals(0L, result.unpaid)
        assertFalse("a rate-0 resolve never reports changed", result.changed)
    }

    @Test
    fun `zero crew owes nothing even at a non-zero rate`() {
        val result = Wages.resolve(credits = 500L, totalCrew = 0, params = WageParams(creditsPerCrewPerPeriod = 100L, periodTicks = 10))
        assertEquals(500L, result.credits)
        assertEquals(0L, result.paid)
        assertFalse(result.changed)
    }

    @Test
    fun `an already-empty wallet drains nothing but still reports the full shortfall`() {
        val result = Wages.resolve(credits = 0L, totalCrew = 2, params = WageParams(creditsPerCrewPerPeriod = 100L, periodTicks = 10))
        assertEquals(0L, result.credits)
        assertEquals(0L, result.paid)
        assertEquals("the whole bill is unpaid", 200L, result.unpaid)
        assertFalse("nothing actually moved, so changed is false", result.changed)
    }

    @Test
    fun `isWageTick fires once per period and never on tick 0`() {
        val params = WageParams(creditsPerCrewPerPeriod = 1L, periodTicks = 5)
        assertFalse("tick 0 never pays (a fresh game / first replay tick)", params.isWageTick(0))
        assertFalse(params.isWageTick(1))
        assertFalse(params.isWageTick(4))
        assertTrue("the first charge lands at periodTicks", params.isWageTick(5))
        assertFalse(params.isWageTick(6))
        assertTrue("and again one period later", params.isWageTick(10))
    }

    @Test
    fun `WageParams rejects a negative rate or a non-positive period`() {
        assertThrows(IllegalArgumentException::class.java) { WageParams(creditsPerCrewPerPeriod = -1L) }
        assertThrows(IllegalArgumentException::class.java) { WageParams(periodTicks = 0) }
        assertThrows(IllegalArgumentException::class.java) { WageParams(periodTicks = -5) }
    }

    @Test
    fun `a negative crew count is coerced to zero (defensive)`() {
        val result = Wages.resolve(credits = 500L, totalCrew = -3, params = WageParams(creditsPerCrewPerPeriod = 100L, periodTicks = 10))
        assertEquals("a negative crew owes nothing", 500L, result.credits)
        assertFalse(result.changed)
    }
}
