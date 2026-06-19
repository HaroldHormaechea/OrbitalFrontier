package com.orbitalfrontier.render

/**
 * The global reduced-motion knob (UC39 AC#3), shaped like [UiScale] / [TextScale] but a simple boolean.
 *
 * When [reduced] is true, non-essential motion is stopped: the multi-layer parallax starfield
 * ([StarfieldRenderer]) is drawn as a STATIC field (zero per-layer camera offset) rather than scrolling
 * with the camera — a full stop, not an attenuation (the locked UC39 decision). The intent also covers
 * screen-shake and other non-essential animations; the MVP has no screen-shake or decorative tweens today,
 * so the starfield is the only current motion source, and any future such effect MUST consult this flag.
 *
 * It does NOT touch gameplay motion (the ship, hostiles, projectiles still move) — only decorative,
 * camera-driven background motion that can cause discomfort.
 *
 * Rendering-only and pure (no engine types) — never read into the simulation (determinism/replay; ADR 0006)
 * and JVM-testable (ADR 0001). A clamped mutable global with the single-writer (settings control) /
 * single-reader-per-frame (the starfield) access pattern, mirroring [UiScale] / [TextScale].
 */
object MotionPreference {
    /** Default: motion ON (false) — the prior full-parallax behaviour. */
    const val DEFAULT_REDUCED: Boolean = false

    private var current: Boolean = DEFAULT_REDUCED

    /** True when the player has asked for reduced motion (static starfield, no non-essential animation). */
    val reduced: Boolean get() = current

    /** Set the global reduced-motion flag. Pure bookkeeping; renderers read [reduced] each frame. */
    fun set(value: Boolean) {
        current = value
    }

    /** Reset to [DEFAULT_REDUCED] (motion on) — for tests / a settings "reset to default" path. */
    fun reset() {
        current = DEFAULT_REDUCED
    }
}
