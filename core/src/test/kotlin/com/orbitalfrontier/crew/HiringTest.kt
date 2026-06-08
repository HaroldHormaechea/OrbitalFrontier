package com.orbitalfrontier.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Hiring.resolve] (UC11 AC#2/#5) — the pure, deterministic crew-hire resolver.
 *
 * Pins the contract the sim and the device both fold back: a hire is bounded simultaneously by the
 * request, the remaining crew berths (clamp-to-remaining), and the wallet; every non-hire (idle
 * order, non-hiring station, already full, can't afford one, non-positive request) is a no-op that
 * returns the inputs unchanged with `hired = 0` / `changed = false`; and a non-positive price is a
 * fail-fast programmer error (div-by-zero guard). Crew logic is pure and JVM-testable (AC#5).
 */
class HiringTest {
    private val price = Hiring.HIRE_COST_PER_CREW // 100

    @Test
    fun `hiring exactly to capacity fills the remaining berths and deducts the cost`() {
        // Capacity 2, currently 0, plenty of credits: a request of 2 hires both.
        val result = Hiring.resolve(credits = 1000L, currentCrew = 0, crewCapacity = 2, offersCrew = true, order = HireOrder.Hire(2))

        assertTrue("a real hire reports changed", result.changed)
        assertEquals("both berths hired", 2, result.hired)
        assertEquals("crew rises to capacity", 2, result.crew)
        assertEquals("credits drop by hired * price", 1000L - 2 * price, result.credits)
    }

    @Test
    fun `hiring beyond capacity clamps to the remaining berths (excess rejected)`() {
        // Capacity 2, already 1 aboard: only 1 berth remains, so a request of 5 hires just 1.
        val result = Hiring.resolve(credits = 1000L, currentCrew = 1, crewCapacity = 2, offersCrew = true, order = HireOrder.Hire(5))

        assertTrue(result.changed)
        assertEquals("only the one remaining berth is filled", 1, result.hired)
        assertEquals("crew tops out at capacity", 2, result.crew)
        assertEquals("only the hired crew is charged", 1000L - 1 * price, result.credits)
    }

    @Test
    fun `at full capacity a hire is a no-op`() {
        val result = Hiring.resolve(credits = 1000L, currentCrew = 2, crewCapacity = 2, offersCrew = true, order = HireOrder.Hire(3))

        assertFalse("nothing changed", result.changed)
        assertEquals(0, result.hired)
        assertEquals("crew unchanged", 2, result.crew)
        assertEquals("credits unchanged", 1000L, result.credits)
    }

    @Test
    fun `hiring beyond the wallet clamps to what the credits can afford`() {
        // Capacity 10 (lots of berths), but only 250 credits at 100/crew ⇒ affords 2.
        val result = Hiring.resolve(credits = 250L, currentCrew = 0, crewCapacity = 10, offersCrew = true, order = HireOrder.Hire(9))

        assertTrue(result.changed)
        assertEquals("only what the wallet affords is hired", 2, result.hired)
        assertEquals(2, result.crew)
        assertEquals("the remaining 50 credits stay (not enough for a third)", 50L, result.credits)
    }

    @Test
    fun `a wallet that can't afford even one crew is a no-op`() {
        val result = Hiring.resolve(credits = 99L, currentCrew = 0, crewCapacity = 2, offersCrew = true, order = HireOrder.Hire(1))

        assertFalse(result.changed)
        assertEquals(0, result.hired)
        assertEquals(0, result.crew)
        assertEquals("credits untouched", 99L, result.credits)
    }

    @Test
    fun `a station that does not hire crew is a no-op`() {
        val result = Hiring.resolve(credits = 1000L, currentCrew = 0, crewCapacity = 2, offersCrew = false, order = HireOrder.Hire(1))

        assertFalse("no hire at a non-crew station", result.changed)
        assertEquals(0, result.hired)
        assertEquals(0, result.crew)
        assertEquals(1000L, result.credits)
    }

    @Test
    fun `the idle order is a no-op even at a crew-hiring station`() {
        val result = Hiring.resolve(credits = 1000L, currentCrew = 0, crewCapacity = 2, offersCrew = true, order = HireOrder.None)

        assertFalse(result.changed)
        assertEquals(0, result.hired)
        assertEquals(1000L, result.credits)
    }

    @Test
    fun `a non-positive requested quantity is a no-op`() {
        for (units in listOf(0, -1, -10)) {
            val result =
                Hiring.resolve(credits = 1000L, currentCrew = 0, crewCapacity = 2, offersCrew = true, order = HireOrder.Hire(units))
            assertFalse("Hire($units) must be a no-op", result.changed)
            assertEquals("Hire($units) hires nobody", 0, result.hired)
            assertEquals("Hire($units) leaves credits intact", 1000L, result.credits)
        }
    }

    @Test
    fun `a non-positive price per crew is rejected (div-by-zero guard)`() {
        for (badPrice in listOf(0L, -100L)) {
            assertThrows(IllegalArgumentException::class.java) {
                Hiring.resolve(
                    credits = 1000L,
                    currentCrew = 0,
                    crewCapacity = 2,
                    offersCrew = true,
                    order = HireOrder.Hire(1),
                    pricePerCrew = badPrice,
                )
            }
        }
    }

    @Test
    fun `resolve is deterministic — identical inputs yield identical results`() {
        val a = Hiring.resolve(credits = 500L, currentCrew = 1, crewCapacity = 4, offersCrew = true, order = HireOrder.Hire(2))
        val b = Hiring.resolve(credits = 500L, currentCrew = 1, crewCapacity = 4, offersCrew = true, order = HireOrder.Hire(2))

        assertEquals(a, b)
    }
}
