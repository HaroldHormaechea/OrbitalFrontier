package com.orbitalfrontier.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Rect] — the pure AABB underpinning the UC19 walk-around layout + collision.
 *
 * `contains` and `clamp` are the two primitives [com.orbitalfrontier.walkaround.StationInterior]
 * builds its walkable-union membership and nearest-point clamp on (AC#5 / AC#8), so they are pinned
 * here directly.
 */
class RectTest {
    private val rect = Rect(minX = 10f, minY = 20f, maxX = 110f, maxY = 220f)

    @Test
    fun `width and height are the spans`() {
        assertEquals(100f, rect.width, EPS)
        assertEquals(200f, rect.height, EPS)
    }

    @Test
    fun `center is the midpoint`() {
        assertEquals(60f, rect.centerX, EPS)
        assertEquals(120f, rect.centerY, EPS)
    }

    @Test
    fun `contains is true for an interior point`() {
        assertTrue(rect.contains(Vec2(50f, 100f)))
    }

    @Test
    fun `contains is inclusive on every edge and corner`() {
        assertTrue("min corner", rect.contains(Vec2(10f, 20f)))
        assertTrue("max corner", rect.contains(Vec2(110f, 220f)))
        assertTrue("left edge", rect.contains(Vec2(10f, 100f)))
        assertTrue("top edge", rect.contains(Vec2(50f, 220f)))
    }

    @Test
    fun `contains is false outside on each axis`() {
        assertFalse("left of", rect.contains(Vec2(9.9f, 100f)))
        assertFalse("right of", rect.contains(Vec2(110.1f, 100f)))
        assertFalse("below", rect.contains(Vec2(50f, 19.9f)))
        assertFalse("above", rect.contains(Vec2(50f, 220.1f)))
    }

    @Test
    fun `clamp leaves an interior point unchanged`() {
        val inside = Vec2(50f, 100f)
        assertEquals(inside, rect.clamp(inside))
    }

    @Test
    fun `clamp pulls an outside point to the nearest edge per axis`() {
        // Left + below → snaps to the (minX, minY) corner.
        assertEquals(Vec2(10f, 20f), rect.clamp(Vec2(-5f, -5f)))
        // Right + above → snaps to the (maxX, maxY) corner.
        assertEquals(Vec2(110f, 220f), rect.clamp(Vec2(500f, 500f)))
        // Outside on one axis only → only that coordinate moves.
        assertEquals(Vec2(110f, 100f), rect.clamp(Vec2(200f, 100f)))
        assertEquals(Vec2(50f, 20f), rect.clamp(Vec2(50f, -10f)))
    }

    @Test
    fun `a clamped point is always contained`() {
        for (p in listOf(Vec2(-50f, -50f), Vec2(999f, 5f), Vec2(60f, 1000f))) {
            assertTrue("clamp($p) must land inside", rect.contains(rect.clamp(p)))
        }
    }

    @Test
    fun `a degenerate (zero-area) rect is allowed and clamps to its single point`() {
        val point = Rect(5f, 5f, 5f, 5f)
        assertEquals(Vec2(5f, 5f), point.clamp(Vec2(100f, 100f)))
        assertTrue(point.contains(Vec2(5f, 5f)))
    }

    @Test
    fun `an inverted rect is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { Rect(10f, 0f, 0f, 10f) }
        assertThrows(IllegalArgumentException::class.java) { Rect(0f, 10f, 10f, 0f) }
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
