package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure mission resolver (UC12 AC#3/#4/#6) — [Missions.resolve] (accept /
 * automatic courier pickup / turn-in) and [Missions.advance] (the per-tick courier timer).
 *
 * Every case is a pure function of its inputs (no engine types, no RNG, no clock), so the whole
 * mission system is JVM-unit-testable (AC#6). The no-op cases assert **reference equality** of the
 * returned log / credits / cargo, the contract the simulation relies on to skip an autosave and to
 * keep a held-while-docked stretch byte-identical.
 */
class MissionsTest {
    private val alphaStation = PoiId("alpha-station")
    private val betaStation = PoiId("beta-station")

    private fun miningOffer(
        id: String = "board:alpha-station:mining",
        resource: ResourceType = ResourceType.HYDROGEN,
        quota: Int = 8,
        reward: Long = 400,
    ): Mission =
        Mission(
            id = MissionId(id),
            type = MissionType.MINING,
            source = MissionSource.BOARD,
            status = MissionStatus.AVAILABLE,
            rewardCredits = reward,
            quotaResource = resource,
            quotaUnits = quota,
        )

    private fun courierOffer(
        id: String = "board:alpha-station:courier",
        pickup: PoiId = alphaStation,
        destination: PoiId = betaStation,
        ticks: Int = 100,
        reward: Long = 535,
    ): Mission =
        Mission(
            id = MissionId(id),
            type = MissionType.COURIER,
            source = MissionSource.BOARD,
            status = MissionStatus.AVAILABLE,
            rewardCredits = reward,
            pickup = pickup,
            destination = destination,
            remainingTicks = ticks,
        )

    // --- Accept (AC#3) ---

    @Test
    fun `accepting a surfaced board offer moves it into the log as ACTIVE`() {
        val offer = miningOffer()
        val result =
            Missions.resolve(
                log = MissionLog.EMPTY,
                offers = listOf(offer),
                order = MissionOrder.Accept(offer.id),
                dockedStation = alphaStation,
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertTrue("accepting an offer is an event", result.changed)
        assertEquals(1, result.log.accepted.size)
        val accepted = result.log.accepted.single()
        assertEquals(offer.id, accepted.id)
        assertEquals(MissionStatus.ACTIVE, accepted.status)
        assertEquals("accept does not touch the wallet", 0L, result.credits)
    }

    @Test
    fun `accepting an id that is not among the offers is a no-op returning the same instances`() {
        val log = MissionLog.EMPTY
        val cargo = Cargo.empty()
        val result =
            Missions.resolve(
                log = log,
                offers = listOf(miningOffer()),
                order = MissionOrder.Accept(MissionId("radio:beta-station")),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 50L,
            )

        assertFalse(result.changed)
        assertSame("an unsatisfiable accept returns the same log", log, result.log)
        assertSame("…and the same cargo", cargo, result.cargo)
        assertEquals(50L, result.credits)
    }

    @Test
    fun `accepting an already-taken id does not duplicate it`() {
        val offer = miningOffer()
        val log = MissionLog(accepted = listOf(offer.copy(status = MissionStatus.ACTIVE)))
        val result =
            Missions.resolve(
                log = log,
                offers = listOf(offer),
                order = MissionOrder.Accept(offer.id),
                dockedStation = alphaStation,
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertFalse("an already-taken offer is not re-accepted", result.changed)
        assertEquals(1, result.log.accepted.size)
    }

    // --- Mining turn-in (AC#4) ---

    @Test
    fun `turning in a mining mission with the quota in the hold completes it, grants credits, and consumes the quota`() {
        val mission = miningOffer(quota = 8, reward = 400).copy(status = MissionStatus.ACTIVE)
        val log = MissionLog(accepted = listOf(mission))
        // Carry exactly the quota plus a little extra of another resource to prove only the quota is taken.
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 10, ResourceType.IRON_ORE to 3), Cargo.DEFAULT_CAPACITY)

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 100L,
            )

        assertTrue(result.changed)
        assertEquals("the reward is added to the wallet", 500L, result.credits)
        assertEquals("only the quota is consumed", 2, result.cargo.contents[ResourceType.HYDROGEN])
        assertEquals("unrelated cargo is untouched", 3, result.cargo.contents[ResourceType.IRON_ORE])
        assertEquals(MissionStatus.COMPLETED, result.log.accepted.single().status)
    }

    @Test
    fun `turning in a mining mission without the full quota is a no-op returning the same instances`() {
        val mission = miningOffer(quota = 8).copy(status = MissionStatus.ACTIVE)
        val log = MissionLog(accepted = listOf(mission))
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 7), Cargo.DEFAULT_CAPACITY)

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = alphaStation,
                cargo = cargo,
                credits = 100L,
            )

        assertFalse("a quota shortfall cannot complete the mission", result.changed)
        assertSame(log, result.log)
        assertSame(cargo, result.cargo)
        assertEquals(100L, result.credits)
    }

    @Test
    fun `a mining turn-in while in flight (not docked) is a no-op`() {
        val mission = miningOffer(quota = 8).copy(status = MissionStatus.ACTIVE)
        val log = MissionLog(accepted = listOf(mission))
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 20), Cargo.DEFAULT_CAPACITY)

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(mission.id),
                dockedStation = null,
                cargo = cargo,
                credits = 0L,
            )

        assertFalse("mining turn-in requires a mission-board station", result.changed)
        assertSame(log, result.log)
    }

    // --- Courier auto-pickup + delivery (AC#1/#4) ---

    @Test
    fun `docking at the pickup station auto-loads the courier parcel`() {
        val courier = courierOffer().copy(status = MissionStatus.ACTIVE, pickedUp = false)
        val log = MissionLog(accepted = listOf(courier))

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.None,
                dockedStation = alphaStation, // the pickup station
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertTrue("auto-pickup is an event worth an autosave", result.changed)
        assertTrue("the parcel is now aboard", result.log.accepted.single().pickedUp)
    }

    @Test
    fun `delivering a picked-up courier at its destination completes it and grants the reward`() {
        val courier = courierOffer(reward = 535).copy(status = MissionStatus.ACTIVE, pickedUp = true)
        val log = MissionLog(accepted = listOf(courier))

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(courier.id),
                dockedStation = betaStation, // the destination
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertTrue(result.changed)
        assertEquals(535L, result.credits)
        assertEquals(MissionStatus.COMPLETED, result.log.accepted.single().status)
    }

    @Test
    fun `delivering a courier that was never picked up is a no-op`() {
        val courier = courierOffer().copy(status = MissionStatus.ACTIVE, pickedUp = false)
        val log = MissionLog(accepted = listOf(courier))

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.TurnIn(courier.id),
                dockedStation = betaStation,
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertFalse("a parcel never collected cannot be delivered", result.changed)
        assertSame(log, result.log)
    }

    // --- advance: courier timer (AC#4) ---

    @Test
    fun `advance decrements an active courier timer without reporting an event`() {
        val courier = courierOffer(ticks = 3).copy(status = MissionStatus.ACTIVE)
        val log = MissionLog(accepted = listOf(courier))

        val result = Missions.advance(log, credits = 0L, cargo = Cargo.empty())

        assertFalse("a plain decrement is not an event (keeps autosave I/O off the frame budget)", result.changed)
        assertEquals(2, result.log.accepted.single().remainingTicks)
        assertEquals(MissionStatus.ACTIVE, result.log.accepted.single().status)
    }

    @Test
    fun `advance flips a courier to FAILED at zero ticks and applies the penalty`() {
        val courier = courierOffer(ticks = 1).copy(status = MissionStatus.ACTIVE)
        val log = MissionLog(accepted = listOf(courier))

        val result = Missions.advance(log, credits = 500L, cargo = Cargo.empty(), params = MissionParams())

        assertTrue("an expiry is a terminal transition worth an autosave", result.changed)
        val failed = result.log.accepted.single()
        assertEquals(MissionStatus.FAILED, failed.status)
        assertEquals(0, failed.remainingTicks)
        assertEquals("the fixed failure penalty is deducted", 500L - MissionParams().courierFailurePenalty, result.credits)
    }

    @Test
    fun `advance never drives the wallet negative on a penalty`() {
        val courier = courierOffer(ticks = 1).copy(status = MissionStatus.ACTIVE)
        val log = MissionLog(accepted = listOf(courier))

        val result = Missions.advance(log, credits = 50L, cargo = Cargo.empty())

        assertEquals("the penalty is clamped at a zero balance", 0L, result.credits)
    }

    @Test
    fun `advance with no active courier returns the same instances`() {
        val log = MissionLog(accepted = listOf(miningOffer().copy(status = MissionStatus.ACTIVE)))
        val cargo = Cargo.empty()

        val result = Missions.advance(log, credits = 77L, cargo = cargo)

        assertFalse(result.changed)
        assertSame("no courier ⇒ the log instance is unchanged", log, result.log)
        assertSame(cargo, result.cargo)
        assertEquals(77L, result.credits)
    }

    @Test
    fun `resolve with None on an empty log returns the same instances`() {
        val log = MissionLog.EMPTY
        val cargo = Cargo.empty()

        val result =
            Missions.resolve(
                log = log,
                offers = emptyList(),
                order = MissionOrder.None,
                dockedStation = null,
                cargo = cargo,
                credits = 0L,
            )

        assertFalse(result.changed)
        assertSame(log, result.log)
        assertSame(cargo, result.cargo)
    }
}
