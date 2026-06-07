package com.orbitalfrontier.playthrough

import com.orbitalfrontier.platform.SeededRng
import com.orbitalfrontier.platform.TickTimeSource
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.sim.Simulation
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.DockAction

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
            state = simulation.step(state, input, playthrough.dtSeconds, dockAction)
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
}
