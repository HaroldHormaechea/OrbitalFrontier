package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import kotlin.math.atan2

/**
 * The rule-based enemy AI (UC13 AC#4 — **no ML**). A pure function from a hostile's state + its
 * archetype + the player's position to a per-tick [Decision] (where to point, whether to thrust,
 * whether to fire). Deterministic and JVM-testable; the *behaviour* is selected by the archetype's
 * [AiBehavior] **data**, and the thresholds/ranges come from archetype data too, so difficulty is
 * data-driven rather than branch-driven.
 *
 * Two MVP behaviours:
 *  - [AiBehavior.AGGRESSIVE] — always turns toward the player, thrusts to close, and fires once within
 *    the archetype's `engageRange`.
 *  - [AiBehavior.FLEE_WHEN_DAMAGED] — behaves aggressively **while healthy**, but once its hull drops
 *    below `fleeHullFraction` it turns directly away and runs, holding fire (the AC#6 disengage path
 *    the player can exploit by out-damaging a skittish enemy).
 */
object EnemyAi {
    /** One tick's AI output: the heading to face, whether to thrust along it, and whether to fire. */
    data class Decision(
        val desiredHeading: Float,
        val thrust: Boolean,
        val wantsToFire: Boolean,
    )

    /** Decide [hostile]'s action this tick against the player at [playerPosition]. */
    fun decide(
        hostile: Hostile,
        archetype: HostileArchetype,
        playerPosition: Vec2,
    ): Decision {
        val toPlayer = playerPosition - hostile.kinematics.position
        val distance = toPlayer.length
        val headingToPlayer = atan2(toPlayer.y, toPlayer.x)

        val fleeing =
            archetype.behavior == AiBehavior.FLEE_WHEN_DAMAGED &&
                hostile.hullFraction(archetype) < archetype.fleeHullFraction

        return if (fleeing) {
            // Turn 180° from the player and run; don't fire while fleeing.
            Decision(desiredHeading = atan2(-toPlayer.y, -toPlayer.x), thrust = true, wantsToFire = false)
        } else {
            // Close on the player; fire when in range (and actually have a bearing — distance > 0).
            Decision(
                desiredHeading = headingToPlayer,
                thrust = true,
                wantsToFire = distance > 0f && distance <= archetype.engageRange,
            )
        }
    }
}
