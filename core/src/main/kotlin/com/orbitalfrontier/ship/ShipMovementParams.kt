package com.orbitalfrontier.ship

import kotlin.math.PI

/**
 * Per-ship movement tuning parameters (see docs/design/ship-and-controls.md).
 *
 * All defaults are **[TUNE]** placeholders chosen for a 50/50 arcade/sim feel; concrete
 * values do not exist yet (use-case 01 "Potential Pitfalls"). They are expressed in the
 * pure model's own units (world units = the Box2D simulation metres mapped by
 * [com.orbitalfrontier.ship.ShipPhysics]) and are intended to be sourced from ship config /
 * upgrades later, not hard-coded at call sites.
 *
 * Linear units are world-units; angular units are radians.
 */
data class ShipMovementParams(
    /** Top forward speed cap (world-units/s). [TUNE] */
    val maxSpeed: Float = 120f,
    /** Forward thrust acceleration at full stick along facing (world-units/s²). [TUNE] */
    val maxAcceleration: Float = 80f,
    /** Reverse-thrust speed cap; reverse is intentionally slower than forward (world-units/s). [TUNE] */
    val maxReverseSpeed: Float = 60f,
    /** How fast turn-rate ramps up (rad/s²). [TUNE] */
    val rotationAcceleration: Float = 6.0f,
    /** Turn-rate cap (rad/s). [TUNE] */
    val maxRotationSpeed: Float = 3.5f,
    /** Auto-stop deceleration applied to speed when the stick is released (world-units/s²). [TUNE] */
    val driftDecay: Float = 40f,
    /**
     * Half-angle of the forward-thrust cone: when the signed angle between hull facing and
     * the stick target is within this, the ship thrusts forward. Default 90°. [TUNE]
     */
    val forwardConeRadians: Float = (PI / 2.0).toFloat(),
    /**
     * Threshold beyond which the stick is considered "opposite" the hull and reverse thrust
     * applies. Between the forward cone and this is a no-thrust deadzone. Default 135°. [TUNE]
     */
    val reverseConeRadians: Float = (PI * 3.0 / 4.0).toFloat(),
    /** Stick deflection below this magnitude (0..1) is treated as no input. [TUNE] */
    val inputDeadzone: Float = 0.15f,
) {
    init {
        require(maxSpeed > 0f) { "maxSpeed must be positive: $maxSpeed" }
        require(maxAcceleration > 0f) { "maxAcceleration must be positive: $maxAcceleration" }
        require(maxReverseSpeed >= 0f) { "maxReverseSpeed must be non-negative: $maxReverseSpeed" }
        require(rotationAcceleration > 0f) { "rotationAcceleration must be positive: $rotationAcceleration" }
        require(maxRotationSpeed > 0f) { "maxRotationSpeed must be positive: $maxRotationSpeed" }
        require(driftDecay >= 0f) { "driftDecay must be non-negative: $driftDecay" }
        require(forwardConeRadians in 0f..reverseConeRadians) {
            "forwardConeRadians ($forwardConeRadians) must be in 0..reverseConeRadians ($reverseConeRadians)"
        }
        require(inputDeadzone in 0f..1f) { "inputDeadzone must be in 0..1: $inputDeadzone" }
    }
}
