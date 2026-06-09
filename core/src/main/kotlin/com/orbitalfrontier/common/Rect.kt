package com.orbitalfrontier.common

/**
 * Immutable axis-aligned bounding box (AABB) of plain [Float]s.
 *
 * Like [Vec2], deliberately free of any libGDX type so the game-logic that uses it (the on-foot
 * walk-around layout + collision, UC19) stays JVM-unit-testable per ADR 0001. The `render` / `screen`
 * layers convert to libGDX shapes at their own boundary.
 *
 * The rectangle spans `[minX, maxX]` × `[minY, maxY]` inclusive; [minX] must not exceed [maxX] and
 * [minY] must not exceed [maxY].
 */
data class Rect(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    init {
        require(minX <= maxX) { "Rect requires minX ($minX) <= maxX ($maxX)" }
        require(minY <= maxY) { "Rect requires minY ($minY) <= maxY ($maxY)" }
    }

    val width: Float get() = maxX - minX

    val height: Float get() = maxY - minY

    val centerX: Float get() = (minX + maxX) * 0.5f

    val centerY: Float get() = (minY + maxY) * 0.5f

    /** True when [point] lies inside this rectangle (edges inclusive). */
    fun contains(point: Vec2): Boolean = point.x in minX..maxX && point.y in minY..maxY

    /** [point] clamped to the nearest position within this rectangle (unchanged if already inside). */
    fun clamp(point: Vec2): Vec2 = Vec2(point.x.coerceIn(minX, maxX), point.y.coerceIn(minY, maxY))
}
