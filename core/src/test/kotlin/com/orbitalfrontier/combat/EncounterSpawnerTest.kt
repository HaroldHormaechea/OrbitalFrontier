package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EncounterSpawner] (UC13) — the **edge-triggered** natural-encounter spawn.
 *
 * The key design point: a fight spawns once on the **outside→inside** crossing of a zone, not while the
 * player merely sits inside. This is what makes the AC#6 flee loop work — outrunning the hostiles and
 * leaving the zone, then re-entering, can ambush again, but lingering inside (or staying outside) does
 * not. A spawn is suppressed while a fight is already active, and the encounter is seeded deterministically.
 */
class EncounterSpawnerTest {
    private val zone =
        EncounterZone(
            id = "alpha-raider-picket",
            sectorId = "alpha",
            center = Vec2(900f, 0f),
            radius = 260f,
            archetypeId = HostileArchetypes.RAIDER.id,
            hostileCount = 1,
        )
    private val params = CombatParams() // spawnDistance 360

    // Points relative to the zone centre (900,0), radius 260.
    private val outside = Vec2(600f, 0f) // dist 300 > 260
    private val inside = Vec2(900f, 0f) // dist 0
    private val alsoInside = Vec2(950f, 0f) // dist 50

    @Test
    fun `crossing from outside to inside spawns the zone's hostiles`() {
        val spawned = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, spawnTick = 0, params = params)

        assertTrue("the encounter is now active", spawned.active)
        assertEquals("tagged with the zone id", zone.id, spawned.zoneId)
        assertEquals("one RAIDER spawned", 1, spawned.hostiles.size)
        assertEquals("the hostile is the zone's archetype", HostileArchetypes.RAIDER.id, spawned.hostiles.single().archetypeId)
    }

    @Test
    fun `staying inside (inside to inside) does not spawn`() {
        assertSame(
            "no fresh ambush while already inside",
            CombatState.NONE,
            EncounterSpawner.naturalSpawn(CombatState.NONE, zone, inside, alsoInside, 0, params),
        )
    }

    @Test
    fun `staying outside (outside to outside) does not spawn`() {
        assertSame(
            "no spawn without a crossing",
            CombatState.NONE,
            EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, Vec2(500f, 0f), 0, params),
        )
    }

    @Test
    fun `a crossing is suppressed while a fight is already active`() {
        val active = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, 0, params)
        assertTrue("precondition: active", active.active)
        // Another outside->inside crossing while active must not stack a second encounter.
        assertSame("an active fight is never re-spawned", active, EncounterSpawner.naturalSpawn(active, zone, outside, inside, 1, params))
    }

    @Test
    fun `the spawned encounter is deterministic for a given zone and spawn tick`() {
        val a = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, spawnTick = 5, params = params)
        val b = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, spawnTick = 5, params = params)
        assertEquals("same zone + spawnTick reproduce the identical encounter", a, b)
    }

    @Test
    fun `hostiles spawn at the configured spawn distance from the player`() {
        val spawned = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, 0, params)
        val hostile = spawned.hostiles.single()
        val distance = (hostile.kinematics.position - inside).length
        assertEquals("placed at spawnDistance from the player", params.spawnDistance, distance, 1e-2f)
    }

    @Test
    fun `missionSpawn injects the same shape of encounter without a positional crossing`() {
        val spawned =
            EncounterSpawner.missionSpawn(
                CombatState.NONE,
                zoneId = "mission:test",
                archetypeId = HostileArchetypes.RAIDER.id,
                hostileCount = 2,
                playerPosition = Vec2(0f, 0f),
                spawnTick = 3,
                params = params,
            )
        assertTrue(spawned.active)
        assertEquals("the mission spawn honours the requested count", 2, spawned.hostiles.size)
        assertSame(
            "a mission spawn is also suppressed while active",
            spawned,
            EncounterSpawner.missionSpawn(spawned, "mission:test", HostileArchetypes.RAIDER.id, 2, Vec2(0f, 0f), 4, params),
        )
    }

    // --- UC45 AC#1: multi-archetype composition spawning + single-entry RNG parity -----------------------

    @Test
    fun `a multi-entry composition spawns each entry's hostiles in authored order`() {
        val zone =
            EncounterZone(
                id = "gamma-marauder-pack",
                sectorId = "gamma",
                center = Vec2(900f, 0f),
                radius = 260f,
                composition =
                    listOf(
                        HostileSpawn(HostileArchetypes.RAIDER.id, 2),
                        HostileSpawn(HostileArchetypes.SCAVENGER.id, 1),
                    ),
            )
        val spawned = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, spawnTick = 0, params = params)

        assertEquals("every composition hostile spawned", 3, spawned.hostiles.size)
        assertEquals(
            "spawned in authored order: 2 raiders then 1 scavenger",
            listOf(HostileArchetypes.RAIDER.id, HostileArchetypes.RAIDER.id, HostileArchetypes.SCAVENGER.id),
            spawned.hostiles.map { it.archetypeId },
        )
    }

    @Test
    fun `a single-entry count is byte-identical to the same count split across entries`() {
        // The RNG draws one nextFloat per hostile in entry order, so [(A,2)] consumes the RNG stream in the
        // EXACT same order as [(A,1),(A,1)] — the parity proof that wrapping a single-archetype zone in a
        // composition list does not perturb its spawn (existing zones stay byte-identical).
        val single =
            EncounterZone("z", "alpha", Vec2(900f, 0f), 260f, listOf(HostileSpawn(HostileArchetypes.RAIDER.id, 2)))
        val split =
            EncounterZone(
                "z",
                "alpha",
                Vec2(900f, 0f),
                260f,
                listOf(HostileSpawn(HostileArchetypes.RAIDER.id, 1), HostileSpawn(HostileArchetypes.RAIDER.id, 1)),
            )
        val a = EncounterSpawner.naturalSpawn(CombatState.NONE, single, outside, inside, spawnTick = 7, params = params)
        val b = EncounterSpawner.naturalSpawn(CombatState.NONE, split, outside, inside, spawnTick = 7, params = params)
        assertEquals("the same count draws the RNG identically however the composition is grouped", a, b)
    }

    @Test
    fun `the spawn director scales a ByProgression zone by the player's progression`() {
        val zone =
            EncounterZone(
                id = "scaled",
                sectorId = "alpha",
                center = Vec2(900f, 0f),
                radius = 260f,
                composition = listOf(HostileSpawn(HostileArchetypes.RAIDER.id, 1)),
                scaling =
                    SpawnScaling.ByProgression(
                        reinforcementArchetypeId = HostileArchetypes.SCAVENGER.id,
                        hostilesPerLevel = 1,
                        maxBonusHostiles = 2,
                    ),
            )
        // progression 0 ⇒ no reinforcement (base composition, AC#3 default-off contract).
        val unscaled = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, 0, params, progression = 0)
        assertEquals("an unprogressed player meets the base pack", 1, unscaled.hostiles.size)

        // progression 2 ⇒ +2 reinforcement scavengers (capped), so a 3-hostile pack.
        val scaled = EncounterSpawner.naturalSpawn(CombatState.NONE, zone, outside, inside, 0, params, progression = 2)
        assertEquals("a progressed player meets a larger pack", 3, scaled.hostiles.size)
        assertEquals(
            "the reinforcements are the configured archetype, appended after the base",
            listOf(HostileArchetypes.RAIDER.id, HostileArchetypes.SCAVENGER.id, HostileArchetypes.SCAVENGER.id),
            scaled.hostiles.map { it.archetypeId },
        )
    }
}
