package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2

/**
 * One frame of movement intent from the left virtual joystick, in world space.
 *
 * Produced by the input layer (a Scene2D touchpad on device, fabricated directly in tests),
 * consumed by [ShipMovementModel]. Kept free of engine types so the model stays JVM-testable.
 *
 * @property targetDirection the direction the player is pushing the stick (need not be a unit
 *   vector; the model uses its angle). Meaningless when [released] is true.
 * @property magnitude stick deflection in 0..1; values at/under the params' deadzone are no input.
 * @property released true when the player is not touching the stick this frame (triggers drift decay).
 */
data class MovementInput(
    val targetDirection: Vec2,
    val magnitude: Float,
    val released: Boolean,
) {
    companion object {
        /** No active steering input — the canonical "stick released" value. */
        val NONE = MovementInput(targetDirection = Vec2.ZERO, magnitude = 0f, released = true)
    }
}
