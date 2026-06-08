package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2

/**
 * Stable identity of an in-flight [Projectile] within a [CombatState] (UC13). A `value class` over a
 * `Long`, allocated from [CombatState.nextProjectileId] so ids are **monotonic** and never reused —
 * giving projectiles a total order for deterministic, replay-stable iteration (never `hashCode`/
 * identity).
 */
@JvmInline
value class ProjectileId(val value: Long) : Comparable<ProjectileId> {
    override fun compareTo(other: ProjectileId): Int = value.compareTo(other.value)
}

/** Who fired a [Projectile] — decides whom it can hit (UC13). A closed `enum`. */
enum class ProjectileOwner {
    /** Fired by the player ship; hits hostiles. */
    PLAYER,

    /** Fired by a hostile; hits the player. */
    HOSTILE,
}

/**
 * One in-flight projectile in the pure combat sim (UC13). Travels in a straight line at [velocity],
 * dealing [damage] to the first eligible ship within hit radius, and expires once [remainingRange]
 * world-units are exhausted (so shots don't live forever). Pure value data (no engine types), so the
 * whole [CombatState] is a comparable immutable snapshot.
 *
 * [targetHostileId] records the hostile a player auto-aim/turret shot was launched at (null for fixed
 * forward fire and all hostile fire); it is advisory for hit resolution and lets the renderer tint a
 * tracking shot, but collision is still positional.
 */
data class Projectile(
    val id: ProjectileId,
    val owner: ProjectileOwner,
    val position: Vec2,
    val velocity: Vec2,
    val damage: Int,
    val remainingRange: Float,
    val targetHostileId: HostileId? = null,
) {
    init {
        require(damage > 0) { "Projectile damage must be positive: $damage" }
    }

    /**
     * Advance this projectile by [dt] seconds: move along [velocity] and consume the travelled distance
     * from [remainingRange]. Returns the moved projectile (its [expired] flag tells the sim to cull it).
     */
    fun advanced(dt: Float): Projectile {
        val step = velocity * dt
        return copy(position = position + step, remainingRange = remainingRange - step.length)
    }

    /** True once the projectile has travelled its full range and should be culled. */
    val expired: Boolean get() = remainingRange <= 0f
}
