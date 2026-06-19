package com.orbitalfrontier.notify

/**
 * How prominently an on-screen [GameNotification] is styled (UC35 AC#2).
 *
 * A deliberately tiny, libGDX-free axis — the engine-free core decides the *meaning* of a cue (a routine
 * confirmation vs. a caution the player should act on) and the device-side
 * [com.orbitalfrontier.render.NotificationRenderer] maps that meaning to a concrete colour from the
 * design-system [com.orbitalfrontier.render.Palette]. Kept in the engine-free `notify` package (scanned by
 * the UC35 purity guard) so the simulation can classify an event without ever touching the engine, exactly
 * like [com.orbitalfrontier.audio.Sfx] does for sound (ADR 0001 JVM-testability).
 */
enum class NotificationSeverity {
    /** A routine, expected confirmation — drawn in the neutral readout colour. */
    INFO,

    /** A caution the player may want to act on (low fuel, a timeout, a loss) — drawn in the warning colour. */
    WARNING,

    /**
     * A rejected/failed action the player just attempted — an unaffordable buy or an otherwise invalid
     * economy action (UC40 AC#3) — drawn in the distinct **danger** colour. Deliberately a step beyond
     * [WARNING]: a routine spend (an amber [NotificationKind.CREDIT_LOSS] WARNING) must read as clearly
     * different from a *refusal*, so the renderer must NOT reuse the warning colour for this tier.
     */
    ERROR,
}
