package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.ship.ShipKinematics
import kotlin.math.floor

/**
 * The forgiving destruction → respawn rule (UC13 AC#5 — **no permadeath**). A pure function: when the
 * player's hull is destroyed, relocate them to their **last docked station**, apply a partial
 * **cargo-loss** penalty, fully repair the ship and clear the encounter. Deterministic and
 * JVM-testable; the device and replay run identical respawns.
 *
 * Credits and progression are untouched — the only cost is a slice of the hold ([RespawnResult.cargo])
 * and the interruption. The cargo lost is `floor(usedUnits * respawnCargoLossFraction)` units, jettisoned
 * **deterministically in [ResourceType] declaration order** (the same canonical order
 * [com.orbitalfrontier.ship.OwnedShip] uses to clamp a shrunk hold), so a replay loses exactly the same
 * units.
 */
object Respawn {
    /** The outcome of a respawn: where the ship reappears, its lightened hold, repaired sections, no combat. */
    data class RespawnResult(
        val kinematics: ShipKinematics,
        val cargo: Cargo,
        val sectionDamage: SectionDamage,
        val combat: CombatState,
        val unitsLost: Int,
    )

    /**
     * Respawn the player at [stationPosition] (their last docked station) with [cargo] lightened by the
     * penalty, sections fully repaired and [CombatState.NONE] — at rest, hull facing preserved-as-zero
     * (a fresh spawn heading). [params] supplies the cargo-loss fraction.
     */
    fun respawn(
        stationPosition: Vec2,
        cargo: Cargo,
        params: CombatParams,
    ): RespawnResult {
        val (lightened, lost) = jettison(cargo, params.respawnCargoLossFraction)
        return RespawnResult(
            kinematics = ShipKinematics(position = stationPosition, velocity = Vec2.ZERO),
            cargo = lightened,
            sectionDamage = SectionDamages.fullRepair(),
            combat = CombatState.NONE,
            unitsLost = lost,
        )
    }

    /**
     * Drop `floor(usedUnits * fraction)` units from [cargo], removing in [ResourceType] declaration
     * order until the loss budget is spent. Returns the lightened cargo and the units actually lost.
     */
    private fun jettison(
        cargo: Cargo,
        fraction: Float,
    ): Pair<Cargo, Int> {
        val toLose = floor(cargo.usedUnits * fraction).toInt().coerceIn(0, cargo.usedUnits)
        if (toLose <= 0) return cargo to 0

        var result = cargo
        var remaining = toLose
        for (resource in ResourceType.entries) {
            if (remaining <= 0) break
            val held = result.contents[resource] ?: 0
            if (held <= 0) continue
            val take = held.coerceAtMost(remaining)
            result = result.remove(resource, take)
            remaining -= take
        }
        return result to (toLose - remaining)
    }
}
