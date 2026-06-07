package com.orbitalfrontier.sim

import com.orbitalfrontier.platform.Rng
import com.orbitalfrontier.platform.TimeSource
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.SectorWorld

/**
 * The single, pure, deterministic fixed-timestep stepper for the game world (UC02 AC#1, UC03 AC#10).
 *
 * One [step] advances [SimulationState] by exactly one fixed tick of [dt] seconds under one frame
 * of [MovementInput]. It contains **no** GL, Android, or Box2D types and performs no I/O, so it
 * runs headlessly on the JVM and is the same code path used by both the live recorder and the
 * headless [com.orbitalfrontier.playthrough.ReplayRunner] — that shared, side-effect-free stepper
 * is what guarantees record/replay parity.
 *
 * Determinism note (ADR 0005 / ADR 0006): replay targets this **pure model**, never the on-device
 * Box2D integrator. Box2D owns position integration on device, but its native step is outside the
 * JVM determinism contract; for CI-grade reproducibility we integrate position inside
 * [ShipMovementModel] (semi-implicit Euler) and assert against that.
 *
 * UC03 jump gates: after each movement update [step] consults the pure [GateTraversal.resolve] —
 * the **same** function [com.orbitalfrontier.screen.PlayScreen] runs on device — against the ship's
 * new position. On a non-null traversal it switches [SimulationState.currentSector] to the
 * destination and relocates the ship to the gate's arrival point **keeping its velocity and
 * heading** (momentum carries through the gate, matching the device's `resetTo`). [world] is
 * injected (defaulting to the MVP map) so the same gate graph drives both the live game and replay.
 *
 * The [rng] and [timeSource] ports are injected and seeded/configured per playthrough. No UC02 sim
 * system consumes them yet (movement is purely deterministic), but they are wired in now so every
 * later system draws randomness and time from the deterministic seam rather than retrofitting it.
 *
 * @param params pinned per-playthrough movement tuning; a recorded playthrough stores the exact
 *   [ShipMovementParams] it ran under so a later tuning change can't silently invalidate old replays.
 * @param world the fixed sector graph traversed by jump gates; defaults to [MvpSectorMap.build].
 */
class Simulation(
    private val rng: Rng,
    private val timeSource: TimeSource,
    private val params: ShipMovementParams = ShipMovementParams(),
    private val movementModel: ShipMovementModel = ShipMovementModel(),
    private val world: SectorWorld = MvpSectorMap.build(),
) {
    /** The seeded randomness source for this run, for sim systems that need it (none in UC02 yet). */
    fun rng(): Rng = rng

    /** Elapsed simulation seconds at [tick], derived purely from the fixed step (no wall clock). */
    fun elapsedSecondsAt(tick: Int): Float = timeSource.secondsAt(tick)

    /**
     * Advance [state] by one fixed tick of [dt] seconds under [input], returning the next snapshot
     * with its tick incremented. [dt] must be > 0 and, for a coherent replay, identical to the
     * playthrough's recorded `dtSeconds`.
     *
     * After integrating movement, the new ship position is checked against [GateTraversal.resolve]
     * in the ship's current sector. If it lands inside a gate's trigger circle the snapshot's
     * [SimulationState.currentSector] becomes the destination and the ship is relocated to the
     * gate's arrival point — velocity and heading are preserved, so momentum carries through the
     * jump exactly as the on-device `PlayScreen` does (UC03 AC#3). Otherwise the sector is unchanged.
     */
    fun step(
        state: SimulationState,
        input: MovementInput,
        dt: Float,
    ): SimulationState {
        require(dt > 0f) { "dt must be positive: $dt" }
        val movedShip = movementModel.update(state.ship, input, params, dt)
        val traversal = GateTraversal.resolve(world, state.currentSector, movedShip.position)
        return if (traversal == null) {
            SimulationState(
                tick = state.tick + 1,
                ship = movedShip,
                currentSector = state.currentSector,
            )
        } else {
            // Keep velocity & heading; only the position and sector change (momentum through the gate).
            SimulationState(
                tick = state.tick + 1,
                ship = movedShip.copy(position = traversal.arrivalPosition),
                currentSector = traversal.destinationSector,
            )
        }
    }
}
