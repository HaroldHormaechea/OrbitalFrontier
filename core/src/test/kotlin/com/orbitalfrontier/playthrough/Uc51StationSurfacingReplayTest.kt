package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.station.OwnedStationProjection
import com.orbitalfrontier.station.StationFunction
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.world.MvpSectorMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC51 owned-station surfacing & dock-to-use playthrough (AC#3/#5), following the
 * record→replay→assert pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc51-owned-station` artifact founds a `commerce-hub-i` personal station at Alpha,
 * undocks, flies to that owned station's **derived placement** ([com.orbitalfrontier.station
 * .OwnedStationPlacement]), **docks at it** (proving the synthetic projection is dockable, AC#3), and
 * **sells** a resource at its reconstructed commerce desk (proving an owned module's function is usable
 * end-to-end, AC#5). This replays it headlessly and asserts:
 *  - the player owns exactly one station that offers COMMERCE (the build happened);
 *  - the run ends **docked at that owned station's synthetic projection id** (`owned-station-0`) — i.e. it
 *    really docked at the personal station, not at Alpha;
 *  - the dock-to-use **sale moved credits up and IRON_ORE down** (a module function actually used);
 *  - the replay is **bit-for-bit deterministic** across two runs (AC#5 determinism).
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc51OwnedStation] and guarded by
 * [PlaythroughFixtureTest]; it is the ONLY new-path fixture, so every existing replay stays byte-identical.
 */
class Uc51StationSurfacingReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC51_OWNED_STATION)

    private val ownedStation0 = OwnedStationProjection.poiIdFor(StationId(0))

    @Test
    fun `the run founds one commerce station and docks at its surfaced projection`() {
        // Precondition: the run starts owning no stations (the build EARNS ownership, it isn't seeded).
        val initial = load().initialState!!.toSimulationState()
        assertTrue("the run starts with no owned stations", initial.stations.isEmpty)

        val state = ReplayRunner().run(load()).finalState

        // Ownership: exactly one owned station, offering COMMERCE (the founded commerce hub).
        assertEquals("the player owns exactly one station", 1, state.stations.size)
        val station = state.stations.stations.single()
        assertEquals("the owned station is id 0", StationId(0), station.id)
        assertEquals("anchored in Alpha (the build sector)", MvpSectorMap.START_SECTOR, station.sector)
        assertTrue("the owned station offers COMMERCE (AC#3)", StationFunction.COMMERCE in station.availableFunctions())

        // AC#3: the run ends docked at the OWNED station's synthetic projection id — it flew to and docked
        // at the personal station (surfaced as a dockable Station), not at Alpha.
        assertEquals(
            "the ship must end docked at the surfaced owned-station projection (AC#3 dock-to-use)",
            ownedStation0,
            state.dockedStation,
        )
        assertEquals(MvpSectorMap.START_SECTOR, state.currentSector)
    }

    @Test
    fun `selling at the owned commerce desk moves credits up and cargo down (AC#5)`() {
        val state = ReplayRunner().run(load()).finalState

        // After founding the commerce hub (cost 1500 from 2000) the wallet is 500; the IRON_ORE sale at the
        // owned station's commerce desk then raises it. We assert the DIRECTION (a real transaction) rather
        // than a magic price, since the owned desk's effective price rides the dynamic-pricing tick.
        assertTrue(
            "credits must rise above the post-build 500 from the dock-to-use sale (the COMMERCE function was used)",
            state.credits > 500L,
        )

        // Started with IRON_ORE 30; the build spent 15 (→ 15), the sale spent UC51_SELL_UNITS (→ 5).
        val expectedIron = PlaythroughFixtures.UC51_STARTING_IRON_ORE - 15 - PlaythroughFixtures.UC51_SELL_UNITS
        assertEquals(
            "IRON_ORE must drop by the build bill plus the sale (cargo used at the commerce desk)",
            expectedIron,
            state.cargo.contents[ResourceType.IRON_ORE] ?: 0,
        )
    }

    @Test
    fun `replay through the build, flight, dock and sale is deterministic`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState
        // SimulationState data-class equality covers the registry, credits, cargo, dock state and kinematics.
        assertEquals(first, second)
    }
}
