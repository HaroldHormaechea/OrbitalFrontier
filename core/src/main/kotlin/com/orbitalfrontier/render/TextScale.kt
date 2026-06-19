package com.orbitalfrontier.render

/**
 * The global UI text-size knob (UC39 AC#2), shaped exactly like [UiScale].
 *
 * [factor] multiplies the Scene2D **skin font** size *on top of* the global [UiScale] — it scales the
 * UI/menu/control text independently of the overall UI magnification, so a player who wants larger text
 * but not larger chrome (or vice-versa) can have it. It is applied at one place — the skin font in
 * [com.orbitalfrontier.screen.controls.OrbitalUiSkin] (`data.setScale(GameFont.NORM * factor)`) — and the
 * host re-applies it live on a change. The in-flight HUD/world-space text (HudRenderer, NotificationRenderer,
 * the minimap/overlay labels) deliberately follows [UiScale] only and is NOT affected: those are heads-up
 * gameplay readouts sized to the playfield, not chrome (a deliberate, documented exclusion — UC39).
 *
 * **Font-blur ceiling.** Because [factor] multiplies on top of [UiScale], the simultaneous-max corner is
 * `UiScale.MAX_FACTOR (3) × MAX (1.4)`; the bundled font is baked at [GameFont.BAKE_CAP_PX] (48px) and
 * normalised by [GameFont.NORM] to ~15px, so the worst-case on-screen size is `15 × 1.4 × 3 ≈ 63px`, a
 * ≤1.31× upscale of the 48px master — it softens gracefully (Linear filtering) and never crashes. Guarded
 * by a unit test (UC39).
 *
 * Rendering-only and pure (no engine types) — never read into movement/combat/simulation math (that would
 * break determinism/replay; ADR 0006), and JVM-testable (ADR 0001). Like [UiScale] it is a clamped mutable
 * global with the single-writer (settings control) / single-reader-per-rebuild (the skin) access pattern.
 */
object TextScale {
    /** Default multiplier, and the fallback a NaN/∞/out-of-range write collapses to (no extra scaling). */
    const val DEFAULT_FACTOR: Float = 1.0f

    /** Inclusive clamp range — `0.85f` (slightly smaller) up to `1.4f` (large-text accessibility). */
    const val MIN_FACTOR: Float = 0.85f
    const val MAX_FACTOR: Float = 1.4f

    private var current: Float = DEFAULT_FACTOR

    /**
     * UI text-size multiplier applied to the skin font on top of [UiScale]; `1.0f` by default. Always
     * within `MIN_FACTOR..MAX_FACTOR`. Rendering-only.
     */
    val factor: Float get() = current

    /**
     * Set the global text-scale [value], coerced to `MIN_FACTOR..MAX_FACTOR` (a NaN/∞ value collapses to
     * [DEFAULT_FACTOR]). Returns the value actually stored, so callers can persist exactly what took
     * effect. Pure bookkeeping — applying it to the live skin font is the caller's job
     * ([com.orbitalfrontier.screen.controls.OrbitalUiSkin.applyTextScale]).
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
