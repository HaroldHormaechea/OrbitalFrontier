package com.orbitalfrontier.combat

import com.orbitalfrontier.crew.TurretOperability

/**
 * A ship-mounted weapon in the pure combat model (UC13 AC#1/#2). A `sealed` hierarchy
 * (coding-guidelines § O) of the two MVP weapon kinds, both plain value data (no engine types) so the
 * model is fully JVM-testable.
 *
 * Every weapon fires a straight-line [Projectile] of [damage] at [projectileSpeed] that expires after
 * travelling [range] world-units, on a per-mount [cooldownSeconds] timer. All numbers are authored
 * `[TUNE]` placeholders carried in [CombatParams] / [com.orbitalfrontier.combat.HostileArchetype].
 */
sealed interface Weapon {
    /** Hit points removed from the struck ship's chosen section per projectile. */
    val damage: Int

    /** Seconds between shots from this mount. */
    val cooldownSeconds: Float

    /** Projectile muzzle speed (world-units/s). */
    val projectileSpeed: Float

    /** Max projectile travel (world-units) before it expires harmlessly. */
    val range: Float
}

/**
 * A **fixed/forward** weapon (UC13 AC#1): fired by the player's [FireAction.FIRE] along the hull
 * facing (and by hostiles toward the player). No auto-aim, no crew requirement.
 */
data class FixedWeapon(
    override val damage: Int,
    override val cooldownSeconds: Float,
    override val projectileSpeed: Float,
    override val range: Float,
) : Weapon {
    init {
        require(damage > 0) { "FixedWeapon damage must be positive: $damage" }
        require(cooldownSeconds > 0f) { "FixedWeapon cooldownSeconds must be positive: $cooldownSeconds" }
        require(projectileSpeed > 0f) { "FixedWeapon projectileSpeed must be positive: $projectileSpeed" }
        require(range > 0f) { "FixedWeapon range must be positive: $range" }
    }
}

/**
 * An **auto-aim turret** (UC13 AC#2): acquires and tracks the priority hostile automatically
 * ([TargetingPriority]) and fires without a player action — but **requires crew** to operate
 * ([requiredCrew], gated through the shared [TurretOperability]). Below the crew requirement the turret
 * is inoperable and never fires.
 */
data class Turret(
    override val damage: Int,
    override val cooldownSeconds: Float,
    override val projectileSpeed: Float,
    override val range: Float,
    /** Minimum crew aboard for this turret to operate (UC13 AC#2; per-turret, supersedes the UC11 flag). */
    val requiredCrew: Int,
) : Weapon {
    init {
        require(damage > 0) { "Turret damage must be positive: $damage" }
        require(cooldownSeconds > 0f) { "Turret cooldownSeconds must be positive: $cooldownSeconds" }
        require(projectileSpeed > 0f) { "Turret projectileSpeed must be positive: $projectileSpeed" }
        require(range > 0f) { "Turret range must be positive: $range" }
        require(requiredCrew >= 0) { "Turret requiredCrew must not be negative: $requiredCrew" }
    }

    /** True when [crew] aboard is enough to operate this turret (UC13 AC#2, via [TurretOperability]). */
    fun operableWith(crew: Int): Boolean = TurretOperability.turretsOperable(crew, requiredCrew)
}

/**
 * A ship's full weapon fit (UC13 AC#1/#2) — its fixed/forward weapons and its turrets — derived from
 * `type + loadout` by [com.orbitalfrontier.outfit.ShipStats.weaponLoadout]. Pure value data; ordered
 * lists (never sets) so iteration is deterministic for replay.
 */
data class WeaponLoadout(
    val fixed: List<FixedWeapon> = emptyList(),
    val turrets: List<Turret> = emptyList(),
) {
    /** The turrets operable with [crew] aboard (UC13 AC#2) — the only turrets that auto-fire. */
    fun operableTurrets(crew: Int): List<Turret> = turrets.filter { it.operableWith(crew) }

    companion object {
        /** No weapons fitted — a ship that cannot fire. */
        val NONE: WeaponLoadout = WeaponLoadout()
    }
}
