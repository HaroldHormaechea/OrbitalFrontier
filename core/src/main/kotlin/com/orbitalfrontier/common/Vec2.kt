package com.orbitalfrontier.common

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable 2D vector of plain [Float]s.
 *
 * Deliberately free of any libGDX type so that game-logic that uses it (movement
 * kinematics, controls mapping) stays JVM-unit-testable per ADR 0001. The `render`
 * and `screen` layers convert to/from libGDX `Vector2` at their own boundary.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)

    operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    /** Euclidean length (magnitude). */
    val length: Float get() = sqrt(x * x + y * y)

    /** Dot product with [other]. */
    fun dot(other: Vec2): Float = x * other.x + y * other.y

    /** Unit vector in the same direction, or [ZERO] for a zero-length vector. */
    fun normalizedOrZero(): Vec2 {
        val len = length
        return if (len > 0f) this * (1f / len) else ZERO
    }

    /** This vector scaled down so its length does not exceed [max]; unchanged if already within. */
    fun limit(max: Float): Vec2 {
        val len = length
        return if (len > max && len > 0f) this * (max / len) else this
    }

    companion object {
        val ZERO = Vec2(0f, 0f)

        /** Unit vector pointing at [radians], scaled by [magnitude]. */
        fun fromAngle(
            radians: Float,
            magnitude: Float = 1f,
        ): Vec2 = Vec2(cos(radians) * magnitude, sin(radians) * magnitude)
    }
}
