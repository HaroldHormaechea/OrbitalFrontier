package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2

/**
 * Auto-aim turret target selection (UC13 AC#2/#7) — **nearest-first**, with a fully deterministic
 * **total order** so the same encounter always picks the same target on replay.
 *
 * The order is: **ascending distance²** from the firing point, then — to break ties between two
 * equidistant hostiles — **ascending [HostileId]** (the monotonic spawn id). This is a *total* order
 * with no reliance on `hashCode()`, object identity, or list position, which is exactly what keeps
 * targeting byte-stable (the determinism rule for UC13). Distance is compared *squared* (no `sqrt`),
 * both for speed and to avoid any floating-point rounding a square root would introduce.
 */
object TargetingPriority {
    /**
     * A [Comparator] over hostiles implementing the total order (ascending distance² from [from], then
     * ascending [HostileId]). Exposed so callers can sort or `minWith` consistently.
     */
    fun comparator(from: Vec2): Comparator<Hostile> =
        compareBy({ distanceSquared(from, it.kinematics.position) }, { it.id.value })

    /** The priority target among [hostiles] from [from], or null if there are none. */
    fun selectTarget(
        from: Vec2,
        hostiles: List<Hostile>,
    ): Hostile? = hostiles.minWithOrNull(comparator(from))

    /** Squared Euclidean distance between [a] and [b] (no `sqrt`, so no rounding in the ordering key). */
    private fun distanceSquared(
        a: Vec2,
        b: Vec2,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
