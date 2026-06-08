package com.orbitalfrontier.faction

import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [ReputationGate] (UC14 AC#3/#5) — the side-effect-free decision of whether a
 * mission offer is currently **available** to a player given their standing.
 *
 * The gate is the SEPARATE filter applied AFTER generation + the takenIds filter (the determinism
 * invariant, ADR 0013): it changes only an offer's visibility, never its content. These cases pin both
 * halves of the contract — an un-gated offer is always available (identity over the offer list), and a
 * gated offer is available iff the player's standing with its faction is at or above its threshold.
 */
class ReputationGateTest {
    private val league = FactionId("league")
    private val independents = FactionId("independents")

    private fun ungatedOffer(): Mission =
        Mission(
            id = MissionId("board:alpha-station:mining"),
            type = MissionType.MINING,
            source = MissionSource.BOARD,
            status = MissionStatus.AVAILABLE,
            rewardCredits = 400,
            quotaResource = null,
            quotaUnits = 0,
            factionId = league,
        )

    private fun gatedOffer(
        unlockFaction: FactionId = league,
        threshold: Int = 10,
    ): Mission =
        Mission(
            id = MissionId("board:alpha-station:premium"),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.AVAILABLE,
            rewardCredits = 1135,
            pickup = PoiId("alpha-station"),
            destination = PoiId("beta-station"),
            remainingTicks = 100,
            factionId = unlockFaction,
            unlockFaction = unlockFaction,
            unlockThreshold = threshold,
        )

    @Test
    fun `an un-gated offer is always available regardless of reputation`() {
        val offer = ungatedOffer()
        assertTrue("available at neutral", ReputationGate.isAvailable(offer, Reputation.EMPTY))
        assertTrue(
            "available even at a hostile standing",
            ReputationGate.isAvailable(offer, Reputation(mapOf(league to -100))),
        )
    }

    @Test
    fun `a gated offer is hidden below its threshold`() {
        val offer = gatedOffer(threshold = 10)
        assertFalse("hidden at neutral 0", ReputationGate.isAvailable(offer, Reputation.EMPTY))
        assertFalse(
            "hidden one short of the threshold",
            ReputationGate.isAvailable(offer, Reputation(mapOf(league to 9))),
        )
    }

    @Test
    fun `a gated offer is available exactly at its threshold`() {
        val offer = gatedOffer(threshold = 10)
        assertTrue(
            "available at exactly the threshold (>=)",
            ReputationGate.isAvailable(offer, Reputation(mapOf(league to 10))),
        )
    }

    @Test
    fun `a gated offer is available above its threshold`() {
        val offer = gatedOffer(threshold = 10)
        assertTrue(ReputationGate.isAvailable(offer, Reputation(mapOf(league to 55))))
    }

    @Test
    fun `the gate keys on the offer's own faction, not another`() {
        val offer = gatedOffer(unlockFaction = league, threshold = 10)
        // Reputation with a DIFFERENT faction does not open a league gate.
        assertFalse(
            "independents standing cannot unlock a league-gated offer",
            ReputationGate.isAvailable(offer, Reputation(mapOf(independents to 80))),
        )
    }

    @Test
    fun `the gate is the identity over a list of un-gated offers`() {
        val offers = listOf(ungatedOffer(), ungatedOffer().copy(id = MissionId("radio:beta-station")))
        val visible = offers.filter { ReputationGate.isAvailable(it, Reputation.EMPTY) }
        assertEquals("every un-gated offer survives the filter", offers, visible)
    }
}
