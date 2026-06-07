package com.orbitalfrontier.ship

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * JVM unit tests for the pure [ShipMovementModel] (AC#2–#6, #12).
 *
 * These run on a bare JVM with no libGDX/Box2D/Android types loaded, which is itself the
 * verification of AC#12 (the movement math is testable off-device via DIP / `core` purity).
 * One behaviour per test; backtick names per the coding guidelines.
 */
class ShipMovementModelTest {
    private val model = ShipMovementModel()
    private val params = ShipMovementParams()

    private fun input(
        dir: Vec2,
        magnitude: Float = 1f,
    ) = MovementInput(targetDirection = dir, magnitude = magnitude, released = false)

    // --- AC#2: rotate-toward then thrust ---

    @Test
    fun `hull rotates toward the stick target`() {
        // Facing +x (heading 0); stick points straight up (+y, PI/2 away).
        val next =
            model.update(
                ShipKinematics(headingRadians = 0f),
                input(Vec2(0f, 1f)),
                params,
                dt = 0.1f,
            )

        assertTrue("angular velocity should be positive (turning CCW toward +y)", next.angularVelocity > 0f)
        assertTrue("heading should advance from 0 toward PI/2", next.headingRadians > 0f)
        assertTrue("heading should not overshoot PI/2 in one frame", next.headingRadians < (PI / 2).toFloat())
    }

    @Test
    fun `thrust is applied forward along facing when stick is aligned`() {
        // Facing +x, stick pointing +x → pure forward thrust, no rotation.
        val next =
            model.update(
                ShipKinematics(headingRadians = 0f),
                input(Vec2(1f, 0f)),
                params,
                dt = 0.1f,
            )

        assertTrue("velocity should build along +x", next.velocity.x > 0f)
        assertEquals("no lateral velocity for an aligned thrust", 0f, next.velocity.y, 1e-4f)
    }

    @Test
    fun `forward thrust is scaled down by the cosine of the off-axis angle`() {
        val headOn =
            model.update(ShipKinematics(headingRadians = 0f), input(Vec2(1f, 0f)), params, dt = 0.1f)
        // 45 deg off axis: still inside the forward cone, but thrust scaled by cos(45deg).
        val offAxis =
            model.update(ShipKinematics(headingRadians = 0f), input(Vec2(1f, 1f)), params, dt = 0.1f)

        assertTrue(
            "head-on thrust should exceed off-axis thrust",
            headOn.velocity.x > offAxis.velocity.x,
        )
        assertTrue("off-axis thrust is still forward", offAxis.velocity.x > 0f)
    }

    // --- AC#3: inertial drift / momentum ---

    @Test
    fun `velocity is retained as momentum rather than halting instantly on release`() {
        val moving = ShipKinematics(velocity = Vec2(50f, 0f), headingRadians = 0f)

        val next = model.update(moving, MovementInput.NONE, params, dt = 0.1f)

        assertTrue("ship keeps moving (drift, not instant stop)", next.speed > 0f)
        assertTrue("but slower than before (decaying)", next.speed < 50f)
    }

    // --- AC#4: release decay to zero, never past ---

    @Test
    fun `released velocity decays smoothly by the drift-decay rate`() {
        // driftDecay 40 u/s^2 over 0.1s removes 4 u/s from a 50 u/s speed.
        val moving = ShipKinematics(velocity = Vec2(50f, 0f))

        val next = model.update(moving, MovementInput.NONE, params, dt = 0.1f)

        assertEquals(46f, next.speed, 1e-3f)
    }

    @Test
    fun `decay clamps exactly to zero and never reverses past it`() {
        // Speed 2 with a one-frame decay budget of 4 must land on 0, not -2.
        val crawling = ShipKinematics(velocity = Vec2(2f, 0f))

        val next = model.update(crawling, MovementInput.NONE, params, dt = 0.1f)

        assertEquals("speed snaps to a full stop", 0f, next.speed, 0f)
        assertTrue("velocity must not flip backward", next.velocity.x >= 0f)
    }

    @Test
    fun `stick deflection under the deadzone is treated as released and drifts`() {
        val moving = ShipKinematics(velocity = Vec2(50f, 0f), headingRadians = 0f)
        // magnitude 0.1 < deadzone 0.15 → no input → decay applies.
        val belowDeadzone = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 0.1f, released = false)

        val next = model.update(moving, belowDeadzone, params, dt = 0.1f)

        assertEquals("sub-deadzone input decays like a release", 46f, next.speed, 1e-3f)
    }

    // --- AC#5: reverse thrust capped at maxReverseSpeed ---

    @Test
    fun `stick opposite the hull facing applies reverse thrust`() {
        // Facing +x, stick points -x (180deg, beyond the reverse cone).
        val next =
            model.update(
                ShipKinematics(headingRadians = 0f),
                input(Vec2(-1f, 0f)),
                params,
                dt = 0.1f,
            )

        assertTrue("ship accelerates backward along -x", next.velocity.x < 0f)
    }

    @Test
    fun `reverse speed is capped at maxReverseSpeed`() {
        // Already moving backward faster than the reverse cap; one reverse-thrust frame
        // must clamp the backward-along-facing component to maxReverseSpeed (60).
        val fastBackward = ShipKinematics(velocity = Vec2(-100f, 0f), headingRadians = 0f)

        val next = model.update(fastBackward, input(Vec2(-1f, 0f)), params, dt = 0.1f)

        assertEquals("backward speed clamped to the reverse cap", params.maxReverseSpeed, next.speed, 0.5f)
        assertTrue(next.velocity.x < 0f)
    }

    @Test
    fun `between the forward and reverse cones no thrust is applied`() {
        // 120deg off axis: outside the 90deg forward cone, inside the 135deg reverse threshold.
        val moving = ShipKinematics(velocity = Vec2(10f, 0f), headingRadians = 0f)
        val sideways = input(Vec2(cosDeg(120f), sinDeg(120f)))

        val next = model.update(moving, sideways, params, dt = 0.1f)

        // No thrust and (input present so) no decay → linear velocity is unchanged.
        assertEquals("no forward thrust in the dead cone", 10f, next.velocity.x, 1e-3f)
        assertEquals("no lateral thrust in the dead cone", 0f, next.velocity.y, 1e-3f)
    }

    // --- AC#6: speed / turn-rate clamps ---

    @Test
    fun `total speed never exceeds maxSpeed under sustained forward thrust`() {
        var state = ShipKinematics(headingRadians = 0f)
        val fwd = input(Vec2(1f, 0f))
        repeat(400) {
            state = model.update(state, fwd, params, dt = 0.05f)
            assertTrue(
                "speed must stay within maxSpeed every frame",
                state.speed <= params.maxSpeed + 1e-3f,
            )
        }
        assertEquals("sustained thrust converges to the cap", params.maxSpeed, state.speed, 1f)
    }

    @Test
    fun `angular velocity never exceeds maxRotationSpeed while turning`() {
        var state = ShipKinematics(headingRadians = 0f)
        val turn = input(Vec2(0f, 1f)) // demand a turn toward +y
        var reachedNearCap = false
        repeat(200) {
            state = model.update(state, turn, params, dt = 0.05f)
            assertTrue(
                "angular velocity must stay within the turn-rate cap",
                abs(state.angularVelocity) <= params.maxRotationSpeed + 1e-4f,
            )
            if (abs(state.angularVelocity) > params.maxRotationSpeed * 0.85f) reachedNearCap = true
        }
        assertTrue("turn-rate should ramp up close to the cap", reachedNearCap)
    }

    @Test
    fun `sustained steering rotates the hull toward the demanded direction`() {
        var state = ShipKinematics(headingRadians = 0f)
        val turn = input(Vec2(0f, 1f)) // +y == PI/2
        repeat(200) { state = model.update(state, turn, params, dt = 0.05f) }

        assertEquals("hull settles facing the target direction", (PI / 2).toFloat(), state.headingRadians, 0.05f)
    }

    // --- AC#12 / guard: model is a pure, self-validating function ---

    @Test
    fun `update rejects a non-positive timestep`() {
        assertThrows(IllegalArgumentException::class.java) {
            model.update(ShipKinematics(), MovementInput.NONE, params, dt = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            model.update(ShipKinematics(), MovementInput.NONE, params, dt = -0.1f)
        }
    }

    private companion object {
        fun cosDeg(deg: Float) = kotlin.math.cos(deg * (PI / 180.0)).toFloat()

        fun sinDeg(deg: Float) = kotlin.math.sin(deg * (PI / 180.0)).toFloat()
    }
}
