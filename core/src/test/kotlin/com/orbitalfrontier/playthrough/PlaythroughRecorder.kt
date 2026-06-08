package com.orbitalfrontier.playthrough

import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.MineAction

/**
 * Accumulates a playthrough recording — the seed, fixed timestep, pinned config, optional initial
 * state, and the tick-stamped input script — and [build]s it into an immutable [Playthrough]
 * (UC02 AC#4).
 *
 * Supports **0..N events per tick** from the start: call [record] as many times as needed for a
 * given tick. [tickCount] grows automatically to cover the highest recorded tick; use
 * [extendToTick]/[setTickCount] to record trailing ticks that have no input (e.g. the ship coasting
 * after the stick is released).
 *
 * This is engine-agnostic and does **not** touch `PlayScreen` in UC02 — a live recorder hooked into
 * the screen is a later concern (and must use a fixed-step accumulator; see docs/PLAYTESTING.md).
 * Not thread-safe: confine recording to the simulation/render thread (coding-guidelines concurrency).
 */
class PlaythroughRecorder(
    private val name: String,
    private val seed: Long,
    private val dtSeconds: Float,
    config: ShipMovementParams = ShipMovementParams(),
    miningConfig: MiningParams = MiningParams(),
    initialState: SimulationState? = null,
) {
    init {
        require(dtSeconds > 0f) { "dtSeconds must be positive: $dtSeconds" }
    }

    private val configDto = MovementParamsDto.from(config)
    private val miningConfigDto = MiningParamsDto.from(miningConfig)
    private val initialStateDto = initialState?.let(StateSnapshotDto::from)
    private val events = mutableListOf<InputEvent>()
    private var tickCount = 0

    /** Number of ticks recorded so far (the highest covered tick + 1, or any explicit extension). */
    fun tickCount(): Int = tickCount

    /**
     * Record one input [event]. Multiple events may share a tick (0..N per tick). [tickCount] grows
     * to cover `event.tick + 1`. Returns `this` for chaining.
     */
    fun record(event: InputEvent): PlaythroughRecorder {
        require(event.tick >= 0) { "event tick must be non-negative: ${event.tick}" }
        events += event
        if (event.tick + 1 > tickCount) {
            tickCount = event.tick + 1
        }
        return this
    }

    /** Convenience: record a [MovementEvent] for [input] at [tick]. */
    fun recordMovement(
        tick: Int,
        input: MovementInput,
    ): PlaythroughRecorder = record(MovementEvent.from(tick, input))

    /** Convenience: record a [DockActionEvent] for [action] at [tick] (UC05). */
    fun recordDockAction(
        tick: Int,
        action: DockAction,
    ): PlaythroughRecorder = record(DockActionEvent(tick = tick, action = action))

    /** Convenience: record a [MineEvent] for [action] at [tick] (UC06). */
    fun recordMineAction(
        tick: Int,
        action: MineAction,
    ): PlaythroughRecorder = record(MineEvent(tick = tick, action = action))

    /** Record several [newEvents] in order. */
    fun recordAll(newEvents: Iterable<InputEvent>): PlaythroughRecorder {
        newEvents.forEach(::record)
        return this
    }

    /**
     * Ensure the recording spans at least [tick] + 1 ticks, so trailing no-input ticks are replayed.
     * Never shrinks the recording.
     */
    fun extendToTick(tick: Int): PlaythroughRecorder = setTickCount(tick + 1)

    /** Set the total [newTickCount]; must be ≥ the ticks already covered by recorded events. */
    fun setTickCount(newTickCount: Int): PlaythroughRecorder {
        require(newTickCount >= tickCount) {
            "newTickCount ($newTickCount) must be >= already-covered tickCount ($tickCount)"
        }
        tickCount = newTickCount
        return this
    }

    /** Build the immutable [Playthrough] from everything recorded so far. */
    fun build(): Playthrough =
        Playthrough(
            name = name,
            seed = seed,
            dtSeconds = dtSeconds,
            tickCount = tickCount,
            config = configDto,
            miningConfig = miningConfigDto,
            initialState = initialStateDto,
            inputEvents = events.toList(),
        )
}
