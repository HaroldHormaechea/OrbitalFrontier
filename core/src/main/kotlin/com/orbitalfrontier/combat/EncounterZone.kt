package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2

/**
 * One entry of an [EncounterZone]'s hostile **composition** (UC45 AC#1): [count] hostiles of a single
 * [archetypeId]. Pure value data; an [EncounterZone] holds an ordered list of these so a zone can mix
 * archetypes and counts. [count] must be positive (a zero-count entry is an authoring error).
 */
data class HostileSpawn(
    val archetypeId: HostileArchetypeId,
    val count: Int,
) {
    init {
        require(count > 0) { "HostileSpawn count must be positive: $count" }
    }
}

/**
 * A hand-authored **natural encounter region** (UC13/UC45 — encounters are natural + mission-spawned).
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
 * The zone spawns its [composition] (UC45 AC#1) — an ordered list of [HostileSpawn] entries — seeded by
 * `"encounter:$id:$spawnTick"` so the same crossing replays identically. Most zones hold a single entry;
 * the [secondary constructor][EncounterZone] preserves the original `(…, archetypeId, hostileCount)` shape
 * for those, and [archetypeId]/[hostileCount] are computed accessors over the first entry / the total
 * count so existing call sites keep working. [scaling] (default [SpawnScaling.None]) lets a zone opt into
 * progression-driven difficulty scaling via the [SpawnDirector] (UC45 AC#3); [SpawnScaling.None] zones are
 * spawned exactly as authored.
 */
data class EncounterZone(
    val id: String,
    val sectorId: String,
    val center: Vec2,
    val radius: Float,
    val composition: List<HostileSpawn>,
    val scaling: SpawnScaling = SpawnScaling.None,
) {
    init {
        require(id.isNotBlank()) { "EncounterZone id must not be blank" }
        require(sectorId.isNotBlank()) { "EncounterZone sectorId must not be blank" }
        require(radius > 0f) { "EncounterZone $id radius must be positive: $radius" }
        require(composition.isNotEmpty()) { "EncounterZone $id composition must not be empty" }
    }

    /**
     * Backward-compatible single-archetype constructor: a zone of [hostileCount] hostiles of one
     * [archetypeId], unscaled. Keeps every pre-UC45 zone literal (and the bounty path) compiling and
     * spawning byte-identically — a single-entry composition draws the RNG exactly as before.
     */
    constructor(
        id: String,
        sectorId: String,
        center: Vec2,
        radius: Float,
        archetypeId: HostileArchetypeId,
        hostileCount: Int,
    ) : this(id, sectorId, center, radius, listOf(HostileSpawn(archetypeId, hostileCount)), SpawnScaling.None)

    /** The first composition entry's archetype — the single-archetype view used by the bounty/loot paths. */
    val archetypeId: HostileArchetypeId get() = composition.first().archetypeId

    /** Total hostiles this zone spawns (sum of every composition entry's count). */
    val hostileCount: Int get() = composition.sumOf { it.count }

    /** True when [position] lies within this zone's trigger radius. */
    fun contains(position: Vec2): Boolean = (position - center).length <= radius
}
