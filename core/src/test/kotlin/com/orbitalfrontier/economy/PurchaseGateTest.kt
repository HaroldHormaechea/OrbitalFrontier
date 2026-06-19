package com.orbitalfrontier.economy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [PurchaseGate] — the pure, engine-free confirm-gate that fronts every economy spend
 * (UC40 AC#1/#3/#5).
 *
 * This is the single source of truth all five economy flows (Trade, Outfit, Shipyard, Hire, refuel)
 * consult, so the threshold and the "can I afford this?" rule live in one place. Because the gate is pure
 * value logic (no libGDX types), the whole of AC#1/#3 is unit-testable headlessly on the JVM (ADR 0001) —
 * which is what AC#5 ("the threshold/confirm-gate logic is unit-tested") asks for. The screens that drive
 * it are not headlessly constructible (live GL), so they are pinned by a source-anchored guard instead
 * (see [com.orbitalfrontier.screen.Uc40EconomyFeedbackSourceTest]); the *decision* is proven here.
 */
class PurchaseGateTest {
    // --- AC#1: PROCEED below the threshold (affordable) -------------------------------------------------

    @Test
    fun `an affordable spend below the threshold proceeds without confirmation`() {
        assertEquals(
            "a cheap, affordable tap fires immediately (today's frictionless behaviour)",
            SpendDecision.PROCEED,
            PurchaseGate.evaluate(cost = 250L, balance = 10_000L),
        )
    }

    @Test
    fun `a spend one credit below the threshold still proceeds`() {
        // The confirm boundary is inclusive (>= threshold), so 999 against a 1_000 threshold is below it.
        assertEquals(
            SpendDecision.PROCEED,
            PurchaseGate.evaluate(cost = PurchaseGate.CONFIRMATION_THRESHOLD_CREDITS - 1L, balance = 50_000L),
        )
    }

    @Test
    fun `a free (zero-cost) action proceeds`() {
        assertEquals(SpendDecision.PROCEED, PurchaseGate.evaluate(cost = 0L, balance = 0L))
    }

    // --- AC#1: CONFIRM at/above the threshold boundary (affordable) -------------------------------------

    @Test
    fun `an affordable spend exactly at the threshold prompts confirmation`() {
        // Boundary case: the threshold itself is the first value that requires a confirm (inclusive).
        assertEquals(
            "reaching the threshold (==) must confirm, not silently proceed",
            SpendDecision.CONFIRM,
            PurchaseGate.evaluate(cost = PurchaseGate.CONFIRMATION_THRESHOLD_CREDITS, balance = 50_000L),
        )
    }

    @Test
    fun `an affordable spend above the threshold prompts confirmation`() {
        assertEquals(
            SpendDecision.CONFIRM,
            PurchaseGate.evaluate(cost = 25_000L, balance = 50_000L),
        )
    }

    @Test
    fun `spending the entire balance at or above the threshold confirms (exact balance is affordable)`() {
        // cost == balance is affordable; at/above threshold it is a CONFIRM (not INSUFFICIENT).
        assertEquals(
            SpendDecision.CONFIRM,
            PurchaseGate.evaluate(cost = 5_000L, balance = 5_000L),
        )
    }

    @Test
    fun `spending the entire balance below the threshold proceeds (exact balance is affordable)`() {
        assertEquals(
            SpendDecision.PROCEED,
            PurchaseGate.evaluate(cost = 800L, balance = 800L),
        )
    }

    // --- AC#3: INSUFFICIENT when cost exceeds balance — and unaffordable wins first ---------------------

    @Test
    fun `a spend that exceeds the balance is insufficient`() {
        assertEquals(
            SpendDecision.INSUFFICIENT,
            PurchaseGate.evaluate(cost = 5_001L, balance = 5_000L),
        )
    }

    @Test
    fun `unaffordability wins over the confirm threshold`() {
        // An unaffordable spend is INSUFFICIENT regardless of whether it is above the confirm threshold —
        // the gate must never surface a confirm dialog for a buy the player cannot cover.
        assertEquals(
            "a large unaffordable spend is INSUFFICIENT, not CONFIRM",
            SpendDecision.INSUFFICIENT,
            PurchaseGate.evaluate(cost = 100_000L, balance = 500L),
        )
    }

    @Test
    fun `a cheap-but-unaffordable spend is insufficient, not proceed`() {
        // Even a sub-threshold cost is refused when the wallet can't cover it (cost > balance wins first).
        assertEquals(
            SpendDecision.INSUFFICIENT,
            PurchaseGate.evaluate(cost = 300L, balance = 100L),
        )
    }

    @Test
    fun `a broke wallet cannot afford any positive spend`() {
        assertEquals(SpendDecision.INSUFFICIENT, PurchaseGate.evaluate(cost = 1L, balance = 0L))
    }

    // --- AC#1: the threshold is a single centralized constant, and is honoured as the boundary ----------

    @Test
    fun `the confirmation threshold is the centralized constant`() {
        // AC#1: the threshold lives in exactly one place (a deliberately round MVP default). Pinning the
        // value guards against it being silently re-tuned or divorced from the gate.
        assertEquals(1_000L, PurchaseGate.CONFIRMATION_THRESHOLD_CREDITS)
    }

    @Test
    fun `evaluate honours an explicit threshold override at its boundary`() {
        val threshold = 2_000L
        assertEquals(
            "just below the supplied threshold proceeds",
            SpendDecision.PROCEED,
            PurchaseGate.evaluate(cost = threshold - 1L, balance = 50_000L, threshold = threshold),
        )
        assertEquals(
            "exactly the supplied threshold confirms",
            SpendDecision.CONFIRM,
            PurchaseGate.evaluate(cost = threshold, balance = 50_000L, threshold = threshold),
        )
    }

    @Test
    fun `evaluate defaults its threshold to the centralized constant`() {
        // Calling with the default threshold and calling with the constant explicitly must agree, proving
        // the screens (which call the two-arg form) gate on the single source of truth.
        for (cost in listOf(0L, 999L, 1_000L, 1_001L, 49_999L)) {
            assertEquals(
                "cost=$cost must decide identically whether the threshold is defaulted or passed explicitly",
                PurchaseGate.evaluate(cost, balance = 50_000L, threshold = PurchaseGate.CONFIRMATION_THRESHOLD_CREDITS),
                PurchaseGate.evaluate(cost, balance = 50_000L),
            )
        }
    }

    // --- AC#1: details() — the values the confirmation dialog renders -----------------------------------

    @Test
    fun `details computes the resulting balance as balance minus cost`() {
        val d = PurchaseGate.details(item = "HEAVY HULL", cost = 12_500L, balance = 50_000L)
        assertEquals("the item name is carried verbatim for the dialog", "HEAVY HULL", d.item)
        assertEquals("the cost is carried verbatim", 12_500L, d.cost)
        assertEquals("the dialog shows what the player is left with", 37_500L, d.resultingBalance)
    }

    @Test
    fun `details for a spend of the entire balance leaves zero`() {
        val d = PurchaseGate.details(item = "STARTER SHIP", cost = 5_000L, balance = 5_000L)
        assertEquals(0L, d.resultingBalance)
    }

    @Test
    fun `details is a dumb carrier and does not clamp a negative resulting balance`() {
        // details() holds no decision logic (the gate already decided affordability). If a caller asks for
        // the details of an unaffordable spend it simply reports the arithmetic, leaving the PROCEED/CONFIRM/
        // INSUFFICIENT decision to evaluate(). This keeps the SRP split documented in the class doc honest.
        val d = PurchaseGate.details(item = "OVERPRICED", cost = 100L, balance = 40L)
        assertEquals(-60L, d.resultingBalance)
    }
}
