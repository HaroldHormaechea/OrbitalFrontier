package com.orbitalfrontier.settings

import com.orbitalfrontier.ship.ShipMovementParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [JoystickTuning] (UC37) — the pure virtual-joystick tuning value type
 * (sensitivity multiplier + input deadzone).
 *
 * Covers the use case's control ACs at the pure layer (the engine-touching wiring is exercised
 * separately): **AC#2/#5** — every setting persists & applies, and **the edge-case pitfall** — invalid
 * values (e.g. zero sensitivity) must be clamped. The three behaviours under test are:
 *
 *  1. [JoystickTuning.coerced] clamps `sensitivity` to `[0.25 .. 3.0]` and `deadzone` to `[0.15 .. 0.9]`,
 *     collapsing NaN/∞ to the field default — so a corrupt save or a future control can never produce an
 *     out-of-range tuning.
 *  2. [JoystickTuning.apply] gates a raw stick magnitude below the deadzone to `0f`, then scales by
 *     sensitivity and caps at `1f` with **no rescale** of the surviving range (a softer/harsher curve,
 *     not a remap).
 *  3. [JoystickTuning.MIN_DEADZONE] equals the pure movement model's own floor
 *     ([ShipMovementParams.inputDeadzone]) — the anti-drift guard that keeps the tuning from ever
 *     narrowing the dead band below what the simulation already ignores.
 *
 * Pure value type (no engine types), so these run headless on the JVM (ADR 0001) and the tuning never
 * perturbs the deterministic movement model / replay (ADR 0006) — it is applied only at the joystick
 * input boundary.
 */
class JoystickTuningTest {
    // --- AC#2 / pitfall: sensitivity clamp [0.25 .. 3.0] (zero -> 0.25) ------------------------------

    @Test
    fun `zero sensitivity clamps up to the minimum`() {
        // The use case's named edge case: a zero sensitivity must not disable the stick.
        assertEquals(JoystickTuning.MIN_SENSITIVITY, JoystickTuning(0f, 0.15f).coerced().sensitivity, 0f)
    }

    @Test
    fun `below-range and negative sensitivity clamp up to the minimum`() {
        assertEquals(JoystickTuning.MIN_SENSITIVITY, JoystickTuning(0.1f, 0.15f).coerced().sensitivity, 0f)
        assertEquals(JoystickTuning.MIN_SENSITIVITY, JoystickTuning(-2f, 0.15f).coerced().sensitivity, 0f)
    }

    @Test
    fun `above-range sensitivity clamps down to the maximum`() {
        assertEquals(JoystickTuning.MAX_SENSITIVITY, JoystickTuning(99f, 0.15f).coerced().sensitivity, 0f)
    }

    @Test
    fun `an in-range sensitivity is left untouched`() {
        assertEquals(1.5f, JoystickTuning(1.5f, 0.4f).coerced().sensitivity, 0f)
    }

    @Test
    fun `a non-finite sensitivity collapses to the default`() {
        assertEquals(JoystickTuning.DEFAULT.sensitivity, JoystickTuning(Float.NaN, 0.3f).coerced().sensitivity, 0f)
    }

    // --- AC#2 / pitfall: deadzone clamp [0.15 .. 0.9] incl. sub-floor (0.05 -> 0.15) -----------------

    @Test
    fun `a sub-floor deadzone clamps up to the model floor`() {
        // 0.05 is below the simulation's own floor (0.15) and must be widened, never narrowed below it.
        assertEquals(JoystickTuning.MIN_DEADZONE, JoystickTuning(1f, 0.05f).coerced().deadzone, 0f)
    }

    @Test
    fun `a negative deadzone clamps up to the model floor`() {
        assertEquals(JoystickTuning.MIN_DEADZONE, JoystickTuning(1f, -0.5f).coerced().deadzone, 0f)
    }

    @Test
    fun `an above-range deadzone clamps down to the maximum`() {
        assertEquals(JoystickTuning.MAX_DEADZONE, JoystickTuning(1f, 0.99f).coerced().deadzone, 0f)
    }

    @Test
    fun `an in-range deadzone is left untouched`() {
        assertEquals(0.4f, JoystickTuning(1f, 0.4f).coerced().deadzone, 0f)
    }

    @Test
    fun `a NaN deadzone collapses to the default`() {
        assertEquals(JoystickTuning.DEFAULT.deadzone, JoystickTuning(1f, Float.NaN).coerced().deadzone, 0f)
    }

    @Test
    fun `infinite values clamp to the range bounds`() {
        // Only NaN collapses to the field default; ±infinity is ordered, so it clamps to the nearer bound.
        val high = JoystickTuning(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY).coerced()
        assertEquals("+inf sensitivity clamps to the maximum", JoystickTuning.MAX_SENSITIVITY, high.sensitivity, 0f)
        assertEquals("+inf deadzone clamps to the maximum", JoystickTuning.MAX_DEADZONE, high.deadzone, 0f)
        val low = JoystickTuning(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY).coerced()
        assertEquals("-inf sensitivity clamps to the minimum", JoystickTuning.MIN_SENSITIVITY, low.sensitivity, 0f)
        assertEquals("-inf deadzone clamps to the minimum", JoystickTuning.MIN_DEADZONE, low.deadzone, 0f)
    }

    @Test
    fun `coerced returns the same instance when already in range`() {
        // Micro-optimisation in the impl: an already-valid value is not re-copied.
        val tuning = JoystickTuning(1.5f, 0.4f)
        assertEquals(tuning, tuning.coerced())
    }

    // --- AC#2/#5: apply() gates below the deadzone, then scales and caps with NO rescale -------------

    @Test
    fun `a raw magnitude below the deadzone reports no input`() {
        val tuning = JoystickTuning(sensitivity = 1f, deadzone = 0.3f)
        assertEquals("just below the deadzone is gated to zero", 0f, tuning.apply(0.29f), 0f)
        assertEquals("zero deflection is no input", 0f, tuning.apply(0f), 0f)
    }

    @Test
    fun `a raw magnitude at or above the deadzone survives the gate`() {
        val tuning = JoystickTuning(sensitivity = 1f, deadzone = 0.3f)
        // At sensitivity 1.0 the surviving range passes through unchanged (no rescale).
        assertEquals("at the deadzone the raw magnitude passes through", 0.3f, tuning.apply(0.3f), 1e-6f)
        assertEquals("above the deadzone the raw magnitude passes through", 0.5f, tuning.apply(0.5f), 1e-6f)
    }

    @Test
    fun `sensitivity scales the surviving magnitude without rescaling the range`() {
        val tuning = JoystickTuning(sensitivity = 2f, deadzone = 0.15f)
        // 0.4 * 2 = 0.8 — a plain multiply of the raw magnitude, NOT a remap of [deadzone..1] onto [0..1].
        assertEquals(0.8f, tuning.apply(0.4f), 1e-6f)
    }

    @Test
    fun `the scaled magnitude is capped at one`() {
        val tuning = JoystickTuning(sensitivity = 3f, deadzone = 0.15f)
        assertEquals("0.5 * 3 = 1.5 caps at 1.0", 1f, tuning.apply(0.5f), 0f)
        assertEquals("full deflection caps at 1.0", 1f, tuning.apply(1f), 0f)
    }

    @Test
    fun `a NaN raw magnitude reports no input`() {
        assertEquals(0f, JoystickTuning.DEFAULT.apply(Float.NaN), 0f)
    }

    @Test
    fun `apply self-coerces a half-constructed tuning`() {
        // Out-of-range fields (sensitivity 10, deadzone -1) read through coerced() -> (3.0, 0.15), so the
        // boundary can apply the result without re-validating: 0.5 >= 0.15, 0.5 * 3 = 1.5 -> capped 1.0.
        val wild = JoystickTuning(sensitivity = 10f, deadzone = -1f)
        assertEquals(1f, wild.apply(0.5f), 0f)
    }

    // --- anti-drift guard: the deadzone floor equals the model's own inputDeadzone -------------------

    @Test
    fun `the minimum deadzone equals the pure movement model floor`() {
        // If ShipMovementParams.inputDeadzone changes, this guard fails — the tuning's floor must track it
        // so a player can only ever WIDEN the dead band, never narrow it below what the sim already ignores.
        assertEquals(
            "JoystickTuning.MIN_DEADZONE must equal ShipMovementParams().inputDeadzone (anti-drift)",
            ShipMovementParams().inputDeadzone,
            JoystickTuning.MIN_DEADZONE,
            0f,
        )
    }

    // --- DEFAULT reproduces today's (pre-UC37) behaviour --------------------------------------------

    @Test
    fun `the default is neutral sensitivity at the model-floor deadzone`() {
        assertEquals("default sensitivity is neutral", 1f, JoystickTuning.DEFAULT.sensitivity, 0f)
        assertEquals("default deadzone is the model floor", JoystickTuning.MIN_DEADZONE, JoystickTuning.DEFAULT.deadzone, 0f)
    }

    @Test
    fun `the default applies as an identity above the deadzone`() {
        // Pre-UC37 behaviour: neutral sensitivity means a surviving deflection is reported unchanged, and
        // anything below the model floor is no input — exactly what the joystick did before it was tunable.
        val default = JoystickTuning.DEFAULT
        assertEquals("below the floor is gated", 0f, default.apply(0.1f), 0f)
        assertEquals("at the floor passes through", JoystickTuning.MIN_DEADZONE, default.apply(JoystickTuning.MIN_DEADZONE), 1e-6f)
        assertEquals("a mid deflection passes through unchanged", 0.6f, default.apply(0.6f), 1e-6f)
        assertEquals("full deflection passes through unchanged", 1f, default.apply(1f), 0f)
    }

    @Test
    fun `the default is already in range`() {
        assertEquals(JoystickTuning.DEFAULT, JoystickTuning.DEFAULT.coerced())
    }

    @Test
    fun `a softened sensitivity really differs from neutral above the deadzone`() {
        // Sanity: the multiplier actually changes the reported magnitude (guards an accidental no-op impl).
        val soft = JoystickTuning(sensitivity = 0.5f, deadzone = 0.15f)
        assertNotEquals(JoystickTuning.DEFAULT.apply(0.8f), soft.apply(0.8f))
        assertEquals(0.4f, soft.apply(0.8f), 1e-6f)
    }
}
