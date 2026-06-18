package com.orbitalfrontier.notify

/**
 * The catalogue of discrete gameplay moments the notification feed surfaces (UC35 AC#1).
 *
 * One entry per event *family* AC#1 names — jump completion, docking/undocking, the mission life-cycle,
 * the combat boundary, low fuel, and credit gain/loss. Each kind carries its own:
 *  - [defaultSeverity] — the styling intent ([NotificationSeverity]) the device renderer colours by, so the
 *    classification lives with the model and not in the draw code; and
 *  - [coalescable] — whether bursts of the *same* kind collapse into a single live toast (flood-defense
 *    layer 2 in [NotificationQueue]). Discrete one-shot events (a jump, a dock) are never coalesced; events
 *    that can fire repeatedly in quick succession (low fuel re-tripping, a run of credit changes, several
 *    couriers timing out together) are, so the queue can't flood (AC#2 pitfall).
 *
 * Pure, engine-free (no libGDX) — it names the cues but owns no colour/asset; the
 * [com.orbitalfrontier.render.NotificationRenderer] binds those. Scanned wholesale by the UC35 purity guard.
 */
enum class NotificationKind(
    val defaultSeverity: NotificationSeverity,
    val coalescable: Boolean,
) {
    /** An inter-sector jump gate was traversed (UC03). */
    JUMP_COMPLETED(NotificationSeverity.INFO, coalescable = false),

    /** The ship docked at a station (UC05). */
    DOCKED(NotificationSeverity.INFO, coalescable = false),

    /** The ship returned to flight from a station (UC05). */
    UNDOCKED(NotificationSeverity.INFO, coalescable = false),

    /** A mission offer was accepted (UC12). */
    MISSION_ACCEPTED(NotificationSeverity.INFO, coalescable = false),

    /** A mission was turned in / completed (UC12). */
    MISSION_COMPLETED(NotificationSeverity.INFO, coalescable = false),

    /** A timed (courier) mission expired before turn-in (UC12) — coalesced as several can lapse together. */
    MISSION_FAILED_TIMEOUT(NotificationSeverity.WARNING, coalescable = true),

    /** A hostile encounter began (UC13). */
    ENTERED_COMBAT(NotificationSeverity.WARNING, coalescable = false),

    /** The hostile encounter ended (UC13). */
    LEFT_COMBAT(NotificationSeverity.INFO, coalescable = false),

    /** The fuel tank crossed into the low-fuel band (UC07) — coalesced so a flickering edge can't flood. */
    LOW_FUEL(NotificationSeverity.WARNING, coalescable = true),

    /** Credits increased (a reward, a sale) — coalesced so a burst of gains collapses into one toast. */
    CREDIT_GAIN(NotificationSeverity.INFO, coalescable = true),

    /** Credits decreased (a purchase, a penalty) — coalesced so a burst of spends collapses into one toast. */
    CREDIT_LOSS(NotificationSeverity.WARNING, coalescable = true),
}
