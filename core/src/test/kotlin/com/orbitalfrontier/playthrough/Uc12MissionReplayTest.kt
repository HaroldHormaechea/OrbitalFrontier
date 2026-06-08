package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC12 mission playthrough (AC#7), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc12-mission` artifact runs the full mission loop: starting docked at Alpha Station it
 * **accepts** the golden board mining mission (`board:alpha-station:mining` — 8 HYDROGEN for 400
 * credits), flies out to the alpha-belt and **mines** the quota (and then some), flies back, docks, and
 * **turns the mission in**. This test replays it headlessly and asserts the AC#7 contract:
 *  - the accepted mission ends **COMPLETED**;
 *  - the wallet has risen by **exactly the 400-credit reward** (it started at 0);
 *  - the 8-unit Hydrogen quota was **consumed** from the hold at turn-in (the run over-mines the full
 *    20-unit Hydrogen deposit, so 12 remain afterwards — proving the quota, not the whole hold, is taken);
 *  - the turn-in happened **while docked** at the station;
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc12Mission] and guarded by [PlaythroughFixtureTest];
 * it is also loadable via the `playtest` skill (`-Dplaythrough.name=uc12-mission`).
 */
class Uc12MissionReplayTest {
    private val missionId = MissionId("board:alpha-station:mining")

    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC12_MISSION)

    @Test
    fun `accepting, mining, and turning in the board mission completes it and pays the 400-credit reward`() {
        val state = ReplayRunner().run(load()).finalState

        val mission = state.missions.accepted.single { it.id == missionId }
        assertEquals("the mission is the board mining mission", MissionType.MINING, mission.type)
        assertEquals("the mission ends COMPLETED (AC#7)", MissionStatus.COMPLETED, mission.status)

        // AC#7: completing the mission pays the reward. The wallet started at 0, so it equals the reward.
        assertEquals("the 400-credit reward is paid out", 400L, state.credits)
        assertEquals("the mission's reward is 400 credits", 400L, mission.rewardCredits)

        // The 8-unit quota is consumed from the hold (not the whole hold): the run mined the full 20-unit
        // Hydrogen deposit, so 20 − 8 = 12 remain after the turn-in.
        assertEquals("the 8-unit quota was consumed, leaving 12 of the 20 mined Hydrogen", 12, state.cargo.contents[ResourceType.HYDROGEN])

        // The turn-in happened while docked back at the station.
        assertEquals("the ship turned the mission in while docked", PoiId("alpha-station"), state.dockedStation)
    }

    @Test
    fun `replay through the full mission loop is deterministic`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState

        // SimulationState data-class equality covers the mission log, cargo, credits and kinematics.
        assertEquals(first, second)
    }

    @Test
    fun `the run mines at least the quota before turning in`() {
        // Sanity: the hold still carries Hydrogen after the quota was consumed, so the run genuinely mined
        // past the 8-unit requirement rather than scraping exactly the quota.
        val state = ReplayRunner().run(load()).finalState
        assertTrue("the run over-mined the quota", (state.cargo.contents[ResourceType.HYDROGEN] ?: 0) >= 8)
    }
}
