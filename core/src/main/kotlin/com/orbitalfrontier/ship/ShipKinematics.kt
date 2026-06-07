package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2

/**
 * Immutable snapshot of a ship's spatial state — the value the pure
 * [ShipMovementModel] reads and produces each frame.
 *
 * This is the *model's* view of kinematics (plain [Float]s, no engine types). On device it
 * is kept in sync with the Box2D body by [ShipPhysics]: the body is the integrator of record
 * (AC#10), this is the velocity authority the model computes (AC#12). [headingRadians] is the
 * hull facing measured CCW from the +x axis.
 */
data class ShipKinematics(
    val position: Vec2 = Vec2.ZERO,
    val velocity: Vec2 = Vec2.ZERO,
    val headingRadians: Float = 0f,
    val angularVelocity: Float = 0f,
) {
    /** Current scalar speed (world-units/s), regardless of direction. */
    val speed: Float get() = velocity.length
}
