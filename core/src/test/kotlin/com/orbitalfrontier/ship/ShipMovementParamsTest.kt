package com.orbitalfrontier.ship

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI

/**
 * Guard-rail tests for [ShipMovementParams] `init` validation (invalid tuning rejected).
 * Covers the parameter-clamping contract behind AC#6.
 */
class ShipMovementParamsTest {
    @Test
    fun `default parameters construct successfully`() {
        assertNotNull(ShipMovementParams())
    }

    @Test
    fun `non-positive maxSpeed is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(maxSpeed = 0f) }
    }

    @Test
    fun `non-positive maxAcceleration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(maxAcceleration = 0f) }
    }

    @Test
    fun `negative maxReverseSpeed is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(maxReverseSpeed = -1f) }
    }

    @Test
    fun `non-positive rotationAcceleration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(rotationAcceleration = 0f) }
    }

    @Test
    fun `non-positive maxRotationSpeed is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(maxRotationSpeed = 0f) }
    }

    @Test
    fun `negative driftDecay is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(driftDecay = -1f) }
    }

    @Test
    fun `forward cone wider than the reverse cone is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShipMovementParams(
                forwardConeRadians = (PI * 3.0 / 4.0).toFloat(),
                reverseConeRadians = (PI / 2.0).toFloat(),
            )
        }
    }

    @Test
    fun `input deadzone outside zero-to-one is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShipMovementParams(inputDeadzone = 1.5f) }
    }
}
