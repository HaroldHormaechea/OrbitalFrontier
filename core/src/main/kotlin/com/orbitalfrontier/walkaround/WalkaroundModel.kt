package com.orbitalfrontier.walkaround

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.MovementInput
import kotlin.math.sqrt

/**
 * Pure on-foot movement integrator for the walk-around prototype (UC19).
 *
 * Direct movement — **no inertia/drift** (unlike the ship's [com.orbitalfrontier.ship.ShipMovementModel]):
 * the avatar moves exactly as far as the stick says this frame and stops the instant the stick is
 * released. Reuses the ship's [MovementInput] for the virtual joystick (AC#4) so the input layer is
 * shared. Free of libGDX types and fully deterministic (same inputs → same output) so it is
 * JVM-unit-testable per ADR 0001.
 *
 * Collision is a **loose single-point destination clamp** (AC#8): the integrator computes the desired
 * destination, then snaps it back into the walkable union via [StationInterior.clampToWalkable].
 * Fast-tunneling across a thin gap is accepted for the prototype.
 */
class WalkaroundModel {
    /**
     * Advance the avatar by one frame.
     *
     * - Stick **active**: translate by `dir * moveSpeed * magnitude * dt`, set `facing = dir`, then
     *   clamp the destination back into the walkable area.
     * - Stick **released** (or no usable direction): position frozen, previous facing retained (AC#4).
     */
    fun update(
        avatar: Avatar,
        interior: StationInterior,
        input: MovementInput,
        params: WalkaroundParams,
        dt: Float,
    ): Avatar {
        // Released → no movement; keep the last facing so the dot doesn't snap (AC#4 / edge case).
        if (input.released) return avatar

        val dir = input.targetDirection.normalizedOrZero()
        // A zero/degenerate stick direction this frame: don't move and don't blank the facing.
        if (dir == Vec2.ZERO) return avatar

        val distance = params.moveSpeed * input.magnitude * dt
        val desired = avatar.position + dir * distance
        val clamped = interior.clampToWalkable(desired)
        return avatar.copy(position = clamped, facing = dir)
    }

    /** True when [avatar] is within the shopkeeper's interact radius (AC#6). */
    fun isNearShopkeeper(
        avatar: Avatar,
        interior: StationInterior,
        params: WalkaroundParams,
    ): Boolean {
        val dx = avatar.position.x - interior.shopkeeperPosition.x
        val dy = avatar.position.y - interior.shopkeeperPosition.y
        return sqrt(dx * dx + dy * dy) <= params.shopkeeperInteractRadius
    }
}
