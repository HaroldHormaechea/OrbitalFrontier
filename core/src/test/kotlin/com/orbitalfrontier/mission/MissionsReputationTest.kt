package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the **reputation** side of the pure mission resolver (UC14 AC#4) — the standings
 * [Missions.resolve] grants on a faction-mission turn-in and [Missions.advance] costs on a courier
 * expiry.
 *
 * Reputation is action-driven and applied **only** through these pure resolvers (never as a side
 * effect of generation/gating). The no-op / faction-less cases assert **reference equality** of the
 * returned [Reputation] — the same-instance contract the simulation relies on to keep a pre-UC14
 * replay byte-identical and skip a spurious autosave.
 */
class MissionsReputationTest {
    private val alphaStation = PoiId("alpha-station")
    private val betaStation = PoiId("beta-station")
    private val league = FactionId("league")

    private val params = ReputationParams() // missionCompleteDelta=+10, courierFailDelta=-15, [-100,100]

    private fun leagueMining(quota: Int = 8): Mission =
        Mission(
            id = MissionId("board:alpha-station:mining"),
            type = MissionType.MINING,
            source = MissionSource.BOARD,
            status = MissionStatus.ACTIVE,
            rewardCredits = 400,
            quotaResource = ResourceType.HYDROGEN,
            quotaUnits = quota,
            factionId = league,
        )

    private fun factionlessMining(quota: Int = 8): Mission = leagueMining(quota).copy(factionId = null)

    private fun leagueCourier(ticks: Int = 1): Mission =
        Mission(
            id = MissionId("board:alpha-station:courier"),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.ACTIVE,
            rewardCredits = 535,
            pickup = alphaStation,
            destination = betaStation,
            remainingTicks = ticks,
            pickedUp = true,
            factionId = league,
        )

    // --- resolve: a turn-in grants the mission-complete delta (AC#4) ---

    @Test
    fun `turning in a faction mining mission grants the mission-complete delta to its faction`() {
        val mission = leagueMining(quota = 8)
        val log = MissionLog(accepted = listOf(mission))
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 8), Cargo.DEFAULT_CAPACITY)

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 0L,
                reputation = Reputation.EMPTY,
                reputationParams = params,
            )

        assertTrue(result.changed)
        assertEquals("the league standing rose by missionCompleteDelta (+10)", 10, result.reputation.valueFor(league))
    }

    @Test
    fun `a faction turn-in accumulates onto an existing standing, clamped to the max`() {
        val mission = leagueMining(quota = 8)
        val log = MissionLog(accepted = listOf(mission))
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 8), Cargo.DEFAULT_CAPACITY)

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 0L,
                // Already near the allied ceiling: +10 clamps to 100, not 105.
                reputation = Reputation(mapOf(league to 95)),
                reputationParams = params,
            )

        assertEquals("the gain is clamped to the params' max bound", params.max, result.reputation.valueFor(league))
    }

    @Test
    fun `turning in a faction-less mission leaves reputation as the same instance`() {
        val mission = factionlessMining(quota = 8)
        val log = MissionLog(accepted = listOf(mission))
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 8), Cargo.DEFAULT_CAPACITY)
        val reputation = Reputation(mapOf(league to 40))

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 0L,
                reputation = reputation,
                reputationParams = params,
            )

        assertTrue("the mining mission still completes (credits/cargo)", result.changed)
        assertSame("a faction-less mission never moves reputation (same instance)", reputation, result.reputation)
    }

    @Test
    fun `a no-op turn-in (quota shortfall) returns the input reputation instance unchanged`() {
        val mission = leagueMining(quota = 8)
        val log = MissionLog(accepted = listOf(mission))
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 5), Cargo.DEFAULT_CAPACITY) // short of the quota
        val reputation = Reputation(mapOf(league to 20))

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 0L,
                reputation = reputation,
                reputationParams = params,
            )

        assertSame("an unsatisfiable turn-in leaves reputation untouched", reputation, result.reputation)
    }

    // --- advance: a courier expiry costs reputation (AC#4) ---

    @Test
    fun `a courier expiry applies the courier-fail delta to its faction`() {
        val courier = leagueCourier(ticks = 1)
        val log = MissionLog(accepted = listOf(courier))

        val result =
            Missions.advance(
                log = log,
                credits = 1000L,
                cargo = Cargo.empty(),
                reputation = Reputation.EMPTY,
                reputationParams = params,
            )

        assertTrue("an expiry is an event", result.changed)
        assertEquals(MissionStatus.FAILED, result.log.accepted.single().status)
        assertEquals("the courier-fail delta (-15) is applied", -15, result.reputation.valueFor(league))
    }

    @Test
    fun `a courier-fail loss is clamped to the min bound`() {
        val courier = leagueCourier(ticks = 1)
        val log = MissionLog(accepted = listOf(courier))

        val result =
            Missions.advance(
                log = log,
                credits = 1000L,
                cargo = Cargo.empty(),
                // Already near the hostile floor: -15 clamps to -100, not -105.
                reputation = Reputation(mapOf(league to -90)),
                reputationParams = params,
            )

        assertEquals("the loss is clamped to the params' min bound", params.min, result.reputation.valueFor(league))
    }

    @Test
    fun `a plain courier decrement (no expiry) leaves reputation as the same instance`() {
        val courier = leagueCourier(ticks = 5)
        val log = MissionLog(accepted = listOf(courier))
        val reputation = Reputation(mapOf(league to 30))

        val result =
            Missions.advance(
                log = log,
                credits = 1000L,
                cargo = Cargo.empty(),
                reputation = reputation,
                reputationParams = params,
            )

        assertEquals("the timer ticked down", 4, result.log.accepted.single().remainingTicks)
        assertSame("a non-expiring tick never moves reputation (same instance)", reputation, result.reputation)
    }

    @Test
    fun `advance with no active courier returns the input reputation instance`() {
        val log = MissionLog(accepted = listOf(leagueMining().copy(status = MissionStatus.ACTIVE)))
        val reputation = Reputation(mapOf(league to 12))

        val result =
            Missions.advance(
                log = log,
                credits = 0L,
                cargo = Cargo.empty(),
                reputation = reputation,
                reputationParams = params,
            )

        assertSame("no courier ⇒ reputation is the same instance", reputation, result.reputation)
    }
}
