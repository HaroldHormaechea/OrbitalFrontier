package com.orbitalfrontier.combat

import com.orbitalfrontier.ship.ShipKinematics

/**
 * The whole live combat encounter at one tick (UC13) — the transient value [Combat.step] reads and
 * produces. Pure, immutable, **engine-free** value data so it composes into the world snapshot and is
 * fully JVM-testable / replay-comparable (UC13 AC#7).
 *
 * **Determinism invariants (HARD):**
 *  - [hostiles] is kept **sorted by [HostileId]** and [projectiles] by [ProjectileId] — a total order,
 *    never `hashCode`/identity ordering — so iteration is byte-stable across a replay.
 *  - [rngState] is the functional [CombatRng] threaded through every seeded choice (damage section
 *    pick, spawn jitter); it advances only when the sim actually draws.
 *  - [nextHostileId] / [nextProjectileId] are **monotonic** allocators (ids never reused).
 *  - **No `HashMap`/`HashSet` anywhere** — only ordered `List`s — to forbid iteration-order nondeterminism.
 *
 * **Transient, never row-persisted.** Hostiles, projectiles and the combat RNG are regenerated from the
 * seeded encounter, not saved (a mid-combat save reloads with combat cleared — see ADR 0012). Only the
 * player's durable combat state (section damage, last docked station) persists, on the ship / world.
 *
 * [active] distinguishes "no encounter" ([NONE]) from a live fight. [zoneId] records which
 * [EncounterZone] spawned this fight so the edge-triggered spawner won't immediately re-ambush.
 */
data class CombatState(
    val active: Boolean = false,
    val zoneId: String = "",
    val hostiles: List<Hostile> = emptyList(),
    val projectiles: List<Projectile> = emptyList(),
    val rngState: CombatRng = CombatRng(0L),
    val nextHostileId: Long = 0L,
    val nextProjectileId: Long = 0L,
    /** Seconds until the player's fixed/forward weapon can fire again (UC13 AC#1). */
    val playerFixedCooldown: Float = 0f,
    /** Seconds until the player's auto-aim turret(s) can fire again (UC13 AC#2). */
    val playerTurretCooldown: Float = 0f,
) {
    /** True when no hostiles remain in a live encounter — the fight is won and the sim can clear it. */
    val cleared: Boolean get() = active && hostiles.isEmpty()

    /**
     * Append a hostile of [archetypeId] at [kinematics], allocating it the next monotonic [HostileId]
     * and keeping [hostiles] sorted (append preserves order since ids only increase). Returns the new
     * state; used by [EncounterSpawner].
     */
    fun spawnHostile(
        archetypeId: HostileArchetypeId,
        kinematics: ShipKinematics,
    ): CombatState {
        val hostile = Hostile(id = HostileId(nextHostileId), archetypeId = archetypeId, kinematics = kinematics)
        return copy(hostiles = hostiles + hostile, nextHostileId = nextHostileId + 1L)
    }

    /**
     * Append [build]'s projectile, allocating it the next monotonic [ProjectileId] (passed to [build]),
     * keeping [projectiles] ordered by id. Returns the new state.
     */
    fun spawnProjectile(build: (ProjectileId) -> Projectile): CombatState {
        val projectile = build(ProjectileId(nextProjectileId))
        return copy(projectiles = projectiles + projectile, nextProjectileId = nextProjectileId + 1L)
    }

    companion object {
        /**
         * The "no encounter" sentinel — inactive, empty, RNG at 0. The default for
         * [com.orbitalfrontier.world.WorldState.combat]; a [Combat.step] on [NONE] with no fire intent
         * is a total no-op returning the SAME instances (the pre-UC13 byte-identical anchor).
         */
        val NONE: CombatState = CombatState()
    }
}
