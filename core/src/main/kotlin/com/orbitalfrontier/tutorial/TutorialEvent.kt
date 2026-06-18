package com.orbitalfrontier.tutorial

/**
 * The discrete gameplay moments the first-run tutorial advances on (UC36).
 *
 * One entry per completing action a [TutorialStep] waits for. The play screen records these from the
 * SAME gameplay event seams the audio (UC31) / notification (UC35) systems use — the thrust edge, a
 * dock, a mission accept, a productive gather (mine or trade), a refuel, and a weapon fire — and applies
 * them to the [TutorialState] inside the gated per-frame advance, so the onboarding never alters the
 * deterministic simulation (AC#4): it only observes events the sim already produced.
 *
 * Pure, engine-free (no libGDX) so the whole `tutorial` package stays JVM-unit-testable (ADR 0001) and
 * a static purity guard can keep it that way, exactly like the `audio` / `notify` model packages.
 */
enum class TutorialEvent {
    /** The player thrust the ship for the first time (the joystick crossed the thrust deadzone). */
    STEERED,

    /** The player docked at a station (UC05). */
    DOCKED,

    /** The player accepted a mission offer — from a radio broadcast or the station board (UC12). */
    MISSION_ACCEPTED,

    /** The player gathered resources — a productive mining tick (UC06) or a station trade (UC08). */
    GATHERED,

    /** The player refuelled — hydrogen conversion or a credits fuel purchase at a station (UC07/UC18). */
    REFUELLED,

    /** The player fired their weapons in combat (UC13). */
    FIRED,
}
