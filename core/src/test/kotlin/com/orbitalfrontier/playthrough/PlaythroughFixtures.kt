package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.MineAction
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

    /** UC05 docking playthrough name: thrust into Alpha Station's dock range and dock. */
    const val UC05_DOCK: String = "uc05-dock"

    /** UC06 mining playthrough name: thrust into the alpha-belt and hold MINE until the hold fills. */
    const val UC06_MINE: String = "uc06-mine"

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
            UC05_DOCK to ::uc05Dock,
            UC06_MINE to ::uc06Mine,
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

    /**
     * UC05 dock scenario (AC#6): the ship starts in [MvpSectorMap.START_SECTOR] (Alpha) just south
     * of Alpha Station (at `(0, 600)`, dock radius 100) already cruising north at max speed, and
     * thrusts straight north into the station's dock circle. After ~15 ticks it is comfortably inside
     * the dock range, at which point an explicit [DockAction.DOCK] docks the ship; the remaining
     * "held" ticks carry no input, so — being docked — the ship is **frozen** (no drift, no bounce),
     * proving the dock state both engages and freezes movement. The geometry is read from the
     * production [MvpSectorMap], so the fixture tracks the real map.
     */
    fun uc05Dock(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC05_DOCK,
                seed = 5L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        ship =
                            ShipKinematics(
                                position = Vec2(0f, 480f),
                                velocity = Vec2(0f, 120f),
                                headingRadians = (Math.PI / 2).toFloat(),
                            ),
                        // currentSector defaults to MvpSectorMap.START_SECTOR (alpha).
                    ),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        // Thrust north into Alpha Station's (0,600) r100 dock circle.
        for (tick in 0 until 15) {
            recorder.recordMovement(tick, north)
        }
        // In range now: issue the explicit dock action (proximity + action, never auto-dock).
        recorder.recordDockAction(15, DockAction.DOCK)
        // Hold: ticks 16..20 carry no input, so the docked ship stays frozen (no movement/bounce).
        recorder.extendToTick(20)
        return recorder.build()
    }

    /**
     * UC06 mining scenario (AC#2/#3/#4/#5/#7): the ship starts in [MvpSectorMap.START_SECTOR] (Alpha)
     * just south of the authored `alpha-belt` field (centre `(-600, -400)`, mining radius 100),
     * already cruising north at max speed, and thrusts straight north into the field's mining circle
     * while **holding MINE the whole time**. Holding MINE out of range is a no-op (proximity gating),
     * so extraction auto-starts once the ship is in range and continues every tick until the hold is
     * full.
     *
     * The field's authored deposits total 70 units (Hydrogen 20, Water-Ice 15, Iron-Ore 25, Copper
     * 10) — more than the starter hold's [com.orbitalfrontier.economy.Cargo.DEFAULT_CAPACITY] of 50 —
     * so mining to a full hold (50 units, extracted in [com.orbitalfrontier.economy.ResourceType]
     * ordinal order: all 20 Hydrogen, all 15 Water-Ice, then 15 of the Iron-Ore) leaves the field
     * **partially depleted** (20 units remain), exercising both the capacity stop (AC#3) and field
     * depletion (AC#4). At the default 2 units/tick the fill takes 25 mining ticks; the 40-tick span
     * leaves comfortable margin to enter range first. Geometry is read from the production
     * [MvpSectorMap], so the fixture tracks the real map.
     */
    fun uc06Mine(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC06_MINE,
                seed = 6L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        ship =
                            ShipKinematics(
                                // x aligned with the field centre; ~110 wu south of it (just out of
                                // the radius-100 circle) so a few ticks of northward thrust enter it.
                                position = Vec2(-600f, -510f),
                                velocity = Vec2(0f, 120f),
                                headingRadians = (Math.PI / 2).toFloat(),
                            ),
                        // currentSector defaults to MvpSectorMap.START_SECTOR (alpha).
                    ),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        // Thrust north into the alpha-belt while holding MINE; extraction auto-starts once in range
        // and runs until the hold fills (25 mining ticks at the default 2 units/tick).
        for (tick in 0 until 40) {
            recorder.recordMovement(tick, north)
            recorder.recordMineAction(tick, MineAction.MINE)
        }
        return recorder.build()
    }
}
