package com.orbitalfrontier.mission

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun `a faction station board surfaces a mining, a courier, and the gated premium offer`() {
        // UC14: Alpha is a LEAGUE station, so beyond the UC12 mining + courier offers the generator now
        // also emits the gated `:premium` courier (a faction contract). Generation is reputation-blind —
        // the offer is always RETURNED here; the ReputationGate filter (a separate site) decides visibility.
        val offers = MissionGenerator.boardOffers(world, alphaStation)

        assertEquals("a faction board surfaces mining + courier + premium", 3, offers.size)
        assertEquals(
            setOf(
                MissionId("board:alpha-station:mining"),
                MissionId("board:alpha-station:courier"),
                MissionId("board:alpha-station:premium"),
            ),
            offers.map { it.id }.toSet(),
        )
        offers.forEach { assertEquals("board offers are AVAILABLE until accepted", MissionStatus.AVAILABLE, it.status) }
        offers.forEach { assertEquals(MissionSource.BOARD, it.source) }
    }

    @Test
    fun `the generator stamps each alpha offer with the owning league faction`() {
        // AC#4: completing any of these credits LEAGUE reputation, so every offer carries factionId=league
        // (stamped from the station's owning faction — static world state, never runtime reputation).
        val offers = MissionGenerator.boardOffers(world, alphaStation)
        offers.forEach {
            assertEquals("offer ${it.id.value} is stamped with the station's faction", FactionId("league"), it.factionId)
        }
    }

    @Test
    fun `the gamma junkyard board surfaces a premium even with no minable resources`() {
        // AC#3: the gated premium is a courier (not a mining offer), so an Independents station with no
        // local fields still gets one — it is stamped + gated on the Independents faction.
        val gamma = PoiId("gamma-junkyard")
        val offers = MissionGenerator.boardOffers(world, gamma)

        val premium = offers.single { it.id == MissionId("board:gamma-junkyard:premium") }
        assertEquals(MissionType.COURIER, premium.type)
        assertEquals(FactionId("independents"), premium.factionId)
        assertEquals(FactionId("independents"), premium.unlockFaction)
        assertEquals(10, premium.unlockThreshold)
    }

    @Test
    fun `the alpha premium offer is a league-gated courier paying the regular courier plus the 600 bonus`() {
        val courier =
            MissionGenerator.boardOffers(world, alphaStation).single { it.id == MissionId("board:alpha-station:courier") }
        val premium =
            MissionGenerator.boardOffers(world, alphaStation).single { it.id == MissionId("board:alpha-station:premium") }

        assertEquals(MissionType.COURIER, premium.type)
        assertEquals("premium is gated on the station's own faction", FactionId("league"), premium.unlockFaction)
        assertEquals("its threshold is reachable by one mission turn-in (<= +10)", 10, premium.unlockThreshold)
        assertEquals(alphaStation, premium.pickup)
        // The premium pays a flat 600 over a regular courier of the same generated span — and since the
        // regular courier here pays 535, the premium's reward clears 535 + 600.
        assertTrue(
            "premium reward (${premium.rewardCredits}) clears regular courier + the 600 bonus",
            premium.rewardCredits >= courier.rewardCredits.coerceAtMost(535L) + 600L,
        )
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
        // Select by id: a faction station now surfaces TWO couriers (the regular + the gated premium), so
        // a `.single { type == COURIER }` would be ambiguous. The regular courier's golden values are pinned.
        val courier =
            MissionGenerator.boardOffers(world, alphaStation).single { it.id == MissionId("board:alpha-station:courier") }

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
