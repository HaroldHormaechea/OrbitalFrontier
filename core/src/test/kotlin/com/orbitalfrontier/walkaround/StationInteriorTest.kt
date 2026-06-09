package com.orbitalfrontier.walkaround

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for [StationInterior] — the pure walk-around layout + collision geometry (UC19).
 *
 * Pins the layout contract behind AC#2 (avatar spawns near the ship in the landing area), AC#5 (the
 * landing → corridor → room areas join into a single continuous walkable union with no internal
 * seam), and AC#8 ([clampToWalkable] keeps any stray point inside the walkable union and never traps
 * it at a junction).
 */
class StationInteriorTest {
    private val interior = StationInterior.prototype()

    // --- AC#2: spawn + ship placement -------------------------------------------------------------

    @Test
    fun `the ship sits inside the landing area`() {
        assertTrue(interior.landingArea.contains(interior.shipPosition))
    }

    @Test
    fun `the avatar spawns inside the landing area, near the ship`() {
        assertTrue("spawn must be walkable", interior.isWalkable(interior.avatarSpawn))
        assertTrue("spawn must be in the landing area", interior.landingArea.contains(interior.avatarSpawn))
        val distToShip = distance(interior.avatarSpawn, interior.shipPosition)
        // "Near" the ship: well within the landing pad, not on the far side of the interior.
        assertTrue("spawn should be near the ship (was $distToShip)", distToShip <= 150f)
    }

    @Test
    fun `the shopkeeper stands inside the room`() {
        assertTrue(interior.room.contains(interior.shopkeeperPosition))
    }

    // --- AC#5: the three areas form ONE continuous walkable union -------------------------------

    @Test
    fun `walkableAreas are landing, corridor and room in draw order`() {
        assertEquals(
            listOf(interior.landingArea, interior.corridor, interior.room),
            interior.walkableAreas,
        )
    }

    @Test
    fun `isWalkable is union membership across all three areas`() {
        assertTrue("a landing point", interior.isWalkable(Vec2(80f, 200f)))
        assertTrue("a corridor point", interior.isWalkable(Vec2(350f, 120f)))
        assertTrue("a room point", interior.isWalkable(Vec2(620f, 250f)))
        assertFalse("a point outside every area", interior.isWalkable(Vec2(350f, 280f)))
    }

    @Test
    fun `the landing-corridor seam is continuously walkable (no gap)`() {
        // Walk across the junction at the corridor's mid-height; every sample must stay walkable.
        var x = 180f
        while (x <= 260f) {
            assertTrue("x=$x must be walkable at the landing-corridor seam", interior.isWalkable(Vec2(x, 120f)))
            x += 2f
        }
    }

    @Test
    fun `the corridor-room seam is continuously walkable (no gap)`() {
        var x = 440f
        while (x <= 520f) {
            assertTrue("x=$x must be walkable at the corridor-room seam", interior.isWalkable(Vec2(x, 120f)))
            x += 2f
        }
    }

    @Test
    fun `adjacent areas overlap (share area, not merely touch)`() {
        // A genuine overlap means there is a point inside BOTH neighbours simultaneously.
        val landingCorridor = Vec2(220f, 120f)
        assertTrue(interior.landingArea.contains(landingCorridor) && interior.corridor.contains(landingCorridor))
        val corridorRoom = Vec2(480f, 120f)
        assertTrue(interior.corridor.contains(corridorRoom) && interior.room.contains(corridorRoom))
    }

    // --- AC#8: clampToWalkable -------------------------------------------------------------------

    @Test
    fun `clampToWalkable leaves an already-walkable point unchanged`() {
        val inside = Vec2(350f, 120f)
        assertEquals(inside, interior.clampToWalkable(inside))
        // Including a point that lies in the overlap of two areas (a seam) — it must NOT be moved.
        val seam = Vec2(220f, 120f)
        assertEquals(seam, interior.clampToWalkable(seam))
    }

    @Test
    fun `clampToWalkable pulls an outside point back into the union`() {
        for (stray in listOf(Vec2(-50f, -50f), Vec2(350f, 400f), Vec2(900f, 150f), Vec2(620f, -30f))) {
            val clamped = interior.clampToWalkable(stray)
            assertTrue("clampToWalkable($stray) -> $clamped must be walkable", interior.isWalkable(clamped))
        }
    }

    @Test
    fun `clampToWalkable picks the nearest area, not just the first`() {
        // A point that has left through the room's right wall must come back onto the ROOM, not the
        // landing area (which is the first area in the list) — proving it minimises distance.
        val outOfRoom = Vec2(900f, 150f)
        val clamped = interior.clampToWalkable(outOfRoom)
        assertTrue("must snap back onto the room", interior.room.contains(clamped))
        assertEquals("nearest point on the room's right wall", interior.room.maxX, clamped.x, EPS)
    }

    private fun distance(
        a: Vec2,
        b: Vec2,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
