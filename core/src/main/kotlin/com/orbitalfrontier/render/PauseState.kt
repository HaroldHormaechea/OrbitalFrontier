package com.orbitalfrontier.render

/**
 * Pure, libGDX-free open/closed state for the in-flight pause overlay (UC32).
 *
 * The deliberate inverse of [MapOverlayState]: where the click-to-zoom map overlay is LIVE (it never
 * suspends the simulation, `MapOverlayLayout.PAUSES_SIMULATION = false`, see ADR 0021), the pause
 * overlay *freezes* the deterministic tick while open so no game time passes (UC32 AC#2). Modelled as
 * an immutable value with [paused]/[resumed]/[toggled] transitions so the gate logic is JVM-unit-testable
 * (ADR 0001) independent of any Scene2D actor — [com.orbitalfrontier.screen.PlayScreen] holds one of
 * these, flips it on the HUD pause button / Android back key, and reads [isPaused] once per frame to
 * gate the entire per-frame state-advance.
 */
data class PauseState(val isPaused: Boolean = false) {
    /** The state after a pause request: always paused (returns `this` when already paused). */
    fun paused(): PauseState = if (isPaused) this else copy(isPaused = true)

    /** The state after a resume request: always running (returns `this` when already running). */
    fun resumed(): PauseState = if (isPaused) copy(isPaused = false) else this

    /** The state after a toggle: flip paused<->running. */
    fun toggled(): PauseState = copy(isPaused = !isPaused)
}
