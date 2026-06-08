package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [MissionLog] value (UC12 AC#3) — holding **multiple concurrent missions**, exposing
 * the active view, and the [MissionLog.takenIds] set the regenerate-and-filter invariant (ADR 0011)
 * uses to keep an already-accepted offer from re-surfacing.
 */
class MissionLogTest {
    private val alphaStation = PoiId("alpha-station")
    private val betaStation = PoiId("beta-station")

    private fun mining(id: String) =
        Mission(
            id = MissionId(id),
            type = MissionType.MINING,
            source = MissionSource.BOARD,
            status = MissionStatus.ACTIVE,
            rewardCredits = 400,
            quotaResource = ResourceType.HYDROGEN,
            quotaUnits = 8,
        )

    private fun courier(id: String) =
        Mission(
            id = MissionId(id),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.ACTIVE,
            rewardCredits = 535,
            pickup = alphaStation,
            destination = betaStation,
            remainingTicks = 100,
        )

    @Test
    fun `the log tracks two concurrent active missions`() {
        val log = MissionLog(accepted = listOf(mining("board:alpha-station:mining"), courier("board:alpha-station:courier")))

        assertEquals("both accepted missions are tracked", 2, log.accepted.size)
        assertEquals("both are active", 2, log.activeMissions.size)
        assertEquals(
            setOf(MissionId("board:alpha-station:mining"), MissionId("board:alpha-station:courier")),
            log.takenIds,
        )
    }

    @Test
    fun `accepting a second mission while one is active holds both`() {
        val log = MissionLog(accepted = listOf(mining("board:alpha-station:mining")))
        val secondOffer =
            courier("radio:beta-station").copy(status = MissionStatus.AVAILABLE)

        val result =
            Missions.resolve(
                log = log,
                offers = listOf(secondOffer),
                order = MissionOrder.Accept(secondOffer.id),
                dockedStation = null,
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertTrue(result.changed)
        assertEquals("the player now holds two concurrent missions", 2, result.log.activeMissions.size)
    }

    @Test
    fun `takenIds excludes an accepted offer from a freshly-regenerated offer list`() {
        // Simulate the regenerate-and-filter the screen does: regenerate offers, drop any already taken.
        val regenerated =
            listOf(
                mining("board:alpha-station:mining").copy(status = MissionStatus.AVAILABLE),
                courier("board:alpha-station:courier").copy(status = MissionStatus.AVAILABLE),
            )
        val log = MissionLog(accepted = listOf(mining("board:alpha-station:mining")))

        val surfaced = regenerated.filter { it.id !in log.takenIds }

        assertEquals("the accepted mining offer is filtered out", 1, surfaced.size)
        assertEquals(MissionId("board:alpha-station:courier"), surfaced.single().id)
    }

    @Test
    fun `activeMissions excludes terminal (completed or failed) missions`() {
        val log =
            MissionLog(
                accepted =
                    listOf(
                        mining("a"),
                        mining("b").copy(status = MissionStatus.COMPLETED),
                        courier("c").copy(status = MissionStatus.FAILED),
                    ),
            )

        assertEquals("only the in-progress mission is active", 1, log.activeMissions.size)
        assertEquals(MissionId("a"), log.activeMissions.single().id)
        assertEquals("but every accepted/terminal id keeps filtering offers", 3, log.takenIds.size)
    }

    @Test
    fun `the empty log holds nothing`() {
        assertTrue(MissionLog.EMPTY.accepted.isEmpty())
        assertTrue(MissionLog.EMPTY.available.isEmpty())
        assertTrue(MissionLog.EMPTY.activeMissions.isEmpty())
        assertFalse(MissionId("board:alpha-station:mining") in MissionLog.EMPTY.takenIds)
    }
}
