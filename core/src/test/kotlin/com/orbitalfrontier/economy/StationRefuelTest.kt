package com.orbitalfrontier.economy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StationRefuel] (UC18) — the pure, deterministic credits-for-fuel station refuel
 * that fixes the broken refuelling reported in UC18 ("sometimes nothing happens; other times it only
 * refuels ~20 units").
 *
 * Refuelling is engine-free and side-effect-free, so these are plain JVM unit tests. They pin the
 * contract every refuel path (device + sim/replay) relies on, mapped to the UC18 acceptance criteria:
 *  - **AC#1** — a BUY with room + affordable fuel always changes state ([StationRefuelStatus.REFUELED]),
 *    never a silent no-op.
 *  - **AC#2** — the amount bought is `min(credits / price, floor(remainingCapacity))`: filled up to
 *    capacity, bounded by credits — **never a spurious fixed amount such as ~20 units**.
 *  - **AC#3** — the invariant `credits deducted == unitsBought * price` AND `fuel added == unitsBought`
 *    (no charge-without-fuel, no fuel-without-charge).
 *  - **AC#4** — full / broke / unavailable / idle each resolve to a distinct, deterministic no-op
 *    status rather than a silent or partial failure.
 *  - **AC#5** — determinism: identical inputs always yield an identical result.
 */
class StationRefuelTest {
    private val tolerance = 1e-6

    /** A tank of the default 100-unit capacity filled to [level]. */
    private fun tank(level: Float): Fuel = Fuel(level = level, capacity = 100f)

    // --- AC#1/#2/#3: REFUELED, full fill bounded by tank space ---

    @Test
    fun `a wallet that can afford the whole tank fills to capacity, charging cost = units times price`() {
        // Tank 50/100 (50 free), wallet far exceeds the cost, price 6 ⇒ buy all 50 units, cost 300.
        val result = StationRefuel.resolve(credits = 10_000, fuel = tank(50f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertEquals(StationRefuelStatus.REFUELED, result.status)
        assertEquals("buys the 50 units of tank room", 50L, result.unitsBought)
        assertEquals("tank ends exactly full", 100f, result.fuel.level)
        assertEquals("credits reduced by 50 * 6", 9_700L, result.credits)
        assertInvariants(creditsBefore = 10_000, fuelBefore = tank(50f), price = 6, result = result)
    }

    // --- AC#2: partial fill bounded by credits — the CORRECT partial, never a fixed +20 ---

    @Test
    fun `a partial wallet buys exactly what credits afford, not a spurious fixed amount`() {
        // Tank 50/100 (50 free) so the tank is NOT the binding limit. Credits 30 at price 6 ⇒ 5 units.
        // The historical bug refuelled ~20 regardless; the correct answer here is 5.
        val result = StationRefuel.resolve(credits = 30, fuel = tank(50f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertEquals(StationRefuelStatus.REFUELED, result.status)
        assertEquals("buys exactly the 5 units 30 credits afford", 5L, result.unitsBought)
        assertNotEquals("must NOT be the spurious fixed ~20 units", 20L, result.unitsBought)
        assertEquals("tank topped up by 5", 55.0, result.fuel.level.toDouble(), tolerance)
        assertEquals("credits fully spent", 0L, result.credits)
        assertInvariants(creditsBefore = 30, fuelBefore = tank(50f), price = 6, result = result)
    }

    @Test
    fun `the credits-bounded amount tracks the wallet, not a constant`() {
        // A second, differently-priced wallet proves the amount is computed, not hardcoded: 63 / 7 = 9.
        val result = StationRefuel.resolve(credits = 63, fuel = tank(0f), fuelPricePerUnit = 7, action = StationRefuelAction.BUY)

        assertEquals(StationRefuelStatus.REFUELED, result.status)
        assertEquals("9 units = floor(63 / 7)", 9L, result.unitsBought)
        assertEquals(0L, result.credits)
        assertInvariants(creditsBefore = 63, fuelBefore = tank(0f), price = 7, result = result)
    }

    // --- AC#2: partial fill bounded by tank space ---

    @Test
    fun `a rich wallet is bounded by the tank space remaining`() {
        // Tank 93/100 (7 free). Wallet could buy far more, so the tank is the binding limit ⇒ 7 units.
        val result = StationRefuel.resolve(credits = 10_000, fuel = tank(93f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertEquals(StationRefuelStatus.REFUELED, result.status)
        assertEquals("only the 7 units that fit are bought", 7L, result.unitsBought)
        assertEquals("tank ends exactly full", 100f, result.fuel.level)
        assertInvariants(creditsBefore = 10_000, fuelBefore = tank(93f), price = 6, result = result)
    }

    @Test
    fun `only whole units are bought, leaving a sub-unit sliver of tank space`() {
        // Tank 95.5/100 (4.5 free) ⇒ floor(4.5) = 4 whole units; the half-unit of room is left unused.
        val result = StationRefuel.resolve(credits = 10_000, fuel = tank(95.5f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertEquals(StationRefuelStatus.REFUELED, result.status)
        assertEquals("4 whole units fit; the 0.5 sliver is left", 4L, result.unitsBought)
        assertEquals(99.5, result.fuel.level.toDouble(), tolerance)
        assertInvariants(creditsBefore = 10_000, fuelBefore = tank(95.5f), price = 6, result = result)
    }

    @Test
    fun `buying exactly one affordable unit into one unit of room succeeds`() {
        // Boundary: credits afford exactly 1 unit and the tank has exactly 1 unit of room.
        val result = StationRefuel.resolve(credits = 6, fuel = tank(99f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertEquals(StationRefuelStatus.REFUELED, result.status)
        assertEquals(1L, result.unitsBought)
        assertEquals(100f, result.fuel.level)
        assertEquals(0L, result.credits)
        assertInvariants(creditsBefore = 6, fuelBefore = tank(99f), price = 6, result = result)
    }

    // --- AC#4: FULL no-op ---

    @Test
    fun `a full tank is a no-op reporting FULL, charging nothing`() {
        val full = Fuel.full()
        val result = StationRefuel.resolve(credits = 10_000, fuel = full, fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.FULL, creditsBefore = 10_000, fuelBefore = full, result = result)
    }

    @Test
    fun `a sub-unit sliver of room reports FULL, not a false BROKE, even when broke`() {
        // Tank 99.5/100 (0.5 free ⇒ floor 0 whole units). FULL is checked BEFORE the broke test, so a
        // penniless player at a near-full tank gets FULL, never a misleading BROKE (UC18 AC#4 ordering).
        val nearlyFull = tank(99.5f)
        val result = StationRefuel.resolve(credits = 0, fuel = nearlyFull, fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.FULL, creditsBefore = 0, fuelBefore = nearlyFull, result = result)
    }

    // --- AC#4: BROKE no-op ---

    @Test
    fun `room but cannot afford even one unit is a no-op reporting BROKE`() {
        // Tank 50/100 (50 free, so the tank is not the limit). 5 credits at price 6 ⇒ 0 affordable units.
        val fuel = tank(50f)
        val result = StationRefuel.resolve(credits = 5, fuel = fuel, fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.BROKE, creditsBefore = 5, fuelBefore = fuel, result = result)
    }

    @Test
    fun `zero credits with tank room is BROKE`() {
        val fuel = tank(40f)
        val result = StationRefuel.resolve(credits = 0, fuel = fuel, fuelPricePerUnit = 8, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.BROKE, creditsBefore = 0, fuelBefore = fuel, result = result)
    }

    // --- AC#4: UNAVAILABLE no-op (station sells no fuel) ---

    @Test
    fun `a null price is a no-op reporting UNAVAILABLE`() {
        val fuel = tank(50f)
        val result = StationRefuel.resolve(credits = 1_000, fuel = fuel, fuelPricePerUnit = null, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.UNAVAILABLE, creditsBefore = 1_000, fuelBefore = fuel, result = result)
    }

    @Test
    fun `a zero price is a no-op reporting UNAVAILABLE`() {
        val fuel = tank(50f)
        val result = StationRefuel.resolve(credits = 1_000, fuel = fuel, fuelPricePerUnit = 0, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.UNAVAILABLE, creditsBefore = 1_000, fuelBefore = fuel, result = result)
    }

    @Test
    fun `a negative price is a no-op reporting UNAVAILABLE`() {
        val fuel = tank(50f)
        val result = StationRefuel.resolve(credits = 1_000, fuel = fuel, fuelPricePerUnit = -5, action = StationRefuelAction.BUY)

        assertNoOp(StationRefuelStatus.UNAVAILABLE, creditsBefore = 1_000, fuelBefore = fuel, result = result)
    }

    // --- AC#4: NONE no-op (no purchase requested) ---

    @Test
    fun `the NONE action returns the inputs unchanged even at a valid station`() {
        val fuel = tank(50f)
        val result = StationRefuel.resolve(credits = 1_000, fuel = fuel, fuelPricePerUnit = 6, action = StationRefuelAction.NONE)

        assertNoOp(StationRefuelStatus.NONE, creditsBefore = 1_000, fuelBefore = fuel, result = result)
    }

    // --- AC#5: determinism ---

    @Test
    fun `identical inputs always yield an identical result`() {
        val first = StationRefuel.resolve(credits = 137, fuel = tank(42f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)
        val second = StationRefuel.resolve(credits = 137, fuel = tank(42f), fuelPricePerUnit = 6, action = StationRefuelAction.BUY)

        assertEquals("the pure resolver is deterministic", first, second)
    }

    @Test
    fun `the credits-deducted = units times price invariant holds across the binding cases`() {
        // One scenario bounded by credits, one by tank space — the AC#3 invariant must hold for both.
        data class Case(val credits: Long, val fuel: Fuel, val price: Long)
        val cases =
            listOf(
                // credits-bound
                Case(30, tank(50f), 6),
                // tank-bound
                Case(10_000, tank(93f), 6),
                // exact credits divide
                Case(63, tank(0f), 7),
            )
        for (c in cases) {
            val result = StationRefuel.resolve(c.credits, c.fuel, c.price, StationRefuelAction.BUY)
            assertEquals("every case here should refuel", StationRefuelStatus.REFUELED, result.status)
            assertInvariants(creditsBefore = c.credits, fuelBefore = c.fuel, price = c.price, result = result)
            assertTrue("credits never go negative", result.credits >= 0)
        }
    }

    /**
     * Asserts the UC18 AC#3 invariants for a [StationRefuelStatus.REFUELED] result: the credits removed
     * equal `unitsBought * price`, and the fuel added equals `unitsBought` (1 fuel unit per unit bought).
     */
    private fun assertInvariants(
        creditsBefore: Long,
        fuelBefore: Fuel,
        price: Long,
        result: StationRefuelResult,
    ) {
        val creditsDeducted = creditsBefore - result.credits
        val fuelAdded = (result.fuel.level - fuelBefore.level).toDouble()
        assertEquals("credits deducted == unitsBought * price", result.unitsBought * price, creditsDeducted)
        assertEquals("fuel added == unitsBought", result.unitsBought.toDouble(), fuelAdded, tolerance)
    }

    /**
     * Asserts a no-op outcome: the expected [status], zero units bought, and the inputs returned
     * untouched (same [Fuel] instance, unchanged credits) so a no-op never mutates state (UC18 AC#4).
     */
    private fun assertNoOp(
        status: StationRefuelStatus,
        creditsBefore: Long,
        fuelBefore: Fuel,
        result: StationRefuelResult,
    ) {
        assertEquals(status, result.status)
        assertEquals("a no-op buys nothing", 0L, result.unitsBought)
        assertEquals("a no-op spends nothing", creditsBefore, result.credits)
        assertSame("a no-op returns the fuel unchanged", fuelBefore, result.fuel)
    }
}
