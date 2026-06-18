package com.orbitalfrontier.tutorial

/**
 * The ordered first-run onboarding steps (UC36 AC#1), each introducing one beat of the core loop:
 * steer, dock, accept a mission, gather, refuel, and fire.
 *
 * Declaration order **is** the tutorial order — [TutorialState] walks the steps front to back. Each step
 * carries the short ASCII [copy] the overlay shows (the bundled game font ships ASCII + `°` + `→` only,
 * UC28, so the copy stays within that set), the [highlight] control to emphasise (AC#2), and the
 * [completingEvent] that advances past it (AC#1).
 *
 * Two steps are **cross-screen**: GATHER's trade path and REFUEL both finish at a docked station, off the
 * flight screen. Their copy is deliberately two-part ("... or DOCK then TRADE", "DOCK then REFUEL") so the
 * hint — which is only shown on the flight screen — still tells the player where to go (the documented
 * lightweight-staged scope, ADR 0024).
 *
 * Pure, engine-free (no libGDX) so the package stays JVM-testable (ADR 0001).
 */
enum class TutorialStep(
    val highlight: TutorialHighlight,
    val completingEvent: TutorialEvent,
    val copy: String,
) {
    /** Thrust with the left stick. */
    STEER(
        highlight = TutorialHighlight.JOYSTICK,
        completingEvent = TutorialEvent.STEERED,
        copy = "STEER  Drag the left stick to thrust and turn your ship.",
    ),

    /** Fly to a station and dock. */
    DOCK(
        highlight = TutorialHighlight.DOCK,
        completingEvent = TutorialEvent.DOCKED,
        copy = "DOCK  Fly to a station, then tap DOCK when it is in range.",
    ),

    /** Accept a mission from a radio offer. */
    ACCEPT_MISSION(
        highlight = TutorialHighlight.RADIO,
        completingEvent = TutorialEvent.MISSION_ACCEPTED,
        copy = "MISSION  Tap ACCEPT on a radio offer to take on a job.",
    ),

    /** Gather resources — mine in flight, or trade at a station. */
    GATHER(
        highlight = TutorialHighlight.MINE,
        completingEvent = TutorialEvent.GATHERED,
        copy = "GATHER  Hold MINE at an asteroid field - or DOCK then TRADE.",
    ),

    /** Top off the fuel tank — by docking and refuelling. */
    REFUEL(
        highlight = TutorialHighlight.DOCK,
        completingEvent = TutorialEvent.REFUELLED,
        copy = "REFUEL  DOCK at a station, then REFUEL to top off your tank.",
    ),

    /** Fire on hostiles in combat. */
    FIRE(
        highlight = TutorialHighlight.FIRE,
        completingEvent = TutorialEvent.FIRED,
        copy = "FIRE  Hold FIRE to shoot back when hostiles attack.",
    ),
    ;

    companion object {
        /** The ordered steps (declaration order is the tutorial order). */
        val ORDER: List<TutorialStep> = entries.toList()
    }
}
