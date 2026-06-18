package com.orbitalfrontier.audio

import com.orbitalfrontier.combat.CombatEvent

/**
 * The catalogue of one-shot sound effects the game can play (UC31 AC#1).
 *
 * This is a **pure**, engine-free enum: it names the gameplay cues but carries no libGDX `Sound`
 * handle or asset path (those live in the platform-side audio service under `render/`). Keeping it in
 * the engine-free `audio` package — scanned by the UC31 purity guard — lets the deterministic core and
 * the screen layer decide *which* cue fires from a gameplay event without ever touching the engine,
 * exactly like the `settings` value types (ADR 0001 JVM-testability; UC31 AC#4).
 */
enum class Sfx {
    /** Engine ignition / thrust cue, fired on the thrust start transition. */
    THRUST,

    /** The player's weapon discharged this tick. */
    WEAPON_FIRE,

    /** A player shot struck a hostile. */
    ENEMY_HIT,

    /** A hostile was destroyed. */
    ENEMY_DESTROYED,

    /** One productive mining extraction tick landed. */
    MINING_TICK,

    /** The ship docked at a station. */
    DOCK,

    /** The ship traversed an inter-sector jump gate. */
    JUMP,

    /** A UI button was tapped. */
    UI_TAP,

    /** A mission offer was accepted. */
    MISSION_ACCEPT,

    /** A mission was turned in / completed. */
    MISSION_COMPLETE,
    ;

    companion object {
        /**
         * The SFX cue a combat [event] should trigger, or `null` for events with no audio (UC31 AC#1/#4).
         *
         * Pure mapping over the [CombatEvent] sealed hierarchy — the device audio layer calls this on the
         * events emitted by [com.orbitalfrontier.combat.Combat.step] so combat sound stays driven by the
         * model's structured output, never embedded inside the deterministic simulation. Only the three
         * cues UC31 AC#1 calls out (player fire, hostile hit, hostile destroyed) map to a sound; the
         * remaining events are intentionally silent so combat doesn't become a wall of noise.
         */
        fun forCombatEvent(event: CombatEvent): Sfx? =
            when (event) {
                is CombatEvent.PlayerFired -> WEAPON_FIRE
                is CombatEvent.HostileHit -> ENEMY_HIT
                is CombatEvent.HostileDestroyed -> ENEMY_DESTROYED
                is CombatEvent.HostileFired,
                is CombatEvent.HostileBrokeOff,
                is CombatEvent.PlayerHit,
                CombatEvent.PlayerDestroyed,
                CombatEvent.EncounterCleared,
                -> null
            }
    }
}
