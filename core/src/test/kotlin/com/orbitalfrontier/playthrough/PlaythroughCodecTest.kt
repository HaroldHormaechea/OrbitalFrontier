package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.mission.BountyParams
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PlaythroughCodec] — the stable, diffable JSON form of a [Playthrough]
 * (UC02 AC#3/#8).
 *
 * Covers a lossless round-trip across the full shape (the polymorphic sealed [InputEvent] plus both
 * DTOs — [MovementParamsDto] and [StateSnapshotDto]) and the stability/diffability properties the
 * artifact relies on (pretty-printed, defaults encoded, self-describing polymorphic discriminator).
 */
class PlaythroughCodecTest {
    /** A playthrough exercising every serialized shape: multiple events per tick, config + state DTOs. */
    private fun richPlaythrough(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = "codec-round-trip",
                seed = 42L,
                dtSeconds = 1f / 60f,
                config = ShipMovementParams(maxSpeed = 99f, inputDeadzone = 0.2f),
                initialState =
                    SimulationState(
                        tick = 0,
                        fleet =
                            singleShipFleet(
                                kinematics =
                                    ShipKinematics(
                                        position = Vec2(1f, 2f),
                                        velocity = Vec2(-3f, 4f),
                                        headingRadians = 0.5f,
                                        angularVelocity = -0.25f,
                                    ),
                            ),
                    ),
            )
        // 0..N events per tick: two distinct movement samples sharing tick 0, one at tick 1.
        recorder.recordMovement(0, MovementInput(Vec2(0f, 1f), magnitude = 1f, released = false))
        recorder.recordMovement(0, MovementInput(Vec2(1f, 0f), magnitude = 0.5f, released = false))
        recorder.recordMovement(1, MovementInput(Vec2.ZERO, magnitude = 0f, released = true))
        recorder.extendToTick(4)
        return recorder.build()
    }

    @Test
    fun `decode of encode round-trips the full playthrough`() {
        val original = richPlaythrough()

        val decoded = PlaythroughCodec.decode(PlaythroughCodec.encode(original))

        // Whole-object equality covers the sealed InputEvent hierarchy and both DTOs at once.
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip preserves the polymorphic movement events including multiple per tick`() {
        val original = richPlaythrough()

        val decoded = PlaythroughCodec.decode(PlaythroughCodec.encode(original))

        val events = decoded.inputEvents.filterIsInstance<MovementEvent>()
        assertEquals(3, events.size)
        // Both tick-0 samples survive in order (0..N events per tick).
        assertEquals(listOf(0, 0, 1), events.map { it.tick })
        assertEquals(MovementInput(Vec2(0f, 1f), 1f, released = false), events[0].toMovementInput())
        assertEquals(MovementInput(Vec2(1f, 0f), 0.5f, released = false), events[1].toMovementInput())
        assertTrue(events[2].released)
    }

    @Test
    fun `round-trip preserves the config and initial-state DTOs`() {
        val original = richPlaythrough()

        val decoded = PlaythroughCodec.decode(PlaythroughCodec.encode(original))

        assertEquals(99f, decoded.config.toParams().maxSpeed, 0f)
        assertEquals(0.2f, decoded.config.toParams().inputDeadzone, 0f)
        val initial = decoded.initialState!!.toSimulationState()
        assertEquals(Vec2(1f, 2f), initial.ship.position)
        assertEquals(Vec2(-3f, 4f), initial.ship.velocity)
        assertEquals(0.5f, initial.ship.headingRadians, 0f)
        assertEquals(-0.25f, initial.ship.angularVelocity, 0f)
    }

    @Test
    fun `encoded form is pretty-printed and diffable`() {
        val json = PlaythroughCodec.encode(richPlaythrough())

        // Pretty-print ⇒ multi-line, one field per line: a noisy diff signals a behaviour change.
        assertTrue("expected multi-line pretty output", json.lines().size > 10)
        assertTrue("expected indented fields", json.contains("\n  \""))
    }

    @Test
    fun `encoded form is self-describing with a type discriminator`() {
        val json = PlaythroughCodec.encode(richPlaythrough())

        // The sealed InputEvent serializes with kotlinx's "type" discriminator + the @SerialName.
        assertTrue("expected polymorphic discriminator", json.contains("\"type\""))
        assertTrue("expected movement SerialName", json.contains("\"movement\""))
    }

    @Test
    fun `state snapshot DTO round-trips the current sector (UC03 AC#5 persistence)`() {
        val state =
            SimulationState(
                tick = 5,
                fleet = singleShipFleet(kinematics = ShipKinematics(position = Vec2(7f, 8f))),
                currentSector = SectorId("gamma"),
            )

        // DTO lock-step both ways: from() captures it, toSimulationState() restores it.
        assertEquals(state, StateSnapshotDto.from(state).toSimulationState())

        // ...and it survives the JSON codec embedded in a playthrough's initial state.
        val playthrough =
            PlaythroughRecorder(name = "sector-round-trip", seed = 1L, dtSeconds = 1f / 60f, initialState = state)
                .build()
        val decoded = PlaythroughCodec.decode(PlaythroughCodec.encode(playthrough))
        assertEquals(SectorId("gamma"), decoded.initialState!!.toSimulationState().currentSector)
    }

    @Test
    fun `round-trips a pinned bounty config and an active bounty mission (UC41)`() {
        // A playthrough that pins a non-default BountyParams and carries an ACTIVE bounty in its initial state.
        val bounty =
            Mission(
                id = MissionId("bounty:bounty-alpha-raider"),
                type = MissionType.BOUNTY,
                source = MissionSource.RADIO,
                status = MissionStatus.ACTIVE,
                rewardCredits = 1750L,
                targetZoneId = "bounty-alpha-raider",
                killTarget = 2,
                killProgress = 1,
            )
        val playthrough =
            PlaythroughRecorder(
                name = "bounty-round-trip",
                seed = 41L,
                dtSeconds = 1f / 60f,
                bountyConfig = BountyParams(rewardBase = 1000L, rewardPerKill = 375L),
                initialState = SimulationState(missions = MissionLog(accepted = listOf(bounty))),
            ).build()

        val decoded = PlaythroughCodec.decode(PlaythroughCodec.encode(playthrough))

        // The pinned bounty tuning survives the codec.
        assertEquals(1000L, decoded.bountyConfig.toBountyParams().rewardBase)
        assertEquals(375L, decoded.bountyConfig.toBountyParams().rewardPerKill)
        // The bounty mission's UC41 fields survive embedded in the initial state.
        val decodedBounty = decoded.initialState!!.toSimulationState().missions.accepted.single()
        assertEquals(MissionType.BOUNTY, decodedBounty.type)
        assertEquals("bounty-alpha-raider", decodedBounty.targetZoneId)
        assertEquals(2, decodedBounty.killTarget)
        assertEquals(1, decodedBounty.killProgress)
    }

    @Test
    fun `a default bounty config and non-bounty missions omit the UC41 fields on disk (byte-identity)`() {
        // A mining mission (no bounty fields) under the default bounty tuning: the new UC41 fields must NOT
        // appear on disk (their @EncodeDefault(NEVER) guard), keeping every pre-UC41 fixture byte-identical.
        val mining =
            Mission(
                id = MissionId("board:alpha-station:mining"),
                type = MissionType.MINING,
                source = MissionSource.BOARD,
                status = MissionStatus.ACTIVE,
                rewardCredits = 400L,
                quotaResource = com.orbitalfrontier.economy.ResourceType.HYDROGEN,
                quotaUnits = 8,
            )
        val json =
            PlaythroughCodec.encode(
                PlaythroughRecorder(
                    name = "no-bounty",
                    seed = 1L,
                    dtSeconds = 1f / 60f,
                    initialState = SimulationState(missions = MissionLog(accepted = listOf(mining))),
                ).build(),
            )

        assertFalse("default bountyConfig is omitted on disk", json.contains("bountyConfig"))
        assertFalse("a non-bounty mission omits targetZoneId", json.contains("targetZoneId"))
        assertFalse("a non-bounty mission omits killTarget", json.contains("killTarget"))
        assertFalse("a non-bounty mission omits killProgress", json.contains("killProgress"))
    }

    @Test
    fun `encodeDefaults writes default-valued fields explicitly`() {
        // A playthrough leaving config/initialState/formatVersion at their defaults.
        val minimal =
            PlaythroughRecorder(name = "minimal", seed = 7L, dtSeconds = 1f / 60f)
                .recordMovement(0, MovementInput(Vec2(0f, 1f), 1f, released = false))
                .build()

        val json = PlaythroughCodec.encode(minimal)

        // encodeDefaults ⇒ even default-valued fields are present, so a later default change can't
        // silently alter how an old artifact decodes.
        assertTrue("formatVersion should be written", json.contains("\"formatVersion\""))
        assertTrue("default config should be written", json.contains("\"config\""))
        assertEquals(Playthrough.CURRENT_FORMAT_VERSION, PlaythroughCodec.decode(json).formatVersion)
    }
}
