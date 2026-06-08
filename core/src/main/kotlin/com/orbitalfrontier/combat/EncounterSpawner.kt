package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import kotlin.math.PI

/**
 * Natural-encounter spawning (UC13 — encounters are natural + mission-spawned). Pure and deterministic:
 * a function of the player's previous and new position, the [EncounterZone] geometry and a [spawnTick],
 * so the device and replay spawn the identical fight.
 *
 * **Edge-triggered (the key design point).** A natural encounter fires once, on the **outside→inside**
 * crossing of the zone radius — `previousPosition` outside, `newPosition` inside — *not* every tick the
 * player is inside. This is what makes the AC#6 flee/outrun loop work: a player who outruns the hostiles
 * past their leash and leaves the zone is not instantly re-ambushed while still inside; only re-entering
 * (another outside→inside crossing) can spawn again. A spawn is also suppressed while a fight is already
 * [CombatState.active], so two zones (or a re-cross during combat) can't stack encounters.
 *
 * The encounter RNG is seeded `"encounter:${zone.id}:${spawnTick}"` (replayed state only, never the
 * wall clock), and each hostile is placed at [CombatParams.spawnDistance] from the player on an
 * RNG-jittered bearing, facing the player.
 *
 * A thin **mission-spawn** hook ([missionSpawn]) lets the (deferred) combat-mission type inject the same
 * shape of encounter without a positional crossing — the model is identical, only the trigger differs.
 */
object EncounterSpawner {
    /**
     * If the player just crossed from outside to inside [zone] and no fight is active, spawn the zone's
     * hostiles and return the new active [CombatState]; otherwise return [combat] **unchanged** (same
     * instance). An unknown archetype id degrades to no spawn (returns [combat]).
     */
    fun naturalSpawn(
        combat: CombatState,
        zone: EncounterZone,
        previousPosition: Vec2,
        newPosition: Vec2,
        spawnTick: Int,
        params: CombatParams = CombatParams(),
    ): CombatState {
        if (combat.active) return combat
        val crossedIn = !zone.contains(previousPosition) && zone.contains(newPosition)
        if (!crossedIn) return combat
        return spawn(zone.id, zone.archetypeId, zone.hostileCount, newPosition, spawnTick, params)
    }

    /**
     * Spawn [hostileCount] hostiles of [archetypeId] around [playerPosition] as a mission-driven
     * encounter (the deferred combat-mission hook), keyed [zoneId]/[spawnTick]. Same model as a natural
     * spawn; suppressed while a fight is already active.
     */
    fun missionSpawn(
        combat: CombatState,
        zoneId: String,
        archetypeId: HostileArchetypeId,
        hostileCount: Int,
        playerPosition: Vec2,
        spawnTick: Int,
        params: CombatParams = CombatParams(),
    ): CombatState {
        if (combat.active) return combat
        return spawn(zoneId, archetypeId, hostileCount, playerPosition, spawnTick, params)
    }

    private fun spawn(
        zoneId: String,
        archetypeId: HostileArchetypeId,
        hostileCount: Int,
        playerPosition: Vec2,
        spawnTick: Int,
        params: CombatParams,
    ): CombatState {
        // Degrade gracefully on an unknown authored archetype — no spawn rather than a crash.
        HostileArchetypes.byId(archetypeId) ?: return CombatState.NONE

        var state =
            CombatState(
                active = true,
                zoneId = zoneId,
                rngState = CombatRng.seeded("encounter:$zoneId:$spawnTick"),
            )

        repeat(hostileCount) {
            val (angleRoll, advanced) = state.rngState.nextFloat()
            val bearing = angleRoll * TWO_PI
            val position = playerPosition + Vec2.fromAngle(bearing, params.spawnDistance)
            // Face back toward the player (bearing + 180°), at rest — the AI accelerates it from here.
            val facingPlayer = bearing + PI.toFloat()
            val kinematics = ShipKinematics(position = position, headingRadians = facingPlayer)
            state = state.copy(rngState = advanced).spawnHostile(archetypeId, kinematics)
        }
        return state
    }

    private const val TWO_PI: Float = (2.0 * PI).toFloat()
}
