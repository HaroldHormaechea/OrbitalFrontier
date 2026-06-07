package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.MvpSectorMap

/**
 * Reproducible test playthrough fixtures, built from the [PlaythroughRecorder] (UC02 AC#4/#7).
 *
 * The committed JSON artifacts under `core/src/test/resources/playthroughs/` are generated from
 * these builders (see [PlaythroughFixtureTest], which both verifies the committed file matches and
 * regenerates it on demand). Keeping the builder in code makes the artifact reproducible: anyone
 * can rebuild the exact same playthrough rather than hand-editing JSON.
 */
object PlaythroughFixtures {
    /** Canonical UC01 movement playthrough name (matches docs/PLAYTESTING.md + the playtest skill). */
    const val UC01_THRUST_NORTH: String = "uc01-thrust-north"

    /** UC03 jump-gate playthrough name: thrust the ship through a gate and across sectors. */
    const val UC03_JUMP: String = "uc03-jump"

    /** Fixed timestep used by the fixtures (60 Hz) — a *fixed* step, not a live frame delta. */
    const val DT_SECONDS: Float = 1f / 60f

    /**
     * Every committed fixture, name → builder, in a stable order. [PlaythroughFixtureTest] iterates
     * this map to (a) verify each committed JSON matches its builder and (b) regenerate them all on
     * demand, so adding a fixture here is all that's needed to bring it under the guard.
     */
    val ALL: Map<String, () -> Playthrough> =
        linkedMapOf(
            UC01_THRUST_NORTH to ::uc01ThrustNorth,
            UC03_JUMP to ::uc03Jump,
        )

    /**
     * UC01 movement scenario: thrust "north" (stick → +y) for 60 ticks, then coast (no input) for
     * 30 trailing ticks so drift decay is exercised. Seeded deterministically; starts at rest at
     * the origin. Total span: 90 ticks. This is the exact recipe documented in docs/PLAYTESTING.md.
     */
    fun uc01ThrustNorth(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC01_THRUST_NORTH,
                seed = 1L,
                dtSeconds = DT_SECONDS,
                initialState = SimulationState(),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        for (tick in 0 until 60) {
            recorder.recordMovement(tick, north)
        }
        // 30 trailing no-input (drift) ticks: ticks 60..89 ⇒ tickCount 90.
        recorder.extendToTick(89)
        return recorder.build()
    }

    /**
     * UC03 jump scenario (AC#3/#9/#10): the ship starts in the [MvpSectorMap.START_SECTOR] (Alpha)
     * already cruising east at max speed, just short of the `alpha-to-beta` gate's trigger circle,
     * and thrusts straight east into it. Within ~20 ticks it crosses the trigger radius and jumps to
     * Beta, arriving offset from the `beta-to-alpha` gate (anti-bounce-back) with its eastward
     * momentum intact. Thrust continues for the full 30 ticks so the post-jump ticks prove the ship
     * doesn't immediately re-trigger a jump back.
     *
     * Starting mid-flight (rather than from rest at the origin) keeps the artifact small while still
     * genuinely *thrusting* into the gate. The geometry is read from the production [MvpSectorMap],
     * so the fixture tracks the real map.
     */
    fun uc03Jump(): Playthrough {
        // The alpha→beta gate sits on the +x axis; start just inside the content area, east-bound, a
        // little short of its trigger circle so a few ticks of eastward thrust carry the ship in.
        val recorder =
            PlaythroughRecorder(
                name = UC03_JUMP,
                seed = 3L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        ship =
                            ShipKinematics(
                                position = Vec2(1180f, 0f),
                                velocity = Vec2(120f, 0f),
                                headingRadians = 0f,
                            ),
                        // currentSector defaults to MvpSectorMap.START_SECTOR (alpha).
                    ),
            )
        val east = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 1f, released = false)
        for (tick in 0 until 30) {
            recorder.recordMovement(tick, east)
        }
        return recorder.build()
    }
}
