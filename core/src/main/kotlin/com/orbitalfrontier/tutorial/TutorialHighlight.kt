package com.orbitalfrontier.tutorial

/**
 * The control a tutorial step draws the player's eye to (UC36 AC#2).
 *
 * Each [TutorialStep] names exactly one highlight; the device-side overlay maps it to the matching
 * on-screen control and emphasises it **visually only** — it never gates or consumes input (AC#4), so
 * the player can always fly, even ahead of the prompted step. The set mirrors the movement joystick plus
 * the relevant [com.orbitalfrontier.screen.controls.ActionCluster.Action] buttons.
 *
 * Pure, engine-free (no libGDX): this names the control, the overlay binds the actor.
 */
enum class TutorialHighlight {
    /** The left movement joystick (the STEER step). */
    JOYSTICK,

    /** The DOCK action button (the DOCK and REFUEL steps — REFUEL is reached by docking first). */
    DOCK,

    /** The RADIO accept button (the ACCEPT_MISSION step). */
    RADIO,

    /** The MINE action button (the GATHER step — its in-flight path). */
    MINE,

    /** The FIRE action button (the FIRE step). */
    FIRE,
}
