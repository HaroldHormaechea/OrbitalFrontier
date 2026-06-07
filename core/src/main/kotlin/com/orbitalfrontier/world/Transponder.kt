package com.orbitalfrontier.world

/**
 * The kind of contact a [Transponder]-broadcasting POI advertises (docs/design/world-and-sector.md
 * "Detection — transponders").
 *
 * A small closed set today (gates and stations); new broadcasting kinds are added here as later UCs
 * introduce them (e.g. ships). The minimap keys its marker style off this value, so a new kind gets
 * a distinct marker by extending the renderer's mapping, not by editing per-type `if` ladders
 * (coding-guidelines § O, Open/Closed).
 */
enum class ContactKind {
    GATE,
    STATION,
}

/**
 * Capability of a POI that **broadcasts a transponder** and therefore shows up automatically on the
 * minimap/HUD (UC05 AC#1; docs/design/world-and-sector.md).
 *
 * A small, focused capability interface (coding-guidelines § I, Interface Segregation) kept separate
 * from [Poi]: a consumer that only cares about "things on the minimap" depends on `Transponder`, not
 * on every POI kind. It is the **Open/Closed seam** the minimap renders against — it filters the
 * sector's POIs to those that are `Transponder` and draws each by its [contactKind], so a future
 * broadcasting POI appears on the map without touching existing rendering code.
 *
 * Pure (no engine types) so it stays in the JVM-testable `world` model (ADR 0001).
 */
interface Transponder {
    /** What this contact advertises itself as — drives the minimap marker style. */
    val contactKind: ContactKind
}
