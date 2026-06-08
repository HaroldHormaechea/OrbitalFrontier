package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.TradeKind
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.platform.SeededRng
import com.orbitalfrontier.platform.TickTimeSource
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.sim.Simulation
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.MineAction

/**
 * Executes a [Playthrough] headlessly on the JVM and returns the resulting state (UC02 AC#5/#6).
 *
 * Deterministic by construction: it seeds a fresh [SeededRng] from the playthrough, sets the
 * initial [SimulationState], then steps the **pure** [Simulation] once per tick (`0 until
 * tickCount`), grouping the input script by tick and applying every event at that tick (default
 * "no input" when a tick has none). Re-running the same playthrough yields an identical result
 * (AC#1/#11).
 *
 * No GL, no Android, no test-framework dependency — and deliberately **no Box2D**: replay targets
 * the pure model, never the native on-device integrator (ADR 0005/0006). This class must never
 * import `ShipPhysics` or `com.badlogic.gdx.physics.box2d.*`.
 */
class ReplayRunner {
    /**
     * Result of a replay: the [finalState] after the last tick, and — when requested —
     * [perTickStates], a snapshot per tick where index 0 is the initial state and index `k` is the
     * state after `k` steps (size `tickCount + 1`). When not captured, [perTickStates] is empty.
     */
    data class ReplayResult(
        val finalState: SimulationState,
        val perTickStates: List<SimulationState> = emptyList(),
    )

    /**
     * Replay [playthrough]. Set [capturePerTickStates] to also collect the per-tick snapshot list
     * (AC#6 assertion helper); leave it false for the common "assert on the end state" case.
     */
    fun run(
        playthrough: Playthrough,
        capturePerTickStates: Boolean = false,
    ): ReplayResult {
        require(playthrough.tickCount >= 0) { "tickCount must be non-negative: ${playthrough.tickCount}" }

        val eventsByTick = playthrough.inputEvents.groupBy(InputEvent::tick)
        val outOfRange = eventsByTick.keys.filter { it < 0 || it >= playthrough.tickCount }
        require(outOfRange.isEmpty()) {
            "playthrough '${playthrough.name}' has events at ticks outside 0 until ${playthrough.tickCount}: $outOfRange"
        }

        val simulation =
            Simulation(
                rng = SeededRng(playthrough.seed),
                timeSource = TickTimeSource(playthrough.dtSeconds),
                params = playthrough.config.toParams(),
                miningParams = playthrough.miningConfig.toMiningParams(),
                powerParams = playthrough.powerConfig.toPowerParams(),
                fuelParams = playthrough.fuelConfig.toFuelParams(),
            )

        var state = playthrough.initialState?.toSimulationState() ?: SimulationState()

        val perTick =
            if (capturePerTickStates) {
                ArrayList<SimulationState>(playthrough.tickCount + 1).apply { add(state) }
            } else {
                null
            }

        for (tick in 0 until playthrough.tickCount) {
            val tickEvents = eventsByTick[tick].orEmpty()
            val input = movementInputFor(tickEvents)
            val dockAction = dockActionFor(tickEvents)
            val mineAction = mineActionFor(tickEvents)
            val refuelAction = refuelActionFor(tickEvents)
            val tradeOrder = tradeOrderFor(tickEvents)
            val outfitOrder = outfitOrderFor(tickEvents)
            val fleetOrder = fleetOrderFor(tickEvents)
            state =
                simulation.step(
                    state,
                    input,
                    playthrough.dtSeconds,
                    dockAction,
                    mineAction,
                    refuelAction,
                    tradeOrder,
                    outfitOrder,
                    fleetOrder,
                )
            perTick?.add(state)
        }

        return ReplayResult(
            finalState = state,
            perTickStates = perTick.orEmpty(),
        )
    }

    /**
     * Reduce a tick's events to the [MovementInput] the sim steps with. UC02 only has movement
     * input; when several movement samples share a tick the latest wins, and a tick with no
     * movement event steps with [MovementInput.NONE]. As new [InputEvent] kinds are added, dispatch
     * them here alongside movement.
     */
    private fun movementInputFor(events: List<InputEvent>): MovementInput =
        events.filterIsInstance<MovementEvent>().lastOrNull()?.toMovementInput() ?: MovementInput.NONE

    /**
     * Reduce a tick's events to the [DockAction] the sim steps with (UC05). When several dock
     * samples share a tick the latest wins; a tick with no [DockActionEvent] steps with
     * [DockAction.NONE], so movement-only artifacts (UC01/UC03) replay exactly as before.
     */
    private fun dockActionFor(events: List<InputEvent>): DockAction =
        events.filterIsInstance<DockActionEvent>().lastOrNull()?.action ?: DockAction.NONE

    /**
     * Reduce a tick's events to the [MineAction] the sim steps with (UC06). When several mine samples
     * share a tick the latest wins; a tick with no [MineEvent] steps with [MineAction.NONE], so
     * non-mining artifacts (UC01/UC03/UC05) replay exactly as before.
     */
    private fun mineActionFor(events: List<InputEvent>): MineAction =
        events.filterIsInstance<MineEvent>().lastOrNull()?.action ?: MineAction.NONE

    /**
     * Reduce a tick's events to the [RefuelAction] the sim steps with (UC07). When several refuel
     * samples share a tick the latest wins; a tick with no [RefuelEvent] steps with
     * [RefuelAction.NONE], so non-refuelling artifacts (UC01/UC03/UC05/UC06) replay exactly as before.
     */
    private fun refuelActionFor(events: List<InputEvent>): RefuelAction =
        events.filterIsInstance<RefuelEvent>().lastOrNull()?.action ?: RefuelAction.NONE

    /**
     * Reduce a tick's events to the [TradeOrder] the sim steps with (UC08). When several trade samples
     * share a tick the latest wins; a tick with no [TradeEvent] steps with [TradeOrder.None], so
     * non-trading artifacts (UC01/UC03/UC05/UC06/UC07) replay exactly as before. A [TradeEvent] maps to
     * the matching [TradeOrder.Buy]/[TradeOrder.Sell] carrying its resource + requested units (the
     * resolver clamps the actual quantity).
     */
    private fun tradeOrderFor(events: List<InputEvent>): TradeOrder =
        events.filterIsInstance<TradeEvent>().lastOrNull()?.let { event ->
            when (event.kind) {
                TradeKind.BUY -> TradeOrder.Buy(event.resource, event.units)
                TradeKind.SELL -> TradeOrder.Sell(event.resource, event.units)
            }
        } ?: TradeOrder.None

    /**
     * Reduce a tick's events to the [OutfitOrder] the sim steps with (UC09). When several outfit
     * samples share a tick the latest wins; a tick with no [OutfitEvent] steps with [OutfitOrder.None],
     * so non-outfitting artifacts (UC01..UC08) replay exactly as before.
     */
    private fun outfitOrderFor(events: List<InputEvent>): OutfitOrder =
        events.filterIsInstance<OutfitEvent>().lastOrNull()?.toOutfitOrder() ?: OutfitOrder.None

    /**
     * Reduce a tick's events to the [FleetOrder] the sim steps with (UC09). When several fleet samples
     * share a tick the latest wins; a tick with no [FleetEvent] steps with [FleetOrder.None], so
     * non-fleet artifacts (UC01..UC08) replay exactly as before.
     */
    private fun fleetOrderFor(events: List<InputEvent>): FleetOrder =
        events.filterIsInstance<FleetEvent>().lastOrNull()?.toFleetOrder() ?: FleetOrder.None
}
