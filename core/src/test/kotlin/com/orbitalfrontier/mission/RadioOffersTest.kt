package com.orbitalfrontier.mission

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.world.MvpSectorMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Range-boundary + in-flight-accept tests for ship-radio mission broadcasts (UC12 AC#2/#3).
 *
 * A radio mining offer surfaces only within [MissionParams.radioRange] (default 700 wu) of its source
 * station — the comms analogue of the scanning range gate. These tests pin the in-range / out-of-range
 * boundary against Alpha Station (at `(0, 600)`) and prove a surfaced radio offer can be accepted
 * **while in flight** (no docking required — radio is the in-flight offer channel).
 */
class RadioOffersTest {
    private val world = MvpSectorMap.build()
    private val alpha = MvpSectorMap.START_SECTOR
    private val alphaStationPos = Vec2(0f, 600f)
    private val radioRange = MissionParams().radioRange // 700

    @Test
    fun `a ship inside radio range surfaces the broadcast`() {
        val offers = MissionGenerator.radioOffers(world, alpha, alphaStationPos)
        assertEquals(1, offers.size)
        assertEquals(MissionId("radio:alpha-station"), offers.single().id)
    }

    @Test
    fun `a ship exactly at the radio range boundary still surfaces the broadcast`() {
        // Exactly radioRange away from Alpha Station: the gate is inclusive (<= range).
        val atBoundary = alphaStationPos + Vec2(radioRange, 0f)
        val offers = MissionGenerator.radioOffers(world, alpha, atBoundary)
        assertEquals("the boundary is in range (<=)", 1, offers.size)
    }

    @Test
    fun `a ship just beyond the radio range hears nothing`() {
        val justOutside = alphaStationPos + Vec2(radioRange + 1f, 0f)
        val offers = MissionGenerator.radioOffers(world, alpha, justOutside)
        assertTrue("out of range ⇒ no broadcast", offers.isEmpty())
    }

    @Test
    fun `a radio offer can be accepted while in flight (not docked)`() {
        val offers = MissionGenerator.radioOffers(world, alpha, alphaStationPos)
        val offer = offers.single()

        val result =
            Missions.resolve(
                log = MissionLog.EMPTY,
                offers = offers,
                order = MissionOrder.Accept(offer.id),
                // In flight (not docked) — radio is the in-flight offer channel.
                dockedStation = null,
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertTrue("a radio offer is acceptable in flight", result.changed)
        assertEquals(1, result.log.accepted.size)
        val accepted = result.log.accepted.single()
        assertEquals(offer.id, accepted.id)
        assertEquals(MissionStatus.ACTIVE, accepted.status)
        assertEquals(MissionSource.RADIO, accepted.source)
    }

    @Test
    fun `an out-of-range ship cannot accept a broadcast it cannot hear`() {
        val justOutside = alphaStationPos + Vec2(radioRange + 1f, 0f)
        val offers = MissionGenerator.radioOffers(world, alpha, justOutside)

        val result =
            Missions.resolve(
                log = MissionLog.EMPTY,
                // The offer list is empty — nothing is in range to accept.
                offers = offers,
                order = MissionOrder.Accept(MissionId("radio:alpha-station")),
                dockedStation = null,
                cargo = Cargo.empty(),
                credits = 0L,
            )

        assertFalse("no in-range offer ⇒ the accept no-ops", result.changed)
    }

    @Test
    fun `a sector with no station in range broadcasts nothing`() {
        // gamma-junkyard sits at (-500, 200); place the ship far outside its 700 range.
        val gamma = world.sectors.first { it.id.value == "gamma" }.id
        val offers = MissionGenerator.radioOffers(world, gamma, Vec2(2000f, 2000f))
        assertTrue(offers.isEmpty())
    }
}
