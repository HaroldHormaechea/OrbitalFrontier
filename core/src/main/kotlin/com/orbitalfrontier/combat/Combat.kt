package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics

/**
 * The player's combat-relevant state handed into one [Combat.step] (UC13): the ship's [kinematics],
 * its derived [weapons] ([com.orbitalfrontier.outfit.ShipStats.weaponLoadout]) and per-section [maxSectionHp]
 * ([com.orbitalfrontier.outfit.ShipStats.sectionHp]), the [crew] aboard (the turret operability gate,
 * AC#2) and the ship's current [sectionDamage]. Pure value data — the screen assembles it each tick.
 */
data class PlayerCombatInput(
    val kinematics: ShipKinematics,
    val weapons: WeaponLoadout,
    val maxSectionHp: Map<ShipSection, Int>,
    val crew: Int,
    val sectionDamage: SectionDamage,
) {
    /** Max HP of [section] for this ship (0 if the ship has no such section). */
    fun maxHp(section: ShipSection): Int = maxSectionHp[section] ?: 0
}

/** The result of one [Combat.step]: the new combat state, the player's new section damage, destruction + events. */
data class CombatStepResult(
    val combat: CombatState,
    val sectionDamage: SectionDamage,
    val destroyed: Boolean,
    val events: List<CombatEvent>,
)

/**
 * **The** shared real-time combat tick (UC13 AC#1–#8) — the single pure function the device loop and
 * the replay harness both call, so live and replayed combat are byte-identical (AC#7). One call advances
 * the encounter by [dt] seconds: player fixed fire ([FireAction]) and auto-aim turret fire (crew-gated),
 * rule-based hostile AI + movement + fire, projectile flight, RNG-weighted sectional damage on every hit,
 * destruction/cull, and the flee/leash break-off.
 *
 * **Anchor contract (HARD).** When [combat] is inactive ([CombatState.active] = false) the step is a
 * total no-op: it returns the **same** [combat] and [PlayerCombatInput.sectionDamage] instances, advances
 * **no** RNG and emits the empty (singleton) event list. Pre-UC13 fixtures are always inactive and never
 * fire, so they replay byte-for-byte.
 *
 * Determinism: hostiles/projectiles are processed in their [CombatState] total order; the only randomness
 * is the section-hit pick, drawn from the threaded [CombatRng]; no `HashMap`/`HashSet`, no engine types,
 * no wall clock.
 */
object Combat {
    fun step(
        combat: CombatState,
        player: PlayerCombatInput,
        fireAction: FireAction,
        params: CombatParams,
        dt: Float,
    ): CombatStepResult {
        // Inactive encounter: nothing ticks, firing has nothing to hit. Same instances, no RNG, no events.
        if (!combat.active) {
            return CombatStepResult(combat, player.sectionDamage, destroyed = false, events = emptyList())
        }

        val events = ArrayList<CombatEvent>()
        var rng = combat.rngState
        val hostiles = ArrayList(combat.hostiles)
        val projectiles = ArrayList(combat.projectiles)
        var nextProjectileId = combat.nextProjectileId
        var playerDamage = player.sectionDamage
        var playerFixedCd = (combat.playerFixedCooldown - dt).coerceAtLeast(0f)
        var playerTurretCd = (combat.playerTurretCooldown - dt).coerceAtLeast(0f)

        val playerPos = player.kinematics.position

        fun emit(
            owner: ProjectileOwner,
            origin: Vec2,
            direction: Vec2,
            weapon: Weapon,
            target: HostileId?,
        ) {
            val dir = direction.normalizedOrZero()
            if (dir == Vec2.ZERO) return
            projectiles.add(
                Projectile(
                    id = ProjectileId(nextProjectileId++),
                    owner = owner,
                    position = origin + dir * params.muzzleOffset,
                    velocity = dir * weapon.projectileSpeed,
                    damage = weapon.damage,
                    remainingRange = weapon.range,
                    targetHostileId = target,
                ),
            )
        }

        // --- Player fixed/forward fire (AC#1): along hull facing, gated on cooldown + WEAPON not disabled.
        val weaponDisabled = SectionDamages.isDestroyed(playerDamage, ShipSection.WEAPON, player.maxHp(ShipSection.WEAPON))
        if (fireAction == FireAction.FIRE && playerFixedCd <= 0f && player.weapons.fixed.isNotEmpty() && !weaponDisabled) {
            val facing = Vec2.fromAngle(player.kinematics.headingRadians)
            var maxCd = 0f
            for (w in player.weapons.fixed) {
                emit(ProjectileOwner.PLAYER, playerPos, facing, w, target = null)
                if (w.cooldownSeconds > maxCd) maxCd = w.cooldownSeconds
            }
            playerFixedCd = maxCd
            events.add(CombatEvent.PlayerFired(turret = false))
        }

        // --- Player turret auto-fire (AC#2): crew-gated, auto-aim at the priority target, TURRET not disabled.
        val turretDisabled = SectionDamages.isDestroyed(playerDamage, ShipSection.TURRET, player.maxHp(ShipSection.TURRET))
        val operableTurrets = player.weapons.operableTurrets(player.crew)
        if (operableTurrets.isNotEmpty() && playerTurretCd <= 0f && !turretDisabled && hostiles.isNotEmpty()) {
            val target = TargetingPriority.selectTarget(playerPos, hostiles)
            if (target != null) {
                val toTarget = target.kinematics.position - playerPos
                var maxCd = 0f
                var fired = false
                for (t in operableTurrets) {
                    if (toTarget.length <= t.range) {
                        emit(ProjectileOwner.PLAYER, playerPos, toTarget, t, target = target.id)
                        if (t.cooldownSeconds > maxCd) maxCd = t.cooldownSeconds
                        fired = true
                    }
                }
                if (fired) {
                    playerTurretCd = maxCd
                    events.add(CombatEvent.PlayerFired(turret = true))
                }
            }
        }

        // --- Hostile AI + movement + fire (AC#4): each hostile in id order.
        for (i in hostiles.indices) {
            val h = hostiles[i]
            val archetype = HostileArchetypes.byId(h.archetypeId) ?: continue
            val decision = EnemyAi.decide(h, archetype, playerPos)

            var velocity = h.kinematics.velocity
            var heading = h.kinematics.headingRadians
            if (decision.thrust) {
                val dir = Vec2.fromAngle(decision.desiredHeading)
                velocity = (velocity + dir * (archetype.acceleration * dt)).limit(archetype.maxSpeed)
                heading = decision.desiredHeading
            }
            val position = h.kinematics.position + velocity * dt

            var cooldown = (h.cooldownRemaining - dt).coerceAtLeast(0f)
            if (decision.wantsToFire && cooldown <= 0f) {
                emit(ProjectileOwner.HOSTILE, position, playerPos - position, archetype.weapon, target = null)
                cooldown = archetype.weapon.cooldownSeconds
                events.add(CombatEvent.HostileFired(h.id))
            }

            hostiles[i] =
                h.copy(
                    kinematics = h.kinematics.copy(position = position, velocity = velocity, headingRadians = heading),
                    cooldownRemaining = cooldown,
                )
        }

        // --- Advance projectiles, then resolve collisions (RNG-weighted sectional damage, AC#3).
        for (i in projectiles.indices) projectiles[i] = projectiles[i].advanced(dt)

        val survivingProjectiles = ArrayList<Projectile>(projectiles.size)
        for (p in projectiles) {
            if (p.expired) continue
            when (p.owner) {
                ProjectileOwner.PLAYER -> {
                    val idx = nearestHostileWithin(hostiles, p.position, params.hitRadius)
                    if (idx < 0) {
                        survivingProjectiles.add(p)
                    } else {
                        val h = hostiles[idx]
                        val archetype = HostileArchetypes.byId(h.archetypeId)
                        if (archetype != null) {
                            val hit =
                                DamageModel.applyHit(
                                    sectionDamage = h.sectionDamage,
                                    maxSectionHp = archetype.sectionHp,
                                    hitDamage = p.damage,
                                    weights = params.sectionHitWeights,
                                    rng = rng,
                                )
                            rng = hit.rng
                            hostiles[idx] = h.copy(sectionDamage = hit.sectionDamage)
                            events.add(CombatEvent.HostileHit(h.id, hit.section))
                        }
                        // projectile consumed on hit
                    }
                }
                ProjectileOwner.HOSTILE -> {
                    if ((p.position - playerPos).length <= params.hitRadius) {
                        val hit =
                            DamageModel.applyHit(
                                sectionDamage = playerDamage,
                                maxSectionHp = player.maxSectionHp,
                                hitDamage = p.damage,
                                weights = params.sectionHitWeights,
                                rng = rng,
                            )
                        rng = hit.rng
                        playerDamage = hit.sectionDamage
                        events.add(CombatEvent.PlayerHit(hit.section))
                        // projectile consumed on hit
                    } else {
                        survivingProjectiles.add(p)
                    }
                }
            }
        }

        // --- Cull destroyed hostiles (AC#8) and break off any past their leash (the outrun path, AC#6).
        val aliveHostiles = ArrayList<Hostile>(hostiles.size)
        for (h in hostiles) {
            val archetype = HostileArchetypes.byId(h.archetypeId) ?: continue // unknown ⇒ drop
            if (h.isDestroyed(archetype)) {
                events.add(CombatEvent.HostileDestroyed(h.id))
                continue
            }
            if ((h.kinematics.position - playerPos).length > archetype.leashRange) {
                events.add(CombatEvent.HostileBrokeOff(h.id))
                continue
            }
            aliveHostiles.add(h)
        }

        // --- Player destruction (AC#5): hull at 0 ⇒ destroyed; caller runs Respawn. Combat cleared.
        val hullMax = player.maxHp(ShipSection.HULL)
        if (hullMax > 0 && SectionDamages.isDestroyed(playerDamage, ShipSection.HULL, hullMax)) {
            events.add(CombatEvent.PlayerDestroyed)
            return CombatStepResult(CombatState.NONE, playerDamage, destroyed = true, events = events)
        }

        // --- Encounter cleared: no hostiles remain ⇒ combat ends (→ NONE), so the screen exits combat.
        if (aliveHostiles.isEmpty()) {
            events.add(CombatEvent.EncounterCleared)
            return CombatStepResult(CombatState.NONE, playerDamage, destroyed = false, events = events)
        }

        val newCombat =
            combat.copy(
                hostiles = aliveHostiles,
                projectiles = survivingProjectiles,
                rngState = rng,
                nextProjectileId = nextProjectileId,
                playerFixedCooldown = playerFixedCd,
                playerTurretCooldown = playerTurretCd,
            )
        return CombatStepResult(newCombat, playerDamage, destroyed = false, events = events)
    }

    /**
     * Index of the priority hostile within [hitRadius] of [point] (nearest by [TargetingPriority]'s total
     * order), or -1 if none is in range. Used to resolve a player projectile against a single target.
     */
    private fun nearestHostileWithin(
        hostiles: List<Hostile>,
        point: Vec2,
        hitRadius: Float,
    ): Int {
        var bestIdx = -1
        var bestDistSq = hitRadius * hitRadius
        var bestId = Long.MAX_VALUE
        for (i in hostiles.indices) {
            val d = hostiles[i].kinematics.position - point
            val distSq = d.x * d.x + d.y * d.y
            if (distSq <= bestDistSq) {
                val id = hostiles[i].id.value
                // Total order: nearer wins; on an exact tie the lower HostileId wins (deterministic).
                if (bestIdx < 0 || distSq < bestDistSq || (distSq == bestDistSq && id < bestId)) {
                    bestIdx = i
                    bestDistSq = distSq
                    bestId = id
                }
            }
        }
        return bestIdx
    }
}
