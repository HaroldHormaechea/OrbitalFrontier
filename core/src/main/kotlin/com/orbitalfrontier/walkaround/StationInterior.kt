package com.orbitalfrontier.walkaround

import com.orbitalfrontier.common.Rect
import com.orbitalfrontier.common.Vec2

/**
 * The on-foot station interior layout for the walk-around prototype (UC19): a small fixed set of
 * walkable [Rect]s plus the authored positions of the ship, the shopkeeper, and the avatar's spawn.
 *
 * Pure data + geometry (no libGDX types) so it stays JVM-unit-testable per ADR 0001; the
 * `WalkaroundRenderer` / `StationWalkaroundScreen` convert it to libGDX shapes at their boundary.
 *
 * **Walkability is UNION membership** (AC#8): a point is walkable iff it lies inside *any* one of the
 * [walkableAreas]; an inside point is returned unchanged. The areas deliberately **overlap** (share
 * area, not merely touch — see [prototype]) so the avatar can never be trapped at an internal seam
 * between two adjacent areas: when it is inside the overlap it is inside both, and when it is outside
 * everything [clampToWalkable] pulls it back to the nearest area.
 *
 * The layout is transient and **never persisted** — re-entering the interior rebuilds it from
 * [prototype] (see docs/design/station-interior.md).
 */
data class StationInterior(
    /** Landing pad the player's ship sits on; the avatar spawns here. */
    val landingArea: Rect,
    /** Corridor linking the landing area to the shop room. Overlaps both at its ends. */
    val corridor: Rect,
    /** Square room containing the shop + shopkeeper. */
    val room: Rect,
    /** Where the player's ship is drawn (inside [landingArea]). */
    val shipPosition: Vec2,
    /** Where the shopkeeper figure stands (inside [room]). */
    val shopkeeperPosition: Vec2,
    /** Where the avatar appears on entering / re-entering on foot (inside [landingArea]). */
    val avatarSpawn: Vec2,
) {
    /** The three walkable areas as a union, in draw order (landing → corridor → room). */
    val walkableAreas: List<Rect> = listOf(landingArea, corridor, room)

    /** True when [point] is inside ANY walkable area (union membership, AC#8). */
    fun isWalkable(point: Vec2): Boolean = walkableAreas.any { it.contains(point) }

    /**
     * [point] if it is already walkable (returned unchanged), otherwise the nearest point on the
     * closest walkable area. Because the areas overlap there is no internal seam to get stuck on, so
     * this only ever moves a point that has left the interior entirely back onto its boundary (AC#8).
     */
    fun clampToWalkable(point: Vec2): Vec2 {
        if (isWalkable(point)) return point
        var best = walkableAreas.first().clamp(point)
        var bestDistSq = distanceSquared(point, best)
        for (i in 1 until walkableAreas.size) {
            val candidate = walkableAreas[i].clamp(point)
            val distSq = distanceSquared(point, candidate)
            if (distSq < bestDistSq) {
                best = candidate
                bestDistSq = distSq
            }
        }
        return best
    }

    private fun distanceSquared(
        a: Vec2,
        b: Vec2,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    companion object {
        /**
         * The authored prototype layout (AC#5): landing area (left) → corridor (middle) → square shop
         * room (right). Adjacent areas **overlap** at their junctions so the union has no internal seam.
         * World units; the screen zooms in so the whole interior fits on screen.
         */
        fun prototype(): StationInterior {
            // Left: landing pad with the ship.
            val landingArea = Rect(minX = 0f, minY = 0f, maxX = 240f, maxY = 240f)
            // Middle: a horizontal corridor. Overlaps the landing area on its left end (x 200..240)
            // and the room on its right end (x 460..500), so both junctions share area, not just touch.
            val corridor = Rect(minX = 200f, minY = 90f, maxX = 500f, maxY = 150f)
            // Right: square shop room with the shopkeeper.
            val room = Rect(minX = 460f, minY = 0f, maxX = 760f, maxY = 300f)
            return StationInterior(
                landingArea = landingArea,
                corridor = corridor,
                room = room,
                shipPosition = Vec2(80f, 160f),
                shopkeeperPosition = Vec2(620f, 180f),
                // Spawn near the ship, on the corridor side, so the player faces the corridor (AC#2).
                avatarSpawn = Vec2(150f, 120f),
            )
        }
    }
}
