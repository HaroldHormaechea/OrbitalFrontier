package com.orbitalfrontier.world

/**
 * The kind of **any** contact — broadcasting or hidden — that can appear on the minimap/HUD
 * (docs/design/world-and-sector.md "Detection — transponders & active scanning").
 *
 * A small closed set: [GATE]/[STATION] broadcast a transponder and auto-show (UC05); [SHIP] is the
 * hidden, no-transponder kind ships/objects run as until an active scan reveals them (UC10). The
 * minimap keys its marker style off this value, so a new kind gets a distinct marker by extending the
 * renderer's mapping, not by editing per-type `if` ladders (coding-guidelines § O, Open/Closed).
 *
 * UC54 adds three additional point-of-interest kinds (docs/adr/0042-additional-poi-types.md): a
 * [DERELICT] wreck (a scan-only [Contact], not a [Transponder] — uncovered by an active scan like a
 * [HiddenContact]), a [DISTRESS] beacon and a [HAZARD] field (both broadcasting [Transponder]s that
 * auto-show). Each is a distinct marker kind so the minimap/overlay draw them without a per-type branch.
 */
enum class ContactKind {
    GATE,
    STATION,
    SHIP,
    DERELICT,
    DISTRESS,
    HAZARD,
}

/**
 * Capability of anything that registers as a **contact** on the minimap/HUD — whether it broadcasts a
 * transponder ([Transponder]) or stays hidden until scanned ([HiddenContact], UC10).
 *
 * A small, focused capability interface (coding-guidelines § I, Interface Segregation) kept separate
 * from [Poi]: a consumer that only cares about "things that can show on the minimap" depends on
 * `Contact`, not on every POI kind. It is the **Open/Closed seam** the minimap renders against — it
 * filters the sector's POIs to those that are `Contact` and draws each by its [contactKind], so a new
 * contact kind appears on the map without touching existing rendering code.
 *
 * Pure (no engine types) so it stays in the JVM-testable `world` model (ADR 0001).
 */
interface Contact {
    /** What this contact registers as — drives the minimap marker style. */
    val contactKind: ContactKind
}

/**
 * Capability of a POI that **broadcasts a transponder** and therefore shows up automatically on the
 * minimap/HUD (UC05 AC#1; docs/design/world-and-sector.md). A broadcasting [Contact]: the minimap
 * draws every `Transponder` unconditionally, whereas a plain [Contact] (e.g. [HiddenContact]) only
 * draws once it has been revealed by a scan (UC10).
 *
 * Pure (no engine types) so it stays in the JVM-testable `world` model (ADR 0001).
 */
interface Transponder : Contact
