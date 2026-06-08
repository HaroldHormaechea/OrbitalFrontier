package com.orbitalfrontier.sim

import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.crew.Hiring
import com.orbitalfrontier.economy.FuelBurn
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.Refueling
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.economy.Trading
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.Outfitting
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.platform.Rng
import com.orbitalfrontier.platform.TimeSource
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.FleetResolver
import com.orbitalfrontier.ship.FuelLimitedMovement
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.Shipyard
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.Docking
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.Mining
import com.orbitalfrontier.world.MiningResult
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.ScanAction
import com.orbitalfrontier.world.Scanning
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.StationKind

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
     *
     * **Trading (UC08).** [tradeOrder] is resolved **while docked**, in the same frozen branch (after
     * the refuel resolves, so a refuel + trade in the same tick compose against the post-refuel cargo):
     * the docked station's authored market is looked up from [world] (mirroring the device's
     * [com.orbitalfrontier.screen.PlayScreen.trade]) and the pure [Trading.resolve] threads the new
     * [SimulationState.credits] + [SimulationState.cargo] into the docked-return snapshot (UC08 AC#3).
     * Trading is implicitly gated on being docked: in flight there is no market, so the in-flight path
     * simply **passes [SimulationState.credits] through unchanged**. The default [TradeOrder.None] is a
     * no-op (credits + cargo unchanged), so every pre-UC08 fixture steps **byte-identically**.
     *
     * **Scanning (UC10).** After mining, while the ship is **in flight** (it did not dock this tick) the
     * player's [scanAction] is resolved via the pure [Scanning.resolve] against the post-movement
     * sector/position. The active ship's effective sensor range is derived from its type + loadout via
     * [ShipStats.scanRange] — the same derivation the device uses — so a sensors upgrade (UC09 SCANNER_I)
     * widens the reveal radius (UC10 AC#3). A [ScanAction.SCAN] unions the in-range hidden contacts into
     * [SimulationState.revealedContacts]; reveal is **monotonic** — ids are never removed, even on leaving
     * range (UC10 AC#4). The default [ScanAction.NONE] returns the same revealed set instance, so every
     * pre-UC10 fixture threads its (empty) revealed set through and steps **byte-identically**.
     */
    fun step(
        state: SimulationState,
        input: MovementInput,
        dt: Float,
        dockAction: DockAction = DockAction.NONE,
        mineAction: MineAction = MineAction.NONE,
        refuelAction: RefuelAction = RefuelAction.NONE,
        tradeOrder: TradeOrder = TradeOrder.None,
        outfitOrder: OutfitOrder = OutfitOrder.None,
        fleetOrder: FleetOrder = FleetOrder.None,
        scanAction: ScanAction = ScanAction.NONE,
        hireOrder: HireOrder = HireOrder.None,
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
            // Trading resolves while docked, against the docked station's authored market and the
            // post-refuel cargo (refuel + trade compose). The same market lookup the device's
            // PlayScreen.trade uses; an unresolvable station / no trade desk yields a null market and
            // Trading.resolve no-ops. TradeOrder.None (the default) is a no-op too — credits + cargo
            // thread through unchanged, so a held-while-docked stretch stays bit-for-bit stable.
            val station = world.sector(state.currentSector).station(state.dockedStation)
            val trade = Trading.resolve(state.credits, refuel.cargo, station?.market, tradeOrder)

            // UC09 composition while docked: refuel -> trade -> outfit -> fleet. Outfitting resolves
            // against the active ship's loadout + slot layout and the docked station's outfit desk;
            // FleetResolver against the station's shipyard. Both no-op on their default None order, so a
            // held-while-docked stretch (no orders) stays bit-for-bit stable.
            val active = state.fleet.active
            val outfit =
                Outfitting.resolve(
                    credits = trade.credits,
                    loadout = active.loadout,
                    slotCounts = active.type.slotCounts,
                    outfitMarket = station?.outfitMarket ?: OutfitMarket.EMPTY,
                    isJunkyard = station?.kind == StationKind.JUNKYARD,
                    order = outfitOrder,
                )
            // Fold cargo (trade) + fuel (refuel) onto the active ship, then — only if the fit changed —
            // re-derive capacities via withLoadout (Δ-capacity propagation, UC09 AC#2). Contents/level
            // are preserved (clamped only if a capacity shrank).
            val refittedActive =
                active.copy(cargo = trade.cargo, fuel = refuel.fuel)
                    .let { if (outfit.changed) it.withLoadout(outfit.loadout) else it }
            val fleetAfterOutfit = state.fleet.withActive(refittedActive)

            val fleetResult =
                FleetResolver.resolve(fleetAfterOutfit, outfit.credits, station?.shipyard ?: Shipyard.EMPTY, fleetOrder)

            // Crew hiring (UC11) is the LAST docked step: refuel -> trade -> outfit -> fleet -> hire, so a
            // hire resolves against the post-fleet wallet and the ship that is active AFTER any switch.
            // Capacity is derived from the active ship's type + loadout via ShipStats.crewCapacity — the
            // SAME source PlayScreen.hire uses on device (parity is required for AC#6) — and is gated on
            // the docked station's hiresCrew flag. HireOrder.None (the default) and a non-hiring station
            // both no-op, so a held-while-docked stretch (no hire) stays bit-for-bit stable; crew rides
            // on the active ship, so SimulationState needs no new field.
            val postFleetActive = fleetResult.fleet.active
            val hire =
                Hiring.resolve(
                    credits = fleetResult.credits,
                    currentCrew = postFleetActive.crew,
                    crewCapacity = ShipStats.crewCapacity(postFleetActive.type, postFleetActive.loadout),
                    offersCrew = station?.hiresCrew ?: false,
                    order = hireOrder,
                )
            val fleetAfterHire =
                if (hire.changed) fleetResult.fleet.withActive(postFleetActive.withCrew(hire.crew)) else fleetResult.fleet

            return state.copy(
                tick = state.tick + 1,
                fleet = fleetAfterHire,
                credits = hire.credits,
            )
        }

        // In flight: burn fuel for this tick's power draw, then derive the fuel-limited movement params.
        // "Thrusting" matches the device loop's gate exactly so live and replayed burn agree (UC07 AC#2).
        val thrusting = !input.released && input.magnitude > params.inputDeadzone
        val burnedFuel = FuelBurn.step(refuel.fuel, thrusting, powerParams, dt)
        // UC09: derive the active ship's effective movement params from its type + engine loadout FIRST
        // (ShipStats), then apply the fuel-limited scaling on top. For the starter ship with an empty
        // loadout ShipStats returns `params` unchanged (===), so the pre-UC09 fixtures stay byte-
        // identical; an engine upgrade / faster hull raises the speed+accel caps before fuel limiting.
        val active = state.fleet.active
        val shipParams = ShipStats.effectiveMovementParams(params, active.type, active.loadout)
        // Byte-identical at a full-enough tank: effectiveParams returns `shipParams` unchanged (factor
        // 1.0), so the movement model steps exactly as before; only a low tank scales the caps (AC#3).
        val effectiveParams = FuelLimitedMovement.effectiveParams(shipParams, burnedFuel, fuelParams)

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

        // Scanning only while in flight (UC10). The active ship's effective sensor range is derived from
        // its type + loadout via ShipStats (the SAME derivation the device uses), so a sensors upgrade
        // (UC09 SCANNER_I) widens what a scan reveals (AC#3). Scanning.resolve unions the in-range hidden
        // contacts into the revealed set on a ScanAction.SCAN and returns the SAME set instance otherwise
        // — so a NONE scan (the common path) threads the prior set through unchanged and is monotonic
        // (revealed ids never re-hide, AC#4). A freshly-docked tick skips scanning, like mining.
        val revealedContacts =
            if (nextDocked == null) {
                val scanRange = ShipStats.scanRange(active.type, active.loadout)
                Scanning.resolve(
                    world = world,
                    currentSector = nextSector,
                    shipPosition = nextShip.position,
                    scanRange = scanRange,
                    revealed = state.revealedContacts,
                    action = scanAction,
                )
            } else {
                state.revealedContacts
            }

        // UC09: fold this tick's kinematics + cargo + fuel back onto the active ship in the fleet.
        // copy() (not withLoadout) — the loadout is unchanged in flight, so capacities stay as derived.
        val updatedActive = state.fleet.active.copy(kinematics = nextShip, cargo = mining.cargo, fuel = burnedFuel)
        return state.copy(
            tick = state.tick + 1,
            fleet = state.fleet.withActive(updatedActive),
            currentSector = nextSector,
            dockedStation = nextDocked,
            fieldDepletion = mining.fieldDepletion,
            // In flight there is no station market, so trading is a no-op; the wallet threads through
            // unchanged (UC08 AC#1 — credits persist tick to tick). Trades happen only while docked.
            credits = state.credits,
            // Revealed hidden contacts after this tick's scan (UC10): grown by a SCAN, otherwise the
            // prior set threaded through unchanged (monotonic).
            revealedContacts = revealedContacts,
        )
    }
}
