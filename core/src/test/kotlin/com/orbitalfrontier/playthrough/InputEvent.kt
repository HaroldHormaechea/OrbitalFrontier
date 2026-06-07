package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.MovementInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded input, stamped with the [tick] it applies to (UC02 AC#3).
 *
 * A `sealed` polymorphic hierarchy (Open/Closed — coding-guidelines "O"): later use cases add new
 * input kinds by adding a new subtype, not by editing a central `when`. kotlinx.serialization emits
 * a `"type"` discriminator so the on-disk form stays self-describing and forward-readable.
 *
 * The recorder and runner support **0..N events per tick** from the start — multiple distinct
 * inputs (e.g. a movement vector *and* an action tap) can share one tick.
 */
@Serializable
sealed class InputEvent {
    /** The simulation tick this event is applied at (0-based). */
    abstract val tick: Int
}

/**
 * A movement-stick sample for one tick — the joystick vector, deflection magnitude, and whether
 * the stick was released. Mirrors [MovementInput] (the per-frame value [com.orbitalfrontier.sim.Simulation]
 * consumes) as flat serializable fields so the domain type stays annotation-free.
 */
@Serializable
@SerialName("movement")
data class MovementEvent(
    override val tick: Int,
    val dirX: Float,
    val dirY: Float,
    val magnitude: Float,
    val released: Boolean,
) : InputEvent() {
    /** Reconstruct the domain [MovementInput] this event recorded. */
    fun toMovementInput(): MovementInput =
        MovementInput(
            targetDirection = Vec2(dirX, dirY),
            magnitude = magnitude,
            released = released,
        )

    companion object {
        /** Capture [input] at [tick] as a serializable event. */
        fun from(
            tick: Int,
            input: MovementInput,
        ): MovementEvent =
            MovementEvent(
                tick = tick,
                dirX = input.targetDirection.x,
                dirY = input.targetDirection.y,
                magnitude = input.magnitude,
                released = input.released,
            )
    }
}
