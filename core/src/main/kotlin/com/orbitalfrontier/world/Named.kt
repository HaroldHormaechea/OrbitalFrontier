package com.orbitalfrontier.world

/**
 * Capability of anything that carries a human-readable [displayName] worth surfacing in the UI — e.g.
 * a [Station]'s name shown as a map label (UC24 AC#1/#2).
 *
 * A small, focused capability interface (coding-guidelines § I, Interface Segregation) kept separate
 * from [Poi] and [Contact], mirroring [Transponder]/[Contact]: a consumer that only cares about
 * "things that have a name" depends on `Named`, not on every concrete POI type. It is the
 * **Open/Closed seam** the map label pass renders against — a new named entity surfaces a label by
 * implementing `Named`, with no change to the label-rendering code.
 *
 * Pure (no engine types) so it stays in the JVM-testable `world` model (ADR 0001).
 */
interface Named {
    /** The human-readable name to display; implementors guarantee it is non-blank. */
    val displayName: String
}
