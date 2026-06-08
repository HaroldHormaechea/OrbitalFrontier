package com.orbitalfrontier.sim

import com.orbitalfrontier.economy.FuelBurn
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.Refueling
import com.orbitalfrontier.platform.Rng
import com.orbitalfrontier.platform.TimeSource
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.FuelLimitedMovement
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.Docking
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.Mining
import com.orbitalfrontier.world.MiningResult
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
    private val miningParams: MiningParams = MiningParams(),
    private val powerParams: PowerParams = PowerParams(),
    private val fuelParams: FuelParams = FuelParams(),
) {
    /** The seeded randomness source for this run, for sim systems that need it (none in UC02 yet). */
    fun rng(): Rng = rng

    /** Elapsed simulation seconds at [tick], derived purely from the fixed step (no wall clock). */
    fun elapsedSecondsAt(tick: Int): Float = timeSource.secondsAt(tick)

    /**
     * Advance [state] by one fixed tick of [dt] seconds under [input] and the player's per-tick
     * [dockAction], returning the next snapshot with its tick incremented. [dt] must be > 0 and, for
     * a coherent replay, identical to the playthrough's recorded `dtSeconds`.
     *
     * **Docked ⇒ frozen (UC05 AC#2/#6).** While [SimulationState.dockedStation] is non-null the ship
     * is parked at the station: this method **explicitly short-circuits both movement and gate
     * traversal** (the ship neither drifts nor re-triggers a jump) and only advances the tick. The
     * single exception is an explicit [DockAction.UNDOCK], which falls through to the flight path and
     * returns the ship to flight. A [DockAction.DOCK] while already docked is a no-op (it holds).
     *
     * **In flight.** Movement integrates first; the new ship position is checked against
     * [GateTraversal.resolve] in the ship's current sector — a hit relocates the ship to the linked
     * gate's arrival point in the destination sector, preserving velocity and heading (momentum
     * through the gate, UC03 AC#3). [Docking.resolve] then resolves [dockAction] against the
     * resulting sector/position: a [DockAction.DOCK] with a station in range docks the ship (so the
     * *next* tick it is frozen). The default [DockAction.NONE] leaves the dock state unchanged, so
     * the pre-UC05 fixtures (which pass no action) step identically.
     *
     * **Mining (UC06).** After movement/gate/dock resolution, while the ship is **in flight** (it did
     * not dock this tick) the player's [mineAction] is resolved against the post-movement
     * sector/position via the pure [Mining.resolve], threading the resulting [SimulationState.cargo]
     * and [SimulationState.fieldDepletion] into the next snapshot (UC06 AC#2/#4/#5). The default
     * [MineAction.NONE] is a no-op that returns cargo + depletion unchanged, and a freshly-docked
     * tick skips mining entirely, so the pre-UC06 fixtures (which pass no mine action) step
     * **byte-identically**.
     *
     * **Fuel & power (UC07).** [refuelAction] is resolved **first, before the docked-freeze short-
     * circuit**, via the pure [Refueling.resolve] (so a docked ship can convert hydrogen cargo into
     * fuel — refuel-while-docked, UC07 AC#5); the resulting fuel + cargo thread forward whether the
     * ship is frozen or in flight. On the in-flight path the ship then **burns fuel**: the power
     * model's draw at the current thrust state (`thrusting = !released && magnitude > deadzone`, the
     * same gate the device loop uses) is consumed via the shared [FuelBurn.step], and the movement
     * model runs against [FuelLimitedMovement.effectiveParams] — so a low tank scales the speed caps
     * down (UC07 AC#3) while a full-enough tank leaves [params] untouched (the byte-identical
     * guarantee for the pre-UC07 fixtures). The post-burn [SimulationState.fuel] threads into the next
     * snapshot (UC07 AC#6). The default [RefuelAction.NONE] + a full default tank makes every pre-UC07
     * fixture step its movement **byte-identically**.
     */
    fun step(
        state: SimulationState,
        input: MovementInput,
        dt: Float,
        dockAction: DockAction = DockAction.NONE,
        mineAction: MineAction = MineAction.NONE,
        refuelAction: RefuelAction = RefuelAction.NONE,
    ): SimulationState {
        require(dt > 0f) { "dt must be positive: $dt" }

        // Refuel resolves FIRST — before the docked-freeze short-circuit — so a docked ship can
        // convert hydrogen cargo into fuel (refuel-while-docked, UC07 AC#5). NONE is a no-op that
        // returns fuel + cargo unchanged, so pre-UC07 fixtures thread the same values through.
        val refuel = Refueling.resolve(state.fuel, state.cargo, refuelAction, fuelParams)

        // Docked and not explicitly undocking ⇒ frozen: short-circuit movement AND gate traversal.
        // Only the tick advances (plus any refuel just resolved); position, velocity, heading, sector,
        // dock state and field depletion are untouched, so a held-while-docked stretch is bit-for-bit
        // stable (UC05 AC#6) — and burns NO fuel (the station hub, not flight, owns docked time).
        if (state.dockedStation != null && dockAction != DockAction.UNDOCK) {
            return state.copy(tick = state.tick + 1, fuel = refuel.fuel, cargo = refuel.cargo)
        }

        // In flight: burn fuel for this tick's power draw, then derive the fuel-limited movement params.
        // "Thrusting" matches the device loop's gate exactly so live and replayed burn agree (UC07 AC#2).
        val thrusting = !input.released && input.magnitude > params.inputDeadzone
        val burnedFuel = FuelBurn.step(refuel.fuel, thrusting, powerParams, dt)
        // Byte-identical at a full-enough tank: effectiveParams returns `params` unchanged (factor 1.0),
        // so the movement model steps exactly as before; only a low tank scales the speed caps (AC#3).
        val effectiveParams = FuelLimitedMovement.effectiveParams(params, burnedFuel, fuelParams)

        val movedShip = movementModel.update(state.ship, input, effectiveParams, dt)
        val traversal = GateTraversal.resolve(world, state.currentSector, movedShip.position)
        val nextSector = traversal?.destinationSector ?: state.currentSector
        // Keep velocity & heading; only the position and sector change on a jump (momentum carries).
        val nextShip = if (traversal == null) movedShip else movedShip.copy(position = traversal.arrivalPosition)
        // Resolve the dock action against the post-movement sector/position. DOCK with a station in
        // range docks; UNDOCK clears the dock; NONE leaves it unchanged (the common pre-UC05 path).
        val nextDocked = Docking.resolve(world, nextSector, state.dockedStation, nextShip.position, dockAction)

        // Mining only while in flight (not docked this tick). Operates on the post-refuel cargo so a
        // refuel + mine in the same tick composes correctly; a NONE action returns cargo + depletion
        // unchanged, so non-mining playthroughs thread the same defaults through and step identically.
        val mining =
            if (nextDocked == null) {
                Mining.resolve(
                    world = world,
                    currentSector = nextSector,
                    shipPosition = nextShip.position,
                    cargo = refuel.cargo,
                    fieldDepletion = state.fieldDepletion,
                    action = mineAction,
                    params = miningParams,
                )
            } else {
                MiningResult(refuel.cargo, state.fieldDepletion, 0)
            }

        return SimulationState(
            tick = state.tick + 1,
            ship = nextShip,
            currentSector = nextSector,
            dockedStation = nextDocked,
            cargo = mining.cargo,
            fieldDepletion = mining.fieldDepletion,
            fuel = burnedFuel,
        )
    }
}
