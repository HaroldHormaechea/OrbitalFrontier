package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2

/**
 * A hand-authored **natural encounter region** (UC13 AC#... encounters are natural + mission-spawned).
 * Pure value data living in the `combat` package (no `world` dependency, so `combat` stays
 * world-agnostic and engine-free): the world layer ([com.orbitalfrontier.world.MvpSectorMap]) authors
 * the zones and tags each with the **String** [sectorId] of the sector it sits in, and the screen
 * filters zones by the current sector before handing the geometry to [EncounterSpawner].
 *
 * The encounter is **edge-triggered**: it fires once when the player crosses from **outside** the
 * [radius] of [center] to **inside** it (see [EncounterSpawner]), not while merely sitting inside — so
 * outrunning the hostiles past the leash and leaving the zone, then re-entering, can ambush again, but
 * lingering inside after a cleared fight does not instantly re-spawn.
 *
 * [hostileCount] hostiles of [archetypeId] are spawned, seeded by `"encounter:$id:$spawnTick"` so the
 * same crossing replays identically.
 */
data class EncounterZone(
    val id: String,
    val sectorId: String,
    val center: Vec2,
    val radius: Float,
    val archetypeId: HostileArchetypeId,
    val hostileCount: Int,
) {
    init {
        require(id.isNotBlank()) { "EncounterZone id must not be blank" }
        require(sectorId.isNotBlank()) { "EncounterZone sectorId must not be blank" }
        require(radius > 0f) { "EncounterZone $id radius must be positive: $radius" }
        require(hostileCount > 0) { "EncounterZone $id hostileCount must be positive: $hostileCount" }
    }

    /** True when [position] lies within this zone's trigger radius. */
    fun contains(position: Vec2): Boolean = (position - center).length <= radius
}
