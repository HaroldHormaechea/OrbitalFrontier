package com.orbitalfrontier.walkaround

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.MovementInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for [WalkaroundModel] — the pure on-foot movement integrator (UC19).
 *
 * Covers: facing follows the stick direction (AC#4); a released / zero-deflection stick freezes the
 * avatar and retains its facing (AC#4 + the released-stick pitfall); a full landing → corridor → room
 * traverse (and back) stays inside the walkable union without sticking (AC#5); the avatar cannot cross
 * the outer walls (AC#8); the interact prompt toggles exactly at the shopkeeper radius (AC#6); and the
 * integrator is deterministic (same inputs → same Avatar).
 */
class WalkaroundModelTest {
    private val model = WalkaroundModel()
    private val interior = StationInterior.prototype()
    private val params = WalkaroundParams()

    private fun pushing(
        direction: Vec2,
        magnitude: Float = 1f,
    ) = MovementInput(targetDirection = direction, magnitude = magnitude, released = false)

    // --- AC#4: facing follows movement; released / zero input freezes ----------------------------

    @Test
    fun `facing follows the (normalized) stick direction`() {
        val avatar = Avatar.spawnedAt(Vec2(300f, 120f))
        val moved = model.update(avatar, interior, pushing(Vec2(0f, 5f)), params, DT)
        // Facing is the normalized push direction.
        assertEquals(0f, moved.facing.x, EPS)
        assertEquals(1f, moved.facing.y, EPS)
        assertEquals("facing must be a unit vector", 1f, length(moved.facing), EPS)
    }

    @Test
    fun `moving advances the position by speed times magnitude times dt`() {
        val start = Vec2(300f, 120f)
        val avatar = Avatar.spawnedAt(start)
        val moved = model.update(avatar, interior, pushing(Vec2(1f, 0f), magnitude = 0.5f), params, DT)
        val expected = params.moveSpeed * 0.5f * DT
        assertEquals(start.x + expected, moved.position.x, EPS)
        assertEquals(start.y, moved.position.y, EPS)
    }

    @Test
    fun `a released stick freezes the position and retains the previous facing (AC4 pitfall)`() {
        val avatar = Avatar(position = Vec2(300f, 120f), facing = Vec2(0f, 1f))
        val after = model.update(avatar, interior, MovementInput.NONE, params, DT)
        assertEquals("position must not move when the stick is released", avatar, after)
        assertEquals("facing must be retained", Vec2(0f, 1f), after.facing)
    }

    @Test
    fun `a zero-direction active stick also freezes and keeps facing`() {
        val avatar = Avatar(position = Vec2(300f, 120f), facing = Vec2(-1f, 0f))
        val after = model.update(avatar, interior, pushing(Vec2.ZERO, magnitude = 0.3f), params, DT)
        assertEquals(avatar, after)
    }

    @Test
    fun `dt is clamped so a long stall cannot teleport the avatar`() {
        // The screen clamps dt to MAX_DT (0.05); the model also guards against a runaway dt being able
        // to tunnel straight across the interior — a huge dt step still lands inside the walkable union.
        val avatar = Avatar.spawnedAt(Vec2(300f, 120f))
        val after = model.update(avatar, interior, pushing(Vec2(1f, 0f)), params, dt = 100f)
        assertTrue("even a huge dt step must stay walkable", interior.isWalkable(after.position))
    }

    // --- AC#5: full traverse landing -> corridor -> room and back, no sticking -------------------

    @Test
    fun `the avatar can walk landing - corridor - room and back without ever leaving the union`() {
        var avatar = Avatar.spawnedAt(interior.avatarSpawn)

        // Outbound: push +x until we reach the shopkeeper's room.
        var steps = 0
        while (avatar.position.x < interior.shopkeeperPosition.x && steps < MAX_STEPS) {
            avatar = model.update(avatar, interior, pushing(Vec2(1f, 0f)), params, DT)
            assertTrue("left the walkable union mid-traverse at ${avatar.position}", interior.isWalkable(avatar.position))
            steps++
        }
        assertTrue("never reached the room (stuck at a seam?)", avatar.position.x >= interior.room.minX)
        assertTrue("should arrive in the shop room", interior.room.contains(avatar.position))

        // Return: push -x back to the landing area.
        steps = 0
        while (avatar.position.x > interior.avatarSpawn.x && steps < MAX_STEPS) {
            avatar = model.update(avatar, interior, pushing(Vec2(-1f, 0f)), params, DT)
            assertTrue("left the walkable union on the way back at ${avatar.position}", interior.isWalkable(avatar.position))
            steps++
        }
        assertTrue("should arrive back in the landing area", interior.landingArea.contains(avatar.position))
    }

    // --- AC#8: outer walls are impassable --------------------------------------------------------

    @Test
    fun `pushing into an outer wall keeps the avatar inside the walkable area`() {
        // Sit against the landing area's bottom-left corner and shove down-and-left, hard.
        var avatar = Avatar.spawnedAt(Vec2(interior.landingArea.minX + 5f, interior.landingArea.minY + 5f))
        repeat(20) {
            avatar = model.update(avatar, interior, pushing(Vec2(-1f, -1f)), params, DT)
            assertTrue("avatar walked through the outer wall to ${avatar.position}", interior.isWalkable(avatar.position))
        }
        // It cannot pass the outer boundary on either axis.
        assertTrue(avatar.position.x >= interior.landingArea.minX - EPS)
        assertTrue(avatar.position.y >= interior.landingArea.minY - EPS)
    }

    @Test
    fun `pushing up out of the corridor (between the rooms) is blocked`() {
        // Stand in the corridor, away from either overlap, and push straight up into the corridor wall.
        var avatar = Avatar.spawnedAt(Vec2(350f, interior.corridor.maxY - 2f))
        repeat(20) {
            avatar = model.update(avatar, interior, pushing(Vec2(0f, 1f)), params, DT)
            assertTrue("avatar escaped the corridor at ${avatar.position}", interior.isWalkable(avatar.position))
        }
    }

    // --- AC#6: shopkeeper interact radius --------------------------------------------------------

    @Test
    fun `isNearShopkeeper toggles exactly at the interact radius boundary`() {
        val keeper = interior.shopkeeperPosition
        val r = params.shopkeeperInteractRadius

        val justInside = Avatar.spawnedAt(Vec2(keeper.x + r - 0.5f, keeper.y))
        assertTrue("inside the radius -> near", model.isNearShopkeeper(justInside, interior, params))

        val onBoundary = Avatar.spawnedAt(Vec2(keeper.x + r, keeper.y))
        assertTrue("exactly on the radius -> near (inclusive)", model.isNearShopkeeper(onBoundary, interior, params))

        val justOutside = Avatar.spawnedAt(Vec2(keeper.x + r + 0.5f, keeper.y))
        assertFalse("beyond the radius -> not near", model.isNearShopkeeper(justOutside, interior, params))
    }

    @Test
    fun `the avatar is not near the shopkeeper at the landing spawn`() {
        val avatar = Avatar.spawnedAt(interior.avatarSpawn)
        assertFalse(model.isNearShopkeeper(avatar, interior, params))
    }

    // --- determinism -----------------------------------------------------------------------------

    @Test
    fun `the same inputs produce the same Avatar (deterministic)`() {
        val start = Avatar.spawnedAt(Vec2(300f, 120f))
        val input = pushing(Vec2(0.6f, 0.8f), magnitude = 0.75f)
        val a = model.update(start, interior, input, params, DT)
        val b = model.update(start, interior, input, params, DT)
        assertEquals(a, b)
    }

    private fun length(v: Vec2): Float = sqrt(v.x * v.x + v.y * v.y)

    private companion object {
        const val DT = 0.05f
        const val EPS = 1e-4f
        const val MAX_STEPS = 1_000
    }
}
