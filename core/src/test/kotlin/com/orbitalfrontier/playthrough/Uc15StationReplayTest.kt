package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.station.StationFunction
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.world.MvpSectorMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC15 station-building playthrough (AC#6), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc15-station` artifact starts docked at Alpha Station (the build-capable MVP station) with
 * credits + mined resources and founds a `commerce-hub-i` station in one tick. This test replays it
 * headlessly and asserts the AC#6 contract:
 *  - the player owns **exactly one** station after the build (AC#1/#3);
 *  - that station's [com.orbitalfrontier.station.OwnedStation.availableFunctions] contains **COMMERCE**
 *    (AC#2/#6) — the module's function is available;
 *  - the build cost was deducted (credits + the IRON_ORE/SILICON resource bill);
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc15Station] and guarded by [PlaythroughFixtureTest];
 * it is also loadable via the `playtest` skill (`-Dplaythrough.name=uc15-station`).
 */
class Uc15StationReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC15_STATION)

    @Test
    fun `founding a commerce hub yields one owned station that offers COMMERCE`() {
        // Precondition: the run starts owning no stations (the build EARNS ownership, it isn't seeded).
        val initial = load().initialState!!.toSimulationState()
        assertTrue("the run starts with no owned stations", initial.stations.isEmpty)

        val state = ReplayRunner().run(load()).finalState

        // AC#1/#3: exactly one owned station after the build.
        assertEquals("the player owns exactly one station (AC#1/#3)", 1, state.stations.size)

        val station = state.stations.stations.single()
        // AC#2/#6: the built module's COMMERCE function is available.
        assertTrue(
            "(AC#2/#6) the owned station offers COMMERCE",
            StationFunction.COMMERCE in station.availableFunctions(),
        )
        assertEquals("the station is anchored in the start sector (Alpha)", MvpSectorMap.START_SECTOR, station.sector)
        assertEquals("the commerce hub sits in slot 0", StationModuleCatalog.COMMERCE_HUB, station.moduleAt(0))
    }

    @Test
    fun `the build deducts the commerce hub cost from credits and the hold`() {
        val state = ReplayRunner().run(load()).finalState

        // Started with 2000 credits; commerce-hub-i costs 1500 → 500 remain.
        assertEquals("credits deducted (2000 - 1500)", 500L, state.credits)
        // Started with IRON_ORE 20 / SILICON 12; the hub costs IRON_ORE 15 / SILICON 8.
        assertEquals("IRON_ORE deducted (20 - 15)", 5, state.cargo.contents[ResourceType.IRON_ORE])
        assertEquals("SILICON deducted (12 - 8)", 4, state.cargo.contents[ResourceType.SILICON])
    }

    @Test
    fun `replay through the station build is deterministic`() {
        val first = ReplayRunner().run(load()).finalState
        val second = ReplayRunner().run(load()).finalState

        // SimulationState data-class equality covers the station registry, credits, cargo and kinematics.
        assertEquals(first, second)
    }

    /**
     * AC#4 backward-compatibility (required): adding the UC15 `stations` snapshot field must NOT perturb an
     * existing pre-UC15 artifact. The `uc14-faction` committed JSON must (a) still decode to exactly its
     * builder (re-encode == committed text — the byte-identical contract), and (b) carry **no** `stations`
     * field at all (the empty registry is omitted via `@EncodeDefault(NEVER)`), so the artifact's bytes are
     * unchanged by this use case. This complements the all-fixtures guard in [PlaythroughFixtureTest].
     */
    @Test
    fun `a pre-UC15 artifact (uc14-faction) stays byte-identical and omits the stations field`() {
        val committedText = readCommittedJson(PlaythroughFixtures.UC14_FACTION)

        // (a) the committed text is exactly what the (unchanged) builder still emits — byte-identical.
        assertEquals(
            "uc14-faction must re-encode byte-identically after the UC15 stations field was added",
            PlaythroughCodec.encode(PlaythroughFixtures.uc14Faction()),
            committedText.trimEnd('\n'),
        )
        // (b) the artifact carries no stations field (empty registry omitted on disk).
        assertTrue(
            "a pre-UC15 artifact must not carry a stations field",
            !committedText.contains("\"stations\""),
        )
        // …and it still replays (decode succeeds and round-trips to the builder).
        assertEquals(PlaythroughFixtures.uc14Faction(), PlaythroughResources.load(PlaythroughFixtures.UC14_FACTION))
    }

    private fun readCommittedJson(name: String): String =
        PlaythroughResources::class.java.classLoader
            .getResourceAsStream(PlaythroughResources.resourcePath(name))!!
            .bufferedReader()
            .use { it.readText() }
}
