package com.orbitalfrontier.mission

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure bounty-kill resolver (UC41 AC#3) — [BountyTracking.applyKills], the
 * combat-mission analogue of [Missions.resolve]/[Missions.advance].
 *
 * Every case is a pure function of its inputs (no engine types, no RNG, no clock), so the bounty system
 * is JVM-unit-testable. The no-op cases assert **reference equality** of the returned log / credits /
 * cargo / reputation — the same-instance contract the simulation relies on to skip an autosave and keep
 * a non-bounty tick byte-identical.
 */
class BountyTrackingTest {
    private val zoneId = "bounty-alpha-raider"

    private fun activeBounty(
        target: Int = 1,
        progress: Int = 0,
        reward: Long = 800,
        zone: String = zoneId,
        status: MissionStatus = MissionStatus.ACTIVE,
    ): Mission =
        Mission(
            id = MissionId("bounty:$zone"),
            type = MissionType.BOUNTY,
            source = MissionSource.BOARD,
            status = status,
            rewardCredits = reward,
            targetZoneId = zone,
            killTarget = target,
            killProgress = progress,
            factionId = FactionId("league"),
        )

    private fun logOf(vararg missions: Mission): MissionLog = MissionLog(accepted = missions.toList())

    // --- Completion + payout (AC#3) ----------------------------------------------------------------

    @Test
    fun `the final kill auto-completes the bounty and pays the reward`() {
        val log = logOf(activeBounty(target = 1, progress = 0, reward = 800))
        val result = BountyTracking.applyKills(log, zoneId, kills = 1, credits = 100, cargo = Cargo.empty())

        assertTrue("a matching kill is event-worthy", result.changed)
        val bounty = result.log.accepted.single()
        assertEquals(MissionStatus.COMPLETED, bounty.status)
        assertEquals("kill progress reaches the target", 1, bounty.killProgress)
        assertEquals("the reward is added to the wallet", 900, result.credits)
    }

    @Test
    fun `a non-final kill advances progress and keeps the bounty ACTIVE without paying`() {
        val log = logOf(activeBounty(target = 3, progress = 0, reward = 800))
        val result = BountyTracking.applyKills(log, zoneId, kills = 1, credits = 100, cargo = Cargo.empty())

        assertTrue(result.changed)
        val bounty = result.log.accepted.single()
        assertEquals(MissionStatus.ACTIVE, bounty.status)
        assertEquals("progress advances by the kill count", 1, bounty.killProgress)
        assertEquals("no reward until the quota is met", 100, result.credits)
    }

    @Test
    fun `killProgress is capped at killTarget on an over-kill`() {
        val log = logOf(activeBounty(target = 2, progress = 0, reward = 800))
        // Three kills land in one fold but the quota is 2 — progress caps at 2 and pays exactly once.
        val result = BountyTracking.applyKills(log, zoneId, kills = 3, credits = 0, cargo = Cargo.empty())

        val bounty = result.log.accepted.single()
        assertEquals(MissionStatus.COMPLETED, bounty.status)
        assertEquals("progress never exceeds the target", 2, bounty.killProgress)
        assertEquals("the reward is paid once", 800, result.credits)
    }

    @Test
    fun `multiple kills crossing the threshold in one call complete the bounty`() {
        val log = logOf(activeBounty(target = 3, progress = 1, reward = 500))
        val result = BountyTracking.applyKills(log, zoneId, kills = 2, credits = 50, cargo = Cargo.empty())

        val bounty = result.log.accepted.single()
        assertEquals(MissionStatus.COMPLETED, bounty.status)
        assertEquals(3, bounty.killProgress)
        assertEquals(550, result.credits)
    }

    // --- No-ops return the same instances ----------------------------------------------------------

    @Test
    fun `zero kills is a same-instance no-op`() {
        val log = logOf(activeBounty())
        val cargo = Cargo.empty()
        val rep = Reputation.EMPTY
        val result = BountyTracking.applyKills(log, zoneId, kills = 0, credits = 100, cargo = cargo, reputation = rep)

        assertTrue("nothing happened", !result.changed)
        assertSame("the same log instance is returned", log, result.log)
        assertSame("the same cargo instance is returned", cargo, result.cargo)
        assertSame("the same reputation instance is returned", rep, result.reputation)
        assertEquals(100, result.credits)
    }

    @Test
    fun `a kill in a different zone does not touch the bounty`() {
        val log = logOf(activeBounty(zone = zoneId))
        val result = BountyTracking.applyKills(log, "some-other-zone", kills = 1, credits = 100, cargo = Cargo.empty())

        assertTrue(!result.changed)
        assertSame(log, result.log)
        assertEquals(100, result.credits)
    }

    @Test
    fun `an empty zone id is a no-op`() {
        val log = logOf(activeBounty())
        val result = BountyTracking.applyKills(log, "", kills = 1, credits = 100, cargo = Cargo.empty())

        assertTrue(!result.changed)
        assertSame(log, result.log)
    }

    @Test
    fun `a non-ACTIVE bounty in the matching zone is not advanced`() {
        // An already-COMPLETED bounty (killProgress already at target) is left alone.
        val log = logOf(activeBounty(target = 1, progress = 1, status = MissionStatus.COMPLETED))
        val result = BountyTracking.applyKills(log, zoneId, kills = 1, credits = 100, cargo = Cargo.empty())

        assertTrue(!result.changed)
        assertSame(log, result.log)
    }

    @Test
    fun `a non-bounty mission in the log is never affected`() {
        val mining =
            Mission(
                id = MissionId("board:alpha-station:mining"),
                type = MissionType.MINING,
                source = MissionSource.BOARD,
                status = MissionStatus.ACTIVE,
                rewardCredits = 400,
                quotaResource = ResourceType.HYDROGEN,
                quotaUnits = 8,
            )
        val result = BountyTracking.applyKills(MissionLog(accepted = listOf(mining)), zoneId, 1, 100, Cargo.empty())

        assertTrue("no bounty matched", !result.changed)
        assertEquals(MissionStatus.ACTIVE, result.log.accepted.single().status)
    }

    // --- Reputation + cargo are threaded UNCHANGED (the UC43 seam) ---------------------------------

    @Test
    fun `a bounty completion threads reputation through UNCHANGED (UC43 seam)`() {
        // A non-neutral standing is supplied; a bounty grants no reputation today, so the SAME instance comes back.
        val rep = Reputation.EMPTY.with(FactionId("league"), 25, -100, 100)
        val log = logOf(activeBounty(target = 1, progress = 0))
        val result = BountyTracking.applyKills(log, zoneId, kills = 1, credits = 0, cargo = Cargo.empty(), reputation = rep)

        assertTrue("the bounty completed", result.log.accepted.single().status == MissionStatus.COMPLETED)
        assertSame("reputation is the same instance (no standing granted yet)", rep, result.reputation)
        assertEquals("standing is unchanged", 25, result.reputation.valueFor(FactionId("league")))
    }

    @Test
    fun `cargo is threaded through unchanged on a completion (a bounty grants no cargo)`() {
        val cargo = Cargo(mapOf(ResourceType.IRON_ORE to 5), Cargo.DEFAULT_CAPACITY)
        val log = logOf(activeBounty(target = 1, progress = 0))
        val result = BountyTracking.applyKills(log, zoneId, kills = 1, credits = 0, cargo = cargo)

        assertSame("the same cargo instance is returned", cargo, result.cargo)
    }
}
