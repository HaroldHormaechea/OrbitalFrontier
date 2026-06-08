package com.orbitalfrontier.playthrough

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.ReputationGate
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.mission.MissionGenerator
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC14 factions & reputation playthrough (AC#6), following the record→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc14-faction` artifact runs the faction-mission loop: starting docked at Alpha Station
 * (a LEAGUE station) with **neutral reputation**, it accepts the board mining mission
 * (`board:alpha-station:mining`, a LEAGUE contract), mines the 8-Hydrogen quota, flies back, docks, and
 * turns it in. This test replays it headlessly and asserts the AC#6 contract end-to-end:
 *  - **(a)** the gated `board:alpha-station:premium` offer is **ABSENT** at the start (reputation 0);
 *  - **(b)** the mission ends **COMPLETED** while docked at Alpha;
 *  - **(c)** LEAGUE reputation rose by **exactly the mission-complete delta (+10)**;
 *  - **(d)** re-running the generator + [ReputationGate] against the final state now surfaces the
 *    premium offer (a **newly available gated offer**);
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * The premium-availability checks call the SAME generator + gate the live game and the simulation run, so
 * the test exercises the real gating path, not a hand-rolled stand-in. The artifact is reproduced from
 * [PlaythroughFixtures.uc14Faction] and guarded by [PlaythroughFixtureTest]; it is also loadable via the
 * `playtest` skill (`-Dplaythrough.name=uc14-faction`).
 */
class Uc14FactionReplayTest {
    private val world = MvpSectorMap.build()
    private val alphaStation = PoiId("alpha-station")
    private val league = FactionId("league")
    private val miningId = MissionId("board:alpha-station:mining")
    private val premiumId = MissionId("board:alpha-station:premium")

    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC14_FACTION)

    /** The board offers visible at [station] for a player with [reputation] — the real generate + gate path. */
    private fun visibleBoardOffers(
        station: PoiId,
        reputation: com.orbitalfrontier.faction.Reputation,
    ) = MissionGenerator
        .boardOffers(world, station)
        .filter { ReputationGate.isAvailable(it, reputation) }

    @Test
    fun `completing the league mission unlocks the premium offer and grants exactly +10 reputation`() {
        // (a) Before the run — neutral reputation: the premium offer is generated but gated OUT.
        val initial = load().initialState!!.toSimulationState()
        assertEquals("the run starts neutral", 0, initial.reputation.valueFor(league))
        assertFalse(
            "(a) the premium offer is absent at rep 0",
            visibleBoardOffers(alphaStation, initial.reputation).any { it.id == premiumId },
        )

        val state = ReplayRunner().run(load()).finalState

        // (b) the league mission ends COMPLETED, while docked at Alpha.
        val mission = state.missions.accepted.single { it.id == miningId }
        assertEquals("(b) the mission ends COMPLETED", MissionStatus.COMPLETED, mission.status)
        assertEquals("the completed mission is a LEAGUE contract", league, mission.factionId)
        assertEquals("the turn-in happened while docked at Alpha", alphaStation, state.dockedStation)

        // (c) LEAGUE reputation rose by exactly the mission-complete delta (+10).
        assertEquals(
            "(c) league reputation == missionCompleteDelta",
            ReputationParams().missionCompleteDelta,
            state.reputation.valueFor(league),
        )
        assertEquals("the delta is exactly +10", 10, state.reputation.valueFor(league))

        // (d) re-running the generator + gate against the final state now surfaces the premium offer.
        assertTrue(
            "(d) the premium offer is now available after the reputation gain",
            visibleBoardOffers(alphaStation, state.reputation).any { it.id == premiumId },
        )
    }

    @Test
    fun `the premium offer's gate flips precisely on the earned reputation`() {
        val state = ReplayRunner().run(load()).finalState
        // The threshold the generator stamped, vs. the standing the run earned: the earned value must
        // reach the threshold (that is the whole AC#6 mechanism).
        val premium = MissionGenerator.boardOffers(world, alphaStation).single { it.id == premiumId }
        assertTrue(
            "earned standing (${state.reputation.valueFor(league)}) >= premium threshold (${premium.unlockThreshold})",
            state.reputation.valueFor(league) >= premium.unlockThreshold,
        )
    }

    @Test
    fun `replay through the faction loop is deterministic`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState

        // SimulationState data-class equality covers the mission log, reputation, cargo, credits and kinematics.
        assertEquals(first, second)
    }
}
