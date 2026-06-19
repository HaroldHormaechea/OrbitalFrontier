package com.orbitalfrontier.notify

/**
 * The transient toast queue (UC35 AC#2/#3) — a pure, libGDX-free state machine the screen drives.
 *
 * It holds a small ordered set of live notifications and governs the two player-facing rules AC#2 calls for:
 *  - **Non-overlapping display.** Only the first [NotificationPolicy.maxVisible] entries are *visible* and
 *    only those age; entries beyond the window wait their turn and start their display clock only once
 *    promoted — so a burst is shown one batch at a time rather than all at once.
 *  - **Auto-dismiss.** A visible entry that has been up for [NotificationPolicy.displaySeconds] is removed on
 *    the next [update]; the freed slot promotes the next waiting entry.
 *
 * **Flood defense (AC#2 pitfall).** Two layers keep a bursty source (combat, a run of credit changes) from
 * flooding the feed: layer 1 is upstream — the per-tick combat events map to `null` in
 * [GameNotifications.forCombatEvent] so they never reach here; layer 2 is here — [enqueue] **coalesces**
 * a [NotificationKind.coalescable] notification into an existing live entry that shares its
 * [GameNotification.coalesceKey] and is still within [NotificationPolicy.coalesceWindowSeconds], by
 * **refreshing** that entry (replacing its content with the newest and resetting its display clock) instead
 * of appending a second toast (the drop/refresh knob). Non-coalescable kinds (a jump, a dock) always stack
 * as distinct entries. A final [NotificationPolicy.maxQueued] cap bounds the worst case.
 *
 * Engine-free and event-driven: the screen calls [enqueue] from gameplay seams and [update] only while the
 * simulation advances (so toasts freeze with the sim under pause/destruction), then draws [visible] (AC#3).
 * Not thread-safe — it is owned and driven entirely on the render thread, like the rest of the screen state.
 */
class NotificationQueue(
    private val policy: NotificationPolicy = NotificationPolicy(),
) {
    /** One live entry: the current [notification] and how long it has been *visible* ([age], seconds). */
    private class Entry(
        var notification: GameNotification,
        var age: Float,
    )

    private val entries = ArrayDeque<Entry>()

    /**
     * Add [notification], coalescing it into a same-key live entry when allowed (see the class doc), and
     * otherwise appending it unless the [NotificationPolicy.maxQueued] cap is already reached (in which case
     * it is dropped — the on-screen toasts win under a flood).
     */
    fun enqueue(notification: GameNotification) {
        if (notification.kind.coalescable) {
            val existing =
                entries.firstOrNull {
                    it.notification.coalesceKey == notification.coalesceKey &&
                        it.age <= policy.coalesceWindowSeconds
                }
            if (existing != null) {
                // Drop/refresh: keep the single live entry, adopt the newest content, and reset its clock so
                // the coalesced toast lingers through the burst rather than vanishing mid-stream.
                existing.notification = notification
                existing.age = 0f
                return
            }
        }
        if (entries.size >= policy.maxQueued) return
        entries.addLast(Entry(notification, 0f))
    }

    /**
     * Advance the visible window by [dt] seconds and auto-dismiss any visible entry that has reached
     * [NotificationPolicy.displaySeconds]. Only the visible window ages; a dismissed entry frees its slot so
     * the next waiting entry is promoted (and begins ageing) here or on a subsequent call.
     *
     * Called by the screen only while the simulation advances (UC35 AC#3) — so under pause / the destruction
     * screen the toasts freeze exactly like the rest of the sim.
     */
    fun update(dt: Float) {
        var shown = 0
        val iterator = entries.iterator()
        while (iterator.hasNext() && shown < policy.maxVisible) {
            val entry = iterator.next()
            entry.age += dt
            if (entry.age >= policy.displaySeconds) {
                iterator.remove()
            } else {
                shown++
            }
        }
    }

    /** A pure snapshot of the currently visible notifications, top-of-stack first (UC35 AC#3). */
    fun visible(): List<GameNotification> =
        entries.asSequence()
            .take(policy.maxVisible)
            .map { it.notification }
            .toList()

    /**
     * A pure snapshot of the currently visible notifications **with their life fraction** (UC40 AC#2),
     * top-of-stack first — the animation-aware sibling of [visible]. Each entry's [VisibleNotification.
     * lifeFraction] is its visible age as a fraction of [NotificationPolicy.displaySeconds], clamped to
     * `0..1` (0 just shown, 1 about to auto-dismiss). The device-side
     * [com.orbitalfrontier.render.NotificationRenderer] drives the fade-in/out + upward drift off this pure
     * fraction, so the *timing* stays here (engine-free, unit-testable) and only the pixels live in the
     * renderer. [visible] is kept intact so existing callers/tests are unaffected.
     */
    fun visibleWithProgress(): List<VisibleNotification> =
        entries.asSequence()
            .take(policy.maxVisible)
            .map { VisibleNotification(it.notification, (it.age / policy.displaySeconds).coerceIn(0f, 1f)) }
            .toList()

    /** Total live entries (visible + waiting) — for tests/diagnostics; the renderer uses [visible]. */
    fun size(): Int = entries.size
}

/**
 * A visible notification paired with its life fraction `0..1` (UC40 AC#2) — the pure unit the animated
 * renderer consumes. Engine-free value data (ADR 0001): the fraction is computed by
 * [NotificationQueue.visibleWithProgress] and the renderer maps it to alpha + drift, so the fade/drift
 * curve is testable without a GL context.
 */
data class VisibleNotification(
    val notification: GameNotification,
    val lifeFraction: Float,
)
