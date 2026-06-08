package com.orbitalfrontier.render

/**
 * The single global UI-scale knob (ADR 0015).
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
 * Pure (no engine types) so it stays JVM-testable (ADR 0001) and carries no rendering dependency; the
 * engine-touching application helpers live alongside it ([ScreenScaling.kt]) and in the overlay
 * renderers.
 */
object UiScale {
    /** Backing constant for [factor] (screaming-snake per the project's object-constant convention). */
    private const val DEFAULT_FACTOR: Float = 2f

    /**
     * UI/HUD magnification — the single knob. `2f` renders the chrome at double size on high-density
     * phone screens where the placeholder 1:1 UI is uncomfortably small. Rendering-only — never read
     * into movement, combat, or any simulation math (that would break determinism/replay; ADR 0006).
     */
    val factor: Float get() = DEFAULT_FACTOR
}
