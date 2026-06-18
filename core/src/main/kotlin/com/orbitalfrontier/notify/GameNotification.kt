package com.orbitalfrontier.notify

/**
 * One on-screen notification: a [kind], a player-facing [message], the [severity] it is styled by, and the
 * [coalesceKey] the [NotificationQueue] groups bursts on (UC35 AC#1/#2).
 *
 * Pure, immutable value data (libGDX-free, JVM-testable — ADR 0001), produced by the [GameNotifications]
 * factory from the same gameplay event seams the audio system (UC31) uses, and consumed by the device-side
 * [com.orbitalfrontier.render.NotificationRenderer]. The [message] is kept to plain ASCII because the
 * bundled game font ships ASCII + `°` + `→` only (UC28); the renderer ellipsizes an over-long line.
 *
 * [severity] defaults to the [kind]'s [NotificationKind.defaultSeverity] and [coalesceKey] to the [kind]
 * itself, so two notifications of the same kind coalesce by default — overridable when a finer grouping is
 * ever needed without touching the queue.
 */
data class GameNotification(
    val kind: NotificationKind,
    val message: String,
    val severity: NotificationSeverity = kind.defaultSeverity,
    val coalesceKey: Any = kind,
)
