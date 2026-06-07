package com.orbitalfrontier.platform

/**
 * Injected time port (DIP — coding-guidelines / determinism).
 *
 * Core sim logic that needs "how much time has elapsed" depends on this abstraction, never on
 * `System.nanoTime()`, `System.currentTimeMillis()`, or libGDX's `Gdx.graphics.deltaTime`. In a
 * deterministic fixed-timestep simulation, elapsed **simulation** time is a pure function of the
 * tick index and the fixed step: `seconds = tick · dt`. There is intentionally **no wall-clock
 * reading** — wall time would break replay reproducibility (UC02 AC#1/#2).
 *
 * Kept deliberately minimal (tick-derived only). The on-device wall clock, if ever needed for
 * non-deterministic concerns (e.g. real-world timestamps on a save file), belongs behind a
 * *separate* port so it can never leak into the deterministic sim path.
 */
interface TimeSource {
    /** Elapsed simulation time, in seconds, at the given [tick] index (tick 0 ⇒ 0s). */
    fun secondsAt(tick: Int): Float
}

/**
 * [TimeSource] that derives elapsed simulation seconds purely from the tick index and the fixed
 * timestep. Holds no mutable state and reads no clock, so it is trivially deterministic.
 */
class TickTimeSource(private val dtSeconds: Float) : TimeSource {
    init {
        require(dtSeconds > 0f) { "dtSeconds must be positive: $dtSeconds" }
    }

    override fun secondsAt(tick: Int): Float = tick * dtSeconds
}
