package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * A **hidden contact** — a ship or object running *without* a transponder, so it is invisible on the
 * minimap/HUD until the player runs an active scan that reaches it (UC10 AC#2/#3;
 * docs/design/world-and-sector.md "Detection — active scanning").
 *
 * It is a [Poi] (it sits in a sector's content cluster like any other) and a [Contact] (it has a
 * [contactKind] so the minimap can draw it once revealed) but deliberately **not** a [Transponder]:
 * that is exactly what makes it hidden by default. The minimap draws every `Transponder`
 * unconditionally; a `HiddenContact` is drawn only when its [id] is in the player's revealed set
 * (see [Scanning] / [WorldState.revealedContacts]).
 *
 * Adding this kind needed no change to the existing POI types or the minimap's marker switch beyond a
 * new [ContactKind] value — the Open/Closed seam (coding-guidelines § O): hidden contacts plug in
 * through the shared [Contact] capability rather than a per-type branch.
 *
 * Pure data — no engine types — so hidden contacts are part of the JVM-testable world model
 * (ADR 0001) and the scan logic that reads them ([Scanning]) stays unit-testable on the JVM
 * (UC10 AC#5).
 */
data class HiddenContact(
    override val id: PoiId,
    override val position: Vec2,
    /** What the contact registers as once scanned; defaults to a [ContactKind.SHIP] (a running ship). */
    override val contactKind: ContactKind = ContactKind.SHIP,
) : Poi, Contact
