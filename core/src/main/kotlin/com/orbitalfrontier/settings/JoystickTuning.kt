package com.orbitalfrontier.settings

/**
 * Persisted virtual-joystick tuning (UC37): a sensitivity multiplier and an input deadzone.
 *
 * A pure value type beside [AudioSettings] / [Handedness] / [ControlsLayout] — no engine types, so it
 * is fully JVM-testable and round-trips through the settings store unchanged (ADR 0001). It is applied
 * at exactly one place — the [com.orbitalfrontier.screen.controls.MovementJoystick] input boundary —
 * so the pure movement model and the record/replay harness are untouched (determinism preserved;
 * ADR 0006).
 *
 * - [sensitivity] scales the raw stick magnitude: `> 1` reaches full thrust at a smaller deflection,
 *   `< 1` softens it. [coerced] clamps it to `0.25f..3.0f`.
 * - [deadzone] is the deflection below which the stick reports no input. [coerced] clamps it to
 *   `MIN_DEADZONE..0.9f`; the lower bound equals the pure model's own floor
 *   ([com.orbitalfrontier.ship.ShipMovementParams.inputDeadzone] = 0.15), so the tuning can only ever
 *   *widen* the deadzone, never narrow it below what the simulation already ignores.
 *
 * [coerced] collapses any out-of-range or non-finite value a corrupt save (or a future control) might
 * produce to a safe in-range value, so the input boundary can apply the result without re-validating.
 */
data class JoystickTuning(
    val sensitivity: Float,
    val deadzone: Float,
) {
    /** This value with both fields clamped to their valid ranges (NaN/∞ collapse to the field default). */
    fun coerced(): JoystickTuning {
        val s = clamp(sensitivity, MIN_SENSITIVITY, MAX_SENSITIVITY, DEFAULT.sensitivity)
        val d = clamp(deadzone, MIN_DEADZONE, MAX_DEADZONE, DEFAULT.deadzone)
        return if (s == sensitivity && d == deadzone) this else copy(sensitivity = s, deadzone = d)
    }

    /**
     * Map a raw stick magnitude (`0f..1f`) to the effective magnitude the joystick reports, applying
     * this tuning: gate first (a deflection below [deadzone] is no input → `0f`), then scale by
     * [sensitivity] and cap at `1f`. Deliberately **no rescale** of the surviving range — a softer/
     * harsher curve, not a remap — so the feel is predictable and the boundary stays trivial.
     *
     * Self-coercing: it reads through [coerced] so a half-constructed value still behaves sanely.
     */
    fun apply(rawMagnitude: Float): Float {
        val c = coerced()
        if (rawMagnitude.isNaN() || rawMagnitude < c.deadzone) return 0f
        return (rawMagnitude * c.sensitivity).coerceAtMost(1f)
    }

    companion object {
        /** Default tuning: neutral sensitivity, deadzone at the model floor (no extra dead band). */
        val DEFAULT = JoystickTuning(sensitivity = 1.0f, deadzone = MIN_DEADZONE)

        /** Sensitivity clamp range. */
        const val MIN_SENSITIVITY = 0.25f
        const val MAX_SENSITIVITY = 3.0f

        /**
         * Deadzone clamp range. [MIN_DEADZONE] equals [com.orbitalfrontier.ship.ShipMovementParams]'s
         * `inputDeadzone` (0.15) — the magnitude the pure model already treats as no input — so the
         * tuning can only widen the dead band, never shrink it below the simulation's own floor.
         */
        const val MIN_DEADZONE = 0.15f
        const val MAX_DEADZONE = 0.9f

        private fun clamp(
            value: Float,
            min: Float,
            max: Float,
            fallback: Float,
        ): Float =
            when {
                value.isNaN() -> fallback
                value < min -> min
                value > max -> max
                else -> value
            }
    }
}
