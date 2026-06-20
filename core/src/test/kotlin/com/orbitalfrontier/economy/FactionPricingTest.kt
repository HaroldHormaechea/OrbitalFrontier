package com.orbitalfrontier.economy

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FactionPricing] (UC46 AC#2) — the pure faction/reputation price seam that ADR
 * 0007/0013 reserved and deferred.
 *
 * Pure and deterministic, so plain JVM unit tests. They pin the byte-identity anchor (exactly 1.0 at a
 * null faction or a neutral standing, so uc08's league-owned Alpha sale stays byte-identical), the
 * graded discount/markup either side of neutral, the shared `[minMul, maxMul]` clamp, and determinism.
 */
class FactionPricingTest {
    private val league = FactionId("league")
    private val params = PricingParams()

    private fun adjust(
        faction: FactionId?,
        standing: Int,
    ): Double {
        val reputation = if (standing == 0) Reputation.EMPTY else Reputation(mapOf(league to standing))
        return FactionPricing.adjust(faction, reputation, params)
    }

    // --- Byte-identity anchor: exactly 1.0 ---

    @Test
    fun `a null faction returns exactly 1·0`() {
        assertEquals(1.0, FactionPricing.adjust(null, Reputation(mapOf(league to 80)), params), 0.0)
    }

    @Test
    fun `a neutral (0) standing returns exactly 1·0`() {
        assertEquals("neutral standing is the identity multiplier", 1.0, adjust(league, 0), 0.0)
    }

    @Test
    fun `an EMPTY reputation (fresh game) returns exactly 1·0`() {
        assertEquals(1.0, FactionPricing.adjust(league, Reputation.EMPTY, params), 0.0)
    }

    // --- Graded multiplier by standing ---

    @Test
    fun `a positive (allied) standing earns a discount below 1·0`() {
        val mul = adjust(league, 50)
        // raw = 1 - 0.1 * (50 / 100) = 0.95.
        assertEquals(0.95, mul, 1e-9)
        assertTrue("an allied standing prices below base", mul < 1.0)
    }

    @Test
    fun `a negative (hostile) standing levies a markup above 1·0`() {
        val mul = adjust(league, -50)
        // raw = 1 - 0.1 * (-50 / 100) = 1.05.
        assertEquals(1.05, mul, 1e-9)
        assertTrue("a hostile standing prices above base", mul > 1.0)
    }

    @Test
    fun `the multiplier grades monotonically with standing`() {
        val hostile = adjust(league, -80)
        val slightlyHostile = adjust(league, -20)
        val neutral = adjust(league, 0)
        val slightlyAllied = adjust(league, 20)
        val allied = adjust(league, 80)

        assertTrue(
            "higher standing ⇒ a lower (more favourable) multiplier",
            hostile > slightlyHostile && slightlyHostile > neutral && neutral > slightlyAllied && slightlyAllied > allied,
        )
    }

    // --- Shared [minMul, maxMul] clamp ---

    @Test
    fun `an extreme allied standing is clamped at minMul`() {
        // raw = 1 - 0.1 * (100000/100) = -99, clamped up to minMul (0.5).
        assertEquals("an extreme discount floors at minMul", params.minMul, adjust(league, 100_000), 0.0)
    }

    @Test
    fun `an extreme hostile standing is clamped at maxMul`() {
        // raw = 1 - 0.1 * (-100000/100) = 101, clamped down to maxMul (1.5).
        assertEquals("an extreme markup ceilings at maxMul", params.maxMul, adjust(league, -100_000), 0.0)
    }

    // --- Determinism ---

    @Test
    fun `adjust is deterministic for identical inputs`() {
        assertEquals(adjust(league, 37), adjust(league, 37), 0.0)
    }

    // --- adjustedPrice: the single source of truth for an acquisition's effective price (UC48 AC#2) ---

    private fun adjustedPrice(
        basePrice: Long,
        faction: FactionId?,
        standing: Int,
    ): Long {
        val reputation = if (standing == 0) Reputation.EMPTY else Reputation(mapOf(league to standing))
        return FactionPricing.adjustedPrice(basePrice, faction, reputation, params)
    }

    @Test
    fun `adjustedPrice at a neutral standing is exactly the base price (byte-identity)`() {
        assertEquals("neutral standing prices at the authored base", 700L, adjustedPrice(700L, league, 0))
    }

    @Test
    fun `adjustedPrice at a null faction is exactly the base price (byte-identity)`() {
        assertEquals(700L, adjustedPrice(700L, null, 80))
    }

    @Test
    fun `adjustedPrice at an allied standing rounds to a discount below base`() {
        // mul = 1 - 0.1 * (10/100) = 0.99 ⇒ round(700 * 0.99) = round(693.0) = 693.
        assertEquals(693L, adjustedPrice(700L, league, 10))
        // mul = 0.95 ⇒ round(700 * 0.95) = 665.
        assertEquals(665L, adjustedPrice(700L, league, 50))
        assertTrue("an allied standing prices below base", adjustedPrice(700L, league, 50) < 700L)
    }

    @Test
    fun `adjustedPrice at a hostile standing rounds to a surcharge above base`() {
        // mul = 1 - 0.1 * (-50/100) = 1.05 ⇒ round(700 * 1.05) = 735.
        assertEquals(735L, adjustedPrice(700L, league, -50))
        assertTrue("a hostile standing prices above base", adjustedPrice(700L, league, -50) > 700L)
    }

    @Test
    fun `adjustedPrice honours the shared minMul clamp at an extreme allied standing`() {
        // mul floors at minMul (0.5) ⇒ round(700 * 0.5) = 350.
        assertEquals(350L, adjustedPrice(700L, league, 100_000))
    }

    @Test
    fun `adjustedPrice never returns below 1 credit`() {
        // A zero base would round to 0; the >=1 floor keeps a discounted item from being free.
        assertEquals(1L, adjustedPrice(0L, league, 50))
    }

    @Test
    fun `adjustedPrice is deterministic for identical inputs`() {
        assertEquals(adjustedPrice(2500L, league, 37), adjustedPrice(2500L, league, 37))
    }
}
