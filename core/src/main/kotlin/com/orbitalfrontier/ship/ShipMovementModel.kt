package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Pure, deterministic ship-movement integrator (AC#2–#6, #12).
 *
 * Given the previous [ShipKinematics], the player's [MovementInput], the ship's
 * [ShipMovementParams] and a timestep, it returns the next velocity/heading/angular-velocity
 * state. It contains **no** libGDX or Android types and performs no I/O, so the full set of
 * behaviours (rotate-toward, forward/reverse thrust cones, inertial drift, release decay, and
 * the speed/turn-rate clamps) is unit-testable on the JVM.
 *
 * Note on responsibility split (ADR 0005): this model is the *velocity authority* — it computes
 * the velocity and angular velocity the ship should have. Position/rotation **integration** is
 * delegated to Box2D via [ShipPhysics] on device; in pure tests we integrate position here too
 * (semi-implicit Euler) so the math is self-contained and verifiable.
 */
class ShipMovementModel {
    /**
     * Advance [state] by [dt] seconds under [input] and [params]. [dt] must be > 0.
     *
     * The returned [ShipKinematics.position] is integrated from the new velocity (semi-implicit
     * Euler) purely so the model is testable end-to-end; on device [ShipPhysics] owns position
     * and only the velocity/heading fields of the result are pushed to the body.
     */
    fun update(
        state: ShipKinematics,
        input: MovementInput,
        params: ShipMovementParams,
        dt: Float,
    ): ShipKinematics {
        require(dt > 0f) { "dt must be positive: $dt" }

        val hasInput = !input.released && input.magnitude > params.inputDeadzone

        // Signed angle from current hull facing to the stick target, in (-PI, PI].
        // Undefined without input, so default to 0 (no steering demand) when released.
        val theta: Float =
            if (hasInput) {
                val targetAngle = atan2(input.targetDirection.y, input.targetDirection.x)
                wrapToPi(targetAngle - state.headingRadians)
            } else {
                0f
            }

        val newAngularVelocity = stepAngularVelocity(state.angularVelocity, theta, hasInput, params, dt)
        val newHeading = wrapToPi(state.headingRadians + newAngularVelocity * dt)

        // Thrust is applied along the *current* facing (consistent with the angle theta we tested).
        val facing = Vec2.fromAngle(state.headingRadians)
        val acceleration = thrustAcceleration(theta, hasInput, input.magnitude, facing, params)

        var velocity = state.velocity + acceleration * dt
        if (!hasInput) {
            velocity = decay(velocity, params.driftDecay, dt)
        }
        velocity = applySpeedCaps(velocity, facing, isReverseThrust(theta, hasInput, params), params)

        val newPosition = state.position + velocity * dt

        return ShipKinematics(
            position = newPosition,
            velocity = velocity,
            headingRadians = newHeading,
            angularVelocity = newAngularVelocity,
        )
    }

    /**
     * Ramp angular velocity toward the target angle [theta] using [ShipMovementParams.rotationAcceleration]
     * and capping at [ShipMovementParams.maxRotationSpeed]. Uses an "arrive" target speed
     * (v = sqrt(2·a·|θ|)) so the hull brakes as it approaches alignment instead of overshooting.
     * With no steering input, angular velocity is ramped back toward zero at the same rate.
     */
    private fun stepAngularVelocity(
        current: Float,
        theta: Float,
        hasInput: Boolean,
        params: ShipMovementParams,
        dt: Float,
    ): Float {
        val maxDelta = params.rotationAcceleration * dt
        val targetAngularVelocity =
            if (!hasInput) {
                0f
            } else {
                val arriveSpeed = sqrt(2f * params.rotationAcceleration * abs(theta))
                val capped = arriveSpeed.coerceAtMost(params.maxRotationSpeed)
                sign(theta) * capped
            }
        return approach(current, targetAngularVelocity, maxDelta)
            .coerceIn(-params.maxRotationSpeed, params.maxRotationSpeed)
    }

    /**
     * Acceleration vector for this frame based on where the stick points relative to the hull:
     * - |θ| ≤ forward cone  → thrust forward along facing, scaled by cos(θ)·stickMag (smoothly
     *   tapering to zero at the cone edge).
     * - |θ| ≥ reverse cone  → thrust in reverse along −facing, scaled by |cos(θ)|·stickMag.
     * - between the cones    → no thrust (deadzone); momentum is retained.
     */
    private fun thrustAcceleration(
        theta: Float,
        hasInput: Boolean,
        magnitude: Float,
        facing: Vec2,
        params: ShipMovementParams,
    ): Vec2 {
        if (!hasInput) return Vec2.ZERO
        val stick = magnitude.coerceIn(0f, 1f)
        val absTheta = abs(theta)
        return when {
            absTheta <= params.forwardConeRadians -> {
                facing * (params.maxAcceleration * cos(theta) * stick)
            }
            absTheta >= params.reverseConeRadians -> {
                // cos(theta) is negative here; magnitude of reverse thrust grows toward 180°.
                facing * (params.maxAcceleration * cos(theta) * stick)
            }
            else -> Vec2.ZERO
        }
    }

    private fun isReverseThrust(
        theta: Float,
        hasInput: Boolean,
        params: ShipMovementParams,
    ): Boolean = hasInput && abs(theta) >= params.reverseConeRadians

    /** Smoothly decay speed toward zero by [driftDecay]·[dt], never reversing past zero. */
    private fun decay(
        velocity: Vec2,
        driftDecay: Float,
        dt: Float,
    ): Vec2 {
        val speed = velocity.length
        if (speed == 0f) return Vec2.ZERO
        val newSpeed = (speed - driftDecay * dt).coerceAtLeast(0f)
        return velocity * (newSpeed / speed)
    }

    /**
     * Enforce the speed caps:
     * - total speed is always clamped to [ShipMovementParams.maxSpeed] (hard top-speed cap, AC#6);
     * - while actively reverse-thrusting, the backward-along-facing component is additionally
     *   clamped to [ShipMovementParams.maxReverseSpeed] (AC#5). Drift (no thrust) is never
     *   clamped against the reverse cap so inertial momentum is preserved (AC#3).
     */
    private fun applySpeedCaps(
        velocity: Vec2,
        facing: Vec2,
        reverseThrusting: Boolean,
        params: ShipMovementParams,
    ): Vec2 {
        var result = velocity.limit(params.maxSpeed)
        if (reverseThrusting) {
            val forwardComponent = result.dot(facing) // signed; negative means moving backward
            val backward = -forwardComponent
            if (backward > params.maxReverseSpeed) {
                val lateral = result - facing * forwardComponent
                result = lateral + facing * (-params.maxReverseSpeed)
            }
        }
        return result
    }

    private companion object {
        /** Move [current] toward [target] by at most [maxDelta]. */
        fun approach(
            current: Float,
            target: Float,
            maxDelta: Float,
        ): Float {
            val diff = target - current
            return if (abs(diff) <= maxDelta) target else current + sign(diff) * maxDelta
        }

        /** Wrap [angle] (radians) into the half-open range (-PI, PI]. */
        fun wrapToPi(angle: Float): Float {
            val twoPi = (2.0 * PI).toFloat()
            val pi = PI.toFloat()
            var x = (angle + pi) % twoPi
            if (x <= 0f) x += twoPi
            return x - pi
        }
    }
}
