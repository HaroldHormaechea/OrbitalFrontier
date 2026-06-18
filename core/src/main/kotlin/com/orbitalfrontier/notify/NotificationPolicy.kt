package com.orbitalfrontier.notify

/**
 * The tunables that govern how the [NotificationQueue] shows and retires toasts (UC35 AC#2).
 *
 * Pure value data (libGDX-free, JVM-testable — ADR 0001) with authored MVP defaults; a test can construct
 * a bespoke policy (e.g. a short [displaySeconds]) to drive the queue deterministically without real time.
 *
 * @property displaySeconds how long a notification stays visible before it auto-dismisses.
 * @property maxVisible how many notifications show at once (they stack, non-overlapping — AC#2); the rest
 *   wait their turn in the queue and only begin ageing once promoted into the visible window.
 * @property maxQueued the hard cap on live entries (visible + waiting); once reached a further enqueue is
 *   dropped rather than growing unboundedly under a flood (AC#2 pitfall).
 * @property coalesceWindowSeconds how long a coalescable entry keeps absorbing same-key repeats; a same-kind
 *   event arriving while an existing entry is within this window refreshes that one instead of adding a new
 *   toast (flood-defense layer 2).
 */
data class NotificationPolicy(
    val displaySeconds: Float = 3.5f,
    val maxVisible: Int = 3,
    val maxQueued: Int = 12,
    val coalesceWindowSeconds: Float = 3.5f,
)
