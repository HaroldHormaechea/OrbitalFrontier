package com.orbitalfrontier.render

import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.Hostile
import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.combat.HostileId
import com.orbitalfrontier.combat.TargetingPriority
import com.orbitalfrontier.common.Vec2

/**
 * One hostile's combat-HUD health bar (UC44 AC#2): its stable [id], its world [position] (where the bar
 * floats), its current [hullFraction] (0..1, the bar fill) and a distance [fade] (0..1 alpha) so distant
 * hostiles dim out and do not clutter the screen (the "multiple simultaneous hostiles" pitfall).
 *
 * Pure value data — engine-free (no libGDX types), so the whole derivation is JVM-unit-testable against
 * the combat model (UC44 AC#5) exactly like the rest of `core` (ADR 0001).
 */
data class HostileHealthBar(
    val id: HostileId,
    val position: Vec2,
    val hullFraction: Float,
    val fade: Float,
)

/**
 * The derived, render-ready combat-HUD overlay state for one frame (UC44 AC#1/#2) — which hostile the
 * auto-aim turrets are locked onto ([lockedTargetId], the reticle target) and the per-hostile health
 * [bars]. **Derived, never persisted**: it is a pure *read* of the live [CombatState] (render reads
 * state — coding-guidelines § simulation vs render), so it introduces no [com.orbitalfrontier.combat]
 * model change, no schema bump and nothing new on the replay path (the test-set `Simulation` mirror
 * runs the same `Combat.step` and renders no HUD, so playthroughs stay byte-identical, ADR 0006/0012).
 *
 * **Lock parity with the firing point (HARD).** [lockedTargetId] is computed from the SAME call the
 * turrets fire through — [TargetingPriority.selectTarget] with the player's kinematic position — and is
 * suppressed (null) whenever no turret would actually fire (no operable turret, or the TURRET section is
 * disabled). So the reticle is honest to what the guns are doing rather than inventing a target.
 */
data class CombatHudState(
    val lockedTargetId: HostileId?,
    val bars: List<HostileHealthBar>,
) {
    companion object {
        /** The empty overlay — no lock, no bars. Drawn as a no-op; the default while combat is inactive. */
        val EMPTY: CombatHudState = CombatHudState(lockedTargetId = null, bars = emptyList())

        /**
         * Cap on simultaneously-drawn health bars (the clutter pitfall): the [MAX_BARS] **nearest**
         * hostiles get a bar, the rest are dropped. The nearest are kept because the player is most
         * likely engaging them (and the turret target is always the nearest, so the locked hostile is
         * never dropped).
         */
        const val MAX_BARS: Int = 6

        /** Within this distance (world units) a bar is fully opaque ([fade] = 1). */
        const val NEAR_RANGE: Float = 600f

        /** Beyond this distance (world units) a bar has faded fully out ([fade] = 0). */
        const val FAR_RANGE: Float = 1600f

        /**
         * Derive the frame's overlay from the live [combat] state. [playerPos] MUST be the player's
         * kinematic position (the same source [com.orbitalfrontier.combat.Combat.step] feeds into the
         * turret target selection) so the reticle points exactly where the guns shoot. [hasOperableTurrets]
         * / [turretDisabled] mirror the turret-fire gate; when no turret would fire the lock is suppressed.
         *
         * A no-op (the [EMPTY] state) when combat is inactive or there are no hostiles.
         */
        fun build(
            combat: CombatState,
            playerPos: Vec2,
            hasOperableTurrets: Boolean,
            turretDisabled: Boolean,
        ): CombatHudState {
            if (!combat.active || combat.hostiles.isEmpty()) return EMPTY

            // Lock target == the turret's auto-aim pick (same firing point), suppressed when no turret fires.
            val lockedTargetId =
                if (hasOperableTurrets && !turretDisabled) {
                    TargetingPriority.selectTarget(playerPos, combat.hostiles)?.id
                } else {
                    null
                }

            // Bars for the MAX_BARS nearest hostiles, in the TargetingPriority total order (distance² then
            // HostileId) so the selection is deterministic and the locked (nearest) hostile is always kept.
            val bars =
                combat.hostiles
                    .sortedWith(TargetingPriority.comparator(playerPos))
                    .take(MAX_BARS)
                    .map { hostile -> barFor(hostile, playerPos) }

            return CombatHudState(lockedTargetId = lockedTargetId, bars = bars)
        }

        /** Build one hostile's bar: its hull fraction from the model + a distance fade from [playerPos]. */
        private fun barFor(
            hostile: Hostile,
            playerPos: Vec2,
        ): HostileHealthBar {
            val archetype = HostileArchetypes.byId(hostile.archetypeId)
            val hullFraction = archetype?.let(hostile::hullFraction) ?: 0f
            val position = hostile.kinematics.position
            return HostileHealthBar(
                id = hostile.id,
                position = position,
                hullFraction = hullFraction,
                fade = fadeFor(distanceSquared(playerPos, position)),
            )
        }

        /**
         * Distance fade (1 → 0) from a squared distance: fully opaque at/under [NEAR_RANGE], fully clear
         * at/over [FAR_RANGE], linear between. Compared in **squared** distance (no `sqrt`) — still
         * monotonic in actual distance, so a nearer hostile always reads at least as bright as a farther
         * one. Constants squared once per call (cheap; clarity over micro-caching at HUD scale).
         */
        private fun fadeFor(distanceSquared: Float): Float {
            val near2 = NEAR_RANGE * NEAR_RANGE
            val far2 = FAR_RANGE * FAR_RANGE
            return when {
                distanceSquared <= near2 -> 1f
                distanceSquared >= far2 -> 0f
                else -> 1f - (distanceSquared - near2) / (far2 - near2)
            }
        }

        /** Squared Euclidean distance between [a] and [b] (no `sqrt`). */
        private fun distanceSquared(
            a: Vec2,
            b: Vec2,
        ): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return dx * dx + dy * dy
        }
    }
}
