package com.orbitalfrontier.faction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [StandingGate] (UC48 AC#1/#4) — the side-effect-free decision of whether a
 * shop/shipyard item is currently **buyable** given the player's standing with the docked station's
 * faction, plus the "why locked" payload the screens render.
 *
 * The acquisition analogue of [ReputationGate] (which gates mission offers). These cases pin both
 * halves of the contract — an un-gated item (`unlockThreshold <= 0`) is always available (the
 * byte-identity anchor over a pre-UC48 catalog), and a gated item is available iff the player's
 * standing with the station's faction is at or above its threshold — plus the lock-reason fields the
 * screens consume and the null-faction authoring-error case.
 */
class StandingGateTest {
    private val league = FactionId("league")
    private val independents = FactionId("independents")

    // --- Un-gated items are always available (the byte-identity anchor) ---

    @Test
    fun `a zero-threshold item is always available regardless of reputation`() {
        assertTrue(
            "ungated at neutral",
            StandingGate.status(0, league, Reputation.EMPTY).available,
        )
        assertTrue(
            "ungated even at a hostile standing",
            StandingGate.status(0, league, Reputation(mapOf(league to -100))).available,
        )
        assertTrue(
            "ungated even at a faction-less station",
            StandingGate.status(0, null, Reputation.EMPTY).available,
        )
    }

    @Test
    fun `a negative threshold is treated as un-gated`() {
        assertTrue(StandingGate.status(-5, league, Reputation.EMPTY).available)
    }

    // --- Gated items follow the >= rule ---

    @Test
    fun `a gated item is locked below its threshold`() {
        assertFalse(
            "locked at neutral 0",
            StandingGate.status(10, league, Reputation.EMPTY).available,
        )
        assertFalse(
            "locked one short of the threshold",
            StandingGate.status(10, league, Reputation(mapOf(league to 9))).available,
        )
    }

    @Test
    fun `a gated item is available exactly at its threshold`() {
        assertTrue(
            "available at exactly the threshold (>=)",
            StandingGate.status(10, league, Reputation(mapOf(league to 10))).available,
        )
    }

    @Test
    fun `a gated item is available above its threshold`() {
        assertTrue(StandingGate.status(10, league, Reputation(mapOf(league to 55))).available)
    }

    @Test
    fun `the gate keys on the station's faction, not another`() {
        // A high standing with a DIFFERENT faction must not open a league gate.
        assertFalse(
            "independents standing cannot unlock a league-gated item",
            StandingGate.status(10, league, Reputation(mapOf(independents to 80))).available,
        )
    }

    // --- Null-faction authoring error ---

    @Test
    fun `a positive threshold at a faction-less station is permanently locked`() {
        // currentStanding reads back 0 for a null faction, so a positive threshold can never be met —
        // surfaced as an authoring error via the lock reason, never special-cased.
        val status = StandingGate.status(10, null, Reputation(mapOf(league to 999)))
        assertFalse("a null-faction gated item is locked", status.available)
        assertEquals("standing reads back 0 at a faction-less station", 0, status.currentStanding)
    }

    // --- StandingStatus payload (what the screens render) ---

    @Test
    fun `the status carries the requirement, current standing and faction for a locked row`() {
        val status = StandingGate.status(10, league, Reputation(mapOf(league to 4)))
        assertFalse(status.available)
        assertTrue("locked is the inverse of available", status.locked)
        assertEquals(10, status.requiredStanding)
        assertEquals(4, status.currentStanding)
        assertEquals(league, status.factionId)
    }

    @Test
    fun `locked is the strict inverse of available for an unlocked item`() {
        val status = StandingGate.status(10, league, Reputation(mapOf(league to 20)))
        assertTrue(status.available)
        assertFalse(status.locked)
    }

    // --- Determinism ---

    @Test
    fun `status is deterministic for identical inputs`() {
        val rep = Reputation(mapOf(league to 7))
        assertEquals(
            StandingGate.status(10, league, rep),
            StandingGate.status(10, league, rep),
        )
    }
}
