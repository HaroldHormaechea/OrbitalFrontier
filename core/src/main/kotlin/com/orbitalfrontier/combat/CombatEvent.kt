package com.orbitalfrontier.combat

/**
 * A discrete thing that happened during one [Combat.step] (UC13) — the model's structured output the
 * device layer turns into log lines, autosave triggers (e.g. a destroyed hostile, a player respawn) and
 * feedback, and the replay harness can assert on (UC13 AC#8). A `sealed` hierarchy (coding-guidelines
 * § O); pure value data, emitted in deterministic order.
 *
 * Events are advisory: the authoritative outcome is the returned [CombatState] / [SectionDamage] /
 * `destroyed` flag. A no-op tick (inactive combat, no fire) emits the empty list (same instance).
 */
sealed interface CombatEvent {
    /** The player fired this tick — [turret] true for an auto-aim turret shot, false for fixed/forward. */
    data class PlayerFired(val turret: Boolean) : CombatEvent

    /** Hostile [id] fired at the player this tick. */
    data class HostileFired(val id: HostileId) : CombatEvent

    /** A player shot struck hostile [id] in [section] (the RNG-weighted hit location, AC#3). */
    data class HostileHit(val id: HostileId, val section: ShipSection) : CombatEvent

    /** Hostile [id]'s hull reached 0 HP and it was destroyed/removed (AC#8). */
    data class HostileDestroyed(val id: HostileId) : CombatEvent

    /** Hostile [id] ran past its leash range and broke off the encounter (the outrun path, AC#6). */
    data class HostileBrokeOff(val id: HostileId) : CombatEvent

    /** A hostile shot struck the player in [section] (sectional damage recorded, AC#3/#8). */
    data class PlayerHit(val section: ShipSection) : CombatEvent

    /** The player's hull reached 0 HP — destruction; the caller runs [Respawn] (no permadeath, AC#5). */
    data object PlayerDestroyed : CombatEvent

    /** The last hostile was removed (destroyed or broke off) — the encounter ended (combat → NONE). */
    data object EncounterCleared : CombatEvent
}
