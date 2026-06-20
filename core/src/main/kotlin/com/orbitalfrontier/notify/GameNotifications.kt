package com.orbitalfrontier.notify

import com.orbitalfrontier.combat.CombatEvent

/**
 * The pure factory that turns a gameplay moment into a [GameNotification] (UC35 AC#1/#3).
 *
 * This is the notification analogue of [com.orbitalfrontier.audio.Sfx] — every builder is a pure function
 * of the event, so the screen layer can enqueue a toast straight from the same seams that already drive
 * sound and the autosave (jump, dock/undock, the mission life-cycle, the combat boundary, low fuel, credit
 * changes), keeping the feed **event-driven, never polled** and the deterministic core engine-free (AC#3).
 *
 * Messages are plain ASCII (the bundled font's glyph set, UC28); the renderer ellipsizes if needed.
 */
object GameNotifications {
    /** A completed inter-sector jump into [sectorName] (UC03). */
    fun jumpCompleted(sectorName: String): GameNotification = GameNotification(NotificationKind.JUMP_COMPLETED, "JUMPED TO $sectorName")

    /** Docked at [stationName] (UC05). */
    fun docked(stationName: String): GameNotification = GameNotification(NotificationKind.DOCKED, "DOCKED: $stationName")

    /** Returned to flight from a station (UC05). */
    fun undocked(): GameNotification = GameNotification(NotificationKind.UNDOCKED, "UNDOCKED")

    /** A mission offer was accepted (UC12). */
    fun missionAccepted(): GameNotification = GameNotification(NotificationKind.MISSION_ACCEPTED, "MISSION ACCEPTED")

    /** A mission was turned in / completed (UC12). */
    fun missionCompleted(): GameNotification = GameNotification(NotificationKind.MISSION_COMPLETED, "MISSION COMPLETE")

    /** A timed courier mission lapsed before turn-in (UC12). */
    fun missionFailedTimeout(): GameNotification = GameNotification(NotificationKind.MISSION_FAILED_TIMEOUT, "MISSION EXPIRED")

    /** A hostile encounter began (UC13). */
    fun enteredCombat(): GameNotification = GameNotification(NotificationKind.ENTERED_COMBAT, "HOSTILES ENGAGED")

    /** The hostile encounter ended (UC13). */
    fun leftCombat(): GameNotification = GameNotification(NotificationKind.LEFT_COMBAT, "COMBAT ENDED")

    /** The fuel tank crossed into the low-fuel band (UC07). */
    fun lowFuel(): GameNotification = GameNotification(NotificationKind.LOW_FUEL, "LOW FUEL")

    /**
     * The player tried to buy something they cannot afford (UC40 AC#3) — a styled [NotificationKind.
     * INSUFFICIENT_CREDITS] error toast in place of the old bare status string. The classification (cost
     * vs. balance) is made by the pure [com.orbitalfrontier.economy.PurchaseGate] at the call site; this is
     * just the cue.
     */
    fun insufficientCredits(): GameNotification = GameNotification(NotificationKind.INSUFFICIENT_CREDITS, "INSUFFICIENT CREDITS")

    /**
     * The player attempted an economy action the resolver refused for a reason other than funds (UC40 AC#3)
     * — a styled [NotificationKind.ACTION_REJECTED] error toast. [reason] is a short ASCII line (the bundled
     * font's glyph set, UC28; the renderer ellipsizes); it defaults to a generic message for callers that
     * cannot name the specific cause.
     */
    fun actionRejected(reason: String = "ACTION UNAVAILABLE"): GameNotification = GameNotification(NotificationKind.ACTION_REJECTED, reason)

    /**
     * The crew wage drain could not be fully paid this period (UC50 AC#2) — a styled [NotificationKind.
     * UNPAID_WAGES] WARNING toast. The wallet clamped at 0 (no debt / no desertion in the MVP, ADR 0038);
     * this is the player-facing cue that upkeep went unpaid.
     */
    fun unpaidWages(): GameNotification = GameNotification(NotificationKind.UNPAID_WAGES, "UNPAID WAGES")

    /**
     * The notification for a credit change from [old] to [new], or `null` when the balance is unchanged
     * (UC35 AC#1). A rise is a [NotificationKind.CREDIT_GAIN] ("+N CR"); a drop a
     * [NotificationKind.CREDIT_LOSS] ("-N CR"). The single chokepoint
     * [com.orbitalfrontier.screen.PlayScreen]'s credit-mutation sites route through calls this, so any
     * credit gain or loss surfaces consistently regardless of which system moved the wallet.
     */
    fun creditDelta(
        old: Long,
        new: Long,
    ): GameNotification? {
        if (new == old) return null
        val delta = new - old
        return if (delta > 0L) {
            GameNotification(NotificationKind.CREDIT_GAIN, "+$delta CR")
        } else {
            GameNotification(NotificationKind.CREDIT_LOSS, "-${-delta} CR")
        }
    }

    /**
     * The player's standing with the faction named [factionName] changed by [delta] (UC43) — e.g.
     * "INDEPENDENTS -5" after destroying one of its ships. Takes a plain display **String** (not a
     * `FactionId`/`Faction`) so the `notify` package stays decoupled from `faction` (the purity guard
     * keeps `notify` engine-free and dependency-light); the call site resolves the faction's display
     * name. The sign is always shown (a loss as "-N", a gain as "+N"). ASCII only (the bundled font's
     * glyph set, UC28; the renderer ellipsizes if needed).
     */
    fun reputationChanged(
        factionName: String,
        delta: Int,
    ): GameNotification {
        val signed = if (delta >= 0) "+$delta" else "$delta"
        return GameNotification(NotificationKind.REPUTATION_CHANGED, "$factionName $signed")
    }

    /**
     * The notification a combat [event] should surface, or `null` for events that must NOT toast (UC35
     * AC#1; flood-defense layer 1).
     *
     * A DIRECT mirror of [com.orbitalfrontier.audio.Sfx.forCombatEvent]: every per-tick combat event
     * (player/hostile fire, a hit, a kill, a break-off, the player's own destruction) maps to `null` so the
     * feed never becomes a wall of per-shot toasts — only the encounter *boundary* is notable, and the
     * *end* of an encounter is [CombatEvent.EncounterCleared] → [leftCombat]. (The *start* of an encounter
     * has no event in the [CombatEvent] hierarchy — there is no `EncounterStarted` — so the screen detects
     * it on the combat-active rising edge and calls [enteredCombat] directly.) An exhaustive `when` with no
     * `else` forces a deliberate decision here if a future [CombatEvent] subtype is ever added, exactly like
     * the SFX mapping.
     */
    fun forCombatEvent(event: CombatEvent): GameNotification? =
        when (event) {
            CombatEvent.EncounterCleared -> leftCombat()
            is CombatEvent.PlayerFired,
            is CombatEvent.HostileFired,
            is CombatEvent.HostileHit,
            is CombatEvent.HostileDestroyed,
            is CombatEvent.HostileBrokeOff,
            is CombatEvent.PlayerHit,
            CombatEvent.PlayerDestroyed,
            -> null
        }
}
