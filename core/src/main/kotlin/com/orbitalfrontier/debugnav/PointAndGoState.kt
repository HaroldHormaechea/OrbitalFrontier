package com.orbitalfrontier.debugnav

/**
 * Pure, libGDX-free arm/disarm state for the debug point-and-go navigation aid (UC25 AC#1).
 *
 * The feature is a debug-only tester convenience (gated on `BuildConfig.DEBUG` at the call site, so
 * release builds never construct it). This value carries only whether point-and-go is currently
 * **armed** — i.e. whether a tap on the main flight view should be treated as a teleport. It defaults
 * to **off** so arming is an explicit, deliberate action and normal taps (minimap zoom, HUD buttons)
 * are never hijacked until the tester arms it.
 *
 * Immutable and engine-free so the gate is JVM-unit-testable headlessly (the suite has no GL),
 * mirroring the project's pure-model pattern (e.g. [com.orbitalfrontier.render.MapOverlayState]).
 */
data class PointAndGoState(val armed: Boolean = false) {
    /** The state with [armed] flipped — the arm toggle's effect. */
    fun toggled(): PointAndGoState = copy(armed = !armed)
}
