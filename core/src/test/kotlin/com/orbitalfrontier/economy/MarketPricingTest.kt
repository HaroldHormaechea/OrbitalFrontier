package com.orbitalfrontier.economy

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MarketPricing] (UC46 AC#1/#2/#4) — the pure engine that turns an authored
 * [StationMarket] base into the effective market for a tick by blending supply/demand, seeded drift
 * and faction multipliers, all clamped to the [TradeOffer] invariant.
 *
 * Pure and deterministic, so plain JVM unit tests (UC46 AC#4). They pin: the byte-identity anchor
 * (every multiplier exactly 1.0 ⇒ effective == base, UC46 risk #1), supply/demand moving price by the
 * pressure sign, the `[minMul, maxMul]` swing clamp, the preserved offer invariant, the dedicated
 * **drift == exactly 1.0 at epoch 0** guard for several arbitrary keys, and the drift kill-switch.
 */
class MarketPricingTest {
    private val alpha = PoiId("alpha-station")
    private val league: FactionId? = null // unaligned ⇒ faction multiplier is exactly 1.0
    private val neutral = Reputation.EMPTY

    /** A small authored base market with a spread of price magnitudes (mirrors the MvpSectorMap shape). */
    private val base =
        StationMarket(
            mapOf(
                ResourceType.HYDROGEN to TradeOffer(buyPrice = 6, sellPrice = 4),
                ResourceType.IRON_ORE to TradeOffer(buyPrice = 10, sellPrice = 8),
                ResourceType.TITANIUM to TradeOffer(buyPrice = 60, sellPrice = 50),
            ),
        )

    private fun effective(
        state: StationMarketState = StationMarketState.EMPTY,
        tick: Int = 0,
        params: PricingParams = PricingParams(),
        faction: FactionId? = league,
        reputation: Reputation = neutral,
    ): StationMarket = MarketPricing.effectiveMarket(base, alpha, state, tick, params, faction, reputation)

    // --- Byte-identity anchor: every multiplier exactly 1.0 ---

    @Test
    fun `at pressure-0, tick-0 and neutral every offer equals the authored base`() {
        val result = effective()

        assertEquals("the whole effective market equals the authored base at the anchor", base, result)
        for ((resource, offer) in base.offers) {
            assertEquals("offer for $resource is unchanged", offer, result.offerFor(resource))
        }
    }

    @Test
    fun `an empty base market is returned untouched`() {
        val result =
            MarketPricing.effectiveMarket(
                StationMarket.EMPTY,
                alpha,
                StationMarketState.EMPTY,
                0,
                PricingParams(),
                league,
                neutral,
            )

        assertEquals(StationMarket.EMPTY, result)
    }

    // --- Supply/demand moves price by pressure sign ---

    @Test
    fun `positive (oversupply) pressure lowers prices`() {
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.SELL, 6)

        val titanium = effective(state = state).offerFor(ResourceType.TITANIUM)!!
        // supplyDemandMul = 1 - 0.02*(6/1) = 0.88, so sell 50 -> 44, buy 60 -> 53.
        assertEquals("sell price drops under oversupply", 44L, titanium.sellPrice)
        assertTrue("the price genuinely moved below base", titanium.sellPrice < 50L)
    }

    @Test
    fun `negative (scarcity) pressure raises prices`() {
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.BUY, 6)

        val titanium = effective(state = state).offerFor(ResourceType.TITANIUM)!!
        // supplyDemandMul = 1 - 0.02*(-6/1) = 1.12, so sell 50 -> 56, buy 60 -> 67.
        assertTrue("sell price rises under scarcity", titanium.sellPrice > 50L)
        assertTrue("buy price rises under scarcity", titanium.buyPrice > 60L)
    }

    @Test
    fun `an untraded resource at a station stays at base while a traded one moves`() {
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.SELL, 6)
        val result = effective(state = state)

        assertEquals("the untraded iron offer is unchanged", base.offerFor(ResourceType.IRON_ORE), result.offerFor(ResourceType.IRON_ORE))
        assertNotEquals("the traded titanium offer moved", base.offerFor(ResourceType.TITANIUM), result.offerFor(ResourceType.TITANIUM))
    }

    // --- Swing clamp at [minMul, maxMul] ---

    @Test
    fun `an extreme oversupply pressure is clamped at minMul`() {
        // A huge positive pressure would push the multiplier far below minMul (0.5); the clamp pins it.
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.SELL, 100_000)
        val params = PricingParams()

        val titanium = effective(state = state, params = params).offerFor(ResourceType.TITANIUM)!!
        // Floor: sell = round(50 * 0.5) = 25, buy = round(60 * 0.5) = 30.
        assertEquals("sell price is clamped at minMul, not driven to 0", (50 * params.minMul).toLong(), titanium.sellPrice)
        assertEquals("buy price is clamped at minMul", (60 * params.minMul).toLong(), titanium.buyPrice)
    }

    @Test
    fun `an extreme scarcity pressure is clamped at maxMul`() {
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.BUY, 100_000)
        val params = PricingParams()

        val titanium = effective(state = state, params = params).offerFor(ResourceType.TITANIUM)!!
        // Ceiling: sell = round(50 * 1.5) = 75, buy = round(60 * 1.5) = 90.
        assertEquals("sell price is clamped at maxMul", (50 * params.maxMul).toLong(), titanium.sellPrice)
        assertEquals("buy price is clamped at maxMul", (60 * params.maxMul).toLong(), titanium.buyPrice)
    }

    // --- TradeOffer invariant preserved under extreme inputs ---

    @Test
    fun `every repriced offer preserves the TradeOffer invariant under extreme pressure`() {
        // Build the cartesian product of extreme buys and sells, large drift and faction skew, and assert
        // each repriced offer still satisfies buyPrice > 0 and 0 <= sellPrice <= buyPrice (it constructs
        // at all — TradeOffer.init would throw otherwise — and we re-check explicitly).
        val params = PricingParams(driftAmplitude = 0.4, elasticity = 0.5)
        for (units in listOf(-100_000, -7, -1, 1, 7, 100_000)) {
            val kind = if (units >= 0) TradeKind.SELL else TradeKind.BUY
            val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, kind, kotlin.math.abs(units))
            for (tick in listOf(0, 1, 200, 5000)) {
                val result =
                    MarketPricing.effectiveMarket(
                        base,
                        alpha,
                        state,
                        tick,
                        params,
                        FactionId("league"),
                        Reputation(mapOf(FactionId("league") to 100)),
                    )
                for ((resource, offer) in result.offers) {
                    assertTrue("buyPrice > 0 for $resource (units=$units tick=$tick)", offer.buyPrice > 0)
                    assertTrue("0 <= sellPrice <= buyPrice for $resource (units=$units tick=$tick)", offer.sellPrice in 0..offer.buyPrice)
                }
            }
        }
    }

    // --- Drift: EXACTLY 1.0 at epoch 0 for several arbitrary keys ---

    @Test
    fun `drift is exactly 1·0 at epoch 0 for several arbitrary station and resource keys`() {
        // Isolate drift: elasticity 0 kills supply/demand (mul 1.0 regardless of pressure), a null faction
        // kills faction (mul 1.0), and a LARGE drift amplitude would clearly move the price IF drift were
        // ever != 1.0. At tick 0 (epoch 0) drift subtracts the epoch-0 seed from itself ⇒ exactly 1.0, so
        // the effective price must equal the base EXACTLY — for every arbitrary (station, resource) key.
        val params = PricingParams(elasticity = 0.0, driftAmplitude = 0.9)
        val seededPressure = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.SELL, 9)

        for (stationSlug in listOf("alpha-station", "beta-station", "gamma-junkyard", "zzz-frontier-7", "x")) {
            val station = PoiId(stationSlug)
            // Even with non-zero pressure present, elasticity 0 means only drift could move the price.
            val result = MarketPricing.effectiveMarket(base, station, seededPressure, 0, params, null, neutral)
            assertEquals("drift must be exactly 1.0 at epoch 0 for station '$stationSlug' ⇒ effective == base", base, result)
        }
    }

    @Test
    fun `drift stays exactly 1·0 across every tick within epoch 0`() {
        val params = PricingParams(elasticity = 0.0, driftAmplitude = 0.9, driftPeriodTicks = 128)

        // Ticks 0..127 are all epoch 0 (tick / 128 == 0), so drift is exactly 1.0 for all of them.
        for (tick in listOf(0, 1, 5, 63, 127)) {
            val result = MarketPricing.effectiveMarket(base, alpha, StationMarketState.EMPTY, tick, params, null, neutral)
            assertEquals("epoch 0 holds at tick $tick ⇒ effective == base", base, result)
        }
    }

    // --- Drift kill-switch ---

    @Test
    fun `drift amplitude 0 fully disables drift at every epoch`() {
        // With drift off (and supply/demand + faction at identity), the effective market equals base at
        // ANY tick — drift can never move it.
        val noDrift = PricingParams(elasticity = 0.0, driftAmplitude = 0.0, driftPeriodTicks = 128)

        for (tick in listOf(0, 128, 256, 99_999)) {
            val result = MarketPricing.effectiveMarket(base, alpha, StationMarketState.EMPTY, tick, noDrift, null, neutral)
            assertEquals("driftAmplitude 0 keeps effective == base at tick $tick", base, result)
        }
    }

    @Test
    fun `a non-zero drift amplitude does move prices at a later epoch (proving the kill-switch matters)`() {
        // Same later-epoch tick, two amplitudes: 0 keeps base; a large amplitude moves at least one offer.
        // This proves drift is real (so the kill-switch above is meaningful), without pinning a magic value.
        val tick = 128 // epoch 1
        val withDrift = PricingParams(elasticity = 0.0, driftAmplitude = 0.9, driftPeriodTicks = 128)
        val noDrift = withDrift.copy(driftAmplitude = 0.0)

        val driven = MarketPricing.effectiveMarket(base, alpha, StationMarketState.EMPTY, tick, withDrift, null, neutral)
        val flat = MarketPricing.effectiveMarket(base, alpha, StationMarketState.EMPTY, tick, noDrift, null, neutral)

        assertEquals("the no-drift control is still base", base, flat)
        assertNotEquals("a large drift amplitude moves the market at epoch 1", base, driven)
    }

    // --- Determinism ---

    @Test
    fun `effectiveMarket is deterministic for identical inputs`() {
        val params = PricingParams(driftAmplitude = 0.3)
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.SELL, 4)

        val a = MarketPricing.effectiveMarket(base, alpha, state, 300, params, null, neutral)
        val b = MarketPricing.effectiveMarket(base, alpha, state, 300, params, null, neutral)
        assertEquals("identical inputs ⇒ identical effective market", a, b)
    }

    @Test
    fun `the effective offer set never grows beyond the authored base`() {
        val state = StationMarketState.EMPTY.withTrade(alpha, ResourceType.TITANIUM, TradeKind.SELL, 6)
        val result = effective(state = state)

        assertEquals("the repriced offer set has the same resources as the base", base.offers.keys, result.offers.keys)
        assertNull("a resource not offered by the base is never priced in", result.offerFor(ResourceType.PLATINUM))
    }
}
