package com.orbitalfrontier.render

/**
 * The single global UI-scale knob (ADR 0015; made player-adjustable in UC37 / ADR 0025).
 *
 * [factor] multiplies the on-screen size of the **UI/HUD layer only** — the Scene2D screens/controls
 * (via [ScreenViewport.applyUiScale]) and the screen-space HUD overlays
 * ([com.orbitalfrontier.render.HudRenderer], [com.orbitalfrontier.render.ShipSchematicRenderer],
 * [com.orbitalfrontier.render.MinimapRenderer]). It deliberately does **not** touch the world camera
 * or any in-world object size — the world view is rendered at 1:1 (product decision: scale the chrome,
 * not the playfield).
 *
 * It is the *single source of truth*: base layout constants stay authored at their base (×1) values and
 * the factor is applied at each use site — never baked into the constants — so changing this one value
 * rescales the whole UI consistently.
 *
 * UC37 (ADR 0025) makes [factor] a **clamped mutable global**: a DISPLAY-group settings control writes
 * it (via [set]) and persists it, and the persisted value is restored at startup before any screen
 * builds. It remains **rendering-only** — still never read into movement, combat, or any simulation math
 * (that would break determinism/replay; ADR 0006). Screens that captured the factor at construction
 * (the in-flight HUD/minimap renderers) reflect a live change only on their next rebuild — the settings
 * screen and overlay re-apply it to their own live viewport immediately; the play screen's already-built
 * HUD picks it up on the next screen rebuild (no app restart needed). Mutating a global is a deliberate,
 * pragmatic exception to the project's value-type leaning, justified in ADR 0025 by the single-writer,
 * single-reader-per-frame access pattern and the rendering-only blast radius.
 *
 * Pure (no engine types) so it stays JVM-testable (ADR 0001) and carries no rendering dependency; the
 * engine-touching application helpers live alongside it ([ScreenScaling.kt]) and in the overlay
 * renderers.
 */
object UiScale {
    /** Default magnification, and the fallback a NaN/∞/out-of-range write collapses to. */
    const val DEFAULT_FACTOR: Float = 2f

    /** Inclusive clamp range for [factor] — `1f` (no magnification) up to `3f` (large-text accessibility). */
    const val MIN_FACTOR: Float = 1f
    const val MAX_FACTOR: Float = 3f

    private var current: Float = DEFAULT_FACTOR

    /**
     * UI/HUD magnification — the single knob, `2f` by default (renders the chrome at double size on
     * high-density phone screens where the placeholder 1:1 UI is uncomfortably small). Player-adjustable
     * since UC37; always within `MIN_FACTOR..MAX_FACTOR`. Rendering-only — never read into movement,
     * combat, or any simulation math (that would break determinism/replay; ADR 0006).
     */
    val factor: Float get() = current

    /**
     * Set the global UI-scale [value], coerced to `MIN_FACTOR..MAX_FACTOR` (a NaN/∞ value collapses to
     * [DEFAULT_FACTOR]). Returns the value actually stored, so callers can persist exactly what took
     * effect. Pure bookkeeping — applying it to live Scene2D viewports is the caller's job
     * ([ScreenViewport.applyUiScale]).
     */
    fun set(value: Float): Float {
        current = coerce(value)
        return current
    }

    /** Coerce [value] into `MIN_FACTOR..MAX_FACTOR`, collapsing NaN/∞ to [DEFAULT_FACTOR]. */
    fun coerce(value: Float): Float =
        when {
            !value.isFinite() -> DEFAULT_FACTOR
            value < MIN_FACTOR -> MIN_FACTOR
            value > MAX_FACTOR -> MAX_FACTOR
            else -> value
        }

    /** Reset to [DEFAULT_FACTOR] — for tests / a settings "reset to default" path. */
    fun reset() {
        current = DEFAULT_FACTOR
    }
}
