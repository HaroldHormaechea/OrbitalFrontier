package com.orbitalfrontier.mission

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Golden-value + determinism tests for the procedural [MissionGenerator] (UC12 AC#1/#2/#6).
 *
 * Mission *content* is procedurally instanced from the static authored [MvpSectorMap] via the
 * string-hash → LCG generator (ADR 0011). Because the world and params are fixed, every drawn value
 * is a **constant** — so these tests pin the exact offer the generator produces for Alpha Station's
 * board and radio. If the generation algorithm ever drifts (a different hash, a reordered candidate
 * list, an ordinal/​hashCode slip), one of these literals breaks — which is the whole point: the
 * regenerate-and-filter invariant rests on generation being a stable pure function of static state.
 *
 * The literals here are the canonical UC12 golden offers (see the developer's DEV CONSTANTS):
 *  - Alpha board MINING: 8 HYDROGEN, reward 400.
 *  - Alpha board COURIER: alpha-station → beta-station, 156 ticks, reward 535.
 *  - Alpha radio MINING: 13 WATER_ICE, reward 525.
 */
class MissionGeneratorTest {
    private val world = MvpSectorMap.build()
    private val alphaStation = PoiId("alpha-station")
    private val betaStation = PoiId("beta-station")

    @Test
    fun `alpha-station board surfaces one mining and one courier offer`() {
        val offers = MissionGenerator.boardOffers(world, alphaStation)

        assertEquals("a board surfaces exactly one mining + one courier offer", 2, offers.size)
        assertEquals(setOf(MissionType.MINING, MissionType.COURIER), offers.map { it.type }.toSet())
        offers.forEach { assertEquals("board offers are AVAILABLE until accepted", MissionStatus.AVAILABLE, it.status) }
        offers.forEach { assertEquals(MissionSource.BOARD, it.source) }
    }

    @Test
    fun `the alpha-station board mining offer is the golden 8 HYDROGEN for 400 credits`() {
        val mining = MissionGenerator.boardOffers(world, alphaStation).single { it.type == MissionType.MINING }

        assertEquals(MissionId("board:alpha-station:mining"), mining.id)
        assertEquals(ResourceType.HYDROGEN, mining.quotaResource)
        assertEquals(8, mining.quotaUnits)
        assertEquals(400L, mining.rewardCredits)
    }

    @Test
    fun `the alpha-station board courier offer is the golden alpha to beta, 156 ticks, for 535 credits`() {
        val courier = MissionGenerator.boardOffers(world, alphaStation).single { it.type == MissionType.COURIER }

        assertEquals(MissionId("board:alpha-station:courier"), courier.id)
        assertEquals(alphaStation, courier.pickup)
        assertEquals(betaStation, courier.destination)
        assertEquals(156, courier.remainingTicks)
        assertEquals(535L, courier.rewardCredits)
        assertEquals("a fresh courier offer is not yet picked up", false, courier.pickedUp)
    }

    @Test
    fun `the alpha-station radio broadcast offer is the golden 13 WATER_ICE for 525 credits`() {
        // In flight within radio range of Alpha Station (origin sector; the only station in range).
        val radio = MissionGenerator.radioOffers(world, MvpSectorMap.START_SECTOR, Vec2(0f, 600f))

        assertEquals("one radio mining offer per in-range station", 1, radio.size)
        val offer = radio.single()
        assertEquals(MissionId("radio:alpha-station"), offer.id)
        assertEquals(MissionSource.RADIO, offer.source)
        assertEquals(MissionType.MINING, offer.type)
        assertEquals(ResourceType.WATER_ICE, offer.quotaResource)
        assertEquals(13, offer.quotaUnits)
        assertEquals(525L, offer.rewardCredits)
    }

    @Test
    fun `generating the same world twice yields identical offers (determinism)`() {
        val first = MissionGenerator.boardOffers(MvpSectorMap.build(), alphaStation)
        val second = MissionGenerator.boardOffers(MvpSectorMap.build(), alphaStation)
        assertEquals("a board generated from the same world twice is identical", first, second)

        val firstRadio = MissionGenerator.radioOffers(MvpSectorMap.build(), MvpSectorMap.START_SECTOR, Vec2(0f, 600f))
        val secondRadio = MissionGenerator.radioOffers(MvpSectorMap.build(), MvpSectorMap.START_SECTOR, Vec2(0f, 600f))
        assertEquals("radio offers generated from the same world twice are identical", firstRadio, secondRadio)
    }

    @Test
    fun `an unknown station id produces no board offers`() {
        assertEquals(emptyList<Mission>(), MissionGenerator.boardOffers(world, PoiId("no-such-station")))
    }

    @Test
    fun `the mining quota never exceeds what the sector field can supply`() {
        // The golden 8-unit Hydrogen quota is well under the alpha-belt's 20 Hydrogen, but assert the
        // clamp invariant generally: the quota is at least 1 and at most the field's stock of that resource.
        val mining = MissionGenerator.boardOffers(world, alphaStation).single { it.type == MissionType.MINING }
        val available =
            world
                .sector(MvpSectorMap.START_SECTOR)
                .asteroidFields
                .flatMap { it.deposits.entries }
                .filter { it.key == mining.quotaResource }
                .sumOf { it.value }
        assertNotNull(mining.quotaResource)
        assertEquals("the quota is completable from the sector's fields", true, mining.quotaUnits in 1..available)
    }
}
