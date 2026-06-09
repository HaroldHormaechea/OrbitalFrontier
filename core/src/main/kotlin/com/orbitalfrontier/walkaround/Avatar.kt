package com.orbitalfrontier.walkaround

import com.orbitalfrontier.common.Vec2

/**
 * The on-foot player avatar (UC19): a position and a unit [facing] direction.
 *
 * Rendered as a ball with a small facing dot (AC#3). Pure data (no libGDX types) so the movement
 * model stays JVM-unit-testable per ADR 0001. [facing] is expected to be a unit vector; the model
 * keeps it normalized and retains the last facing when the stick is released (AC#4).
 */
data class Avatar(
    val position: Vec2,
    val facing: Vec2,
) {
    companion object {
        /** Default facing for a freshly-spawned avatar (pointing +x, toward the corridor). */
        val DEFAULT_FACING = Vec2(1f, 0f)

        /** An avatar spawned at [position] with the [DEFAULT_FACING]. */
        fun spawnedAt(position: Vec2): Avatar = Avatar(position = position, facing = DEFAULT_FACING)
    }
}
