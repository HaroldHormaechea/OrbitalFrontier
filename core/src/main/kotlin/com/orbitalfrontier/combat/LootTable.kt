package com.orbitalfrontier.combat

import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.economy.ResourceType

/**
 * The result of rolling a destroyed hostile's loot (UC42 AC#1) — the [credits] (scrap value) and the
 * [resources] (positive units per [ResourceType]) the wreck yields. Pure value data; an empty
 * [resources] map means the wreck dropped credits only.
 */
data class LootResult(
    val credits: Long,
    val resources: Map<ResourceType, Int>,
)

/**
 * One possible resource yield in an archetype's loot catalog (UC42) — [resource] drops with probability
 * [chance] (0..1) in a uniform `[minUnits, maxUnits]` quantity when it hits. All numbers are `[TUNE]`.
 */
data class LootDrop(
    val resource: ResourceType,
    val chance: Float,
    val minUnits: Int,
    val maxUnits: Int,
) {
    init {
        require(chance in 0f..1f) { "LootDrop chance must be in 0..1: $chance" }
        require(minUnits >= 0) { "LootDrop minUnits must not be negative: $minUnits" }
        require(maxUnits >= minUnits) { "LootDrop maxUnits must be >= minUnits: $maxUnits < $minUnits" }
    }
}

/**
 * The authored loot profile of one [HostileArchetype] (UC42) — a uniform credit span
 * `[creditsMin, creditsMax]` plus an ordered list of possible resource [drops]. The order is a
 * **deterministic contract**: [LootTable.roll] walks [drops] in this order drawing from the threaded
 * RNG, so reordering changes the stream. All numbers are `[TUNE]`.
 */
data class ArchetypeLoot(
    val creditsMin: Long,
    val creditsMax: Long,
    val drops: List<LootDrop>,
) {
    init {
        require(creditsMin >= 0L) { "ArchetypeLoot creditsMin must not be negative: $creditsMin" }
        require(creditsMax >= creditsMin) { "ArchetypeLoot creditsMax must be >= creditsMin: $creditsMax < $creditsMin" }
    }
}

/**
 * The data-driven loot catalog (UC42 AC#1) — what each [HostileArchetype] drops when destroyed, plus
 * the **pure, seed-deterministic** [roll] that turns an archetype + seed key into a [LootResult].
 *
 * Mirrors the precedent of archetype-stat authoring ([HostileArchetypes]): the catalog is keyed by
 * [HostileArchetypeId] and an unknown id degrades gracefully to a small [DEFAULT] profile (never a
 * crash). The whole thing is engine-free and floating-point-free in its state transition (it threads
 * the project's single [DeterministicRng] primitive — string-hash → LCG), so loot is identical on any
 * JVM and replay-stable (UC42 AC#4): the same `seedKey` always yields the same credits and resources.
 *
 * **Seed convention.** The caller seeds with `"salvage:$zoneId:${hostileId.value}"` (see
 * [Salvage.spawn]) — independent of the combat RNG, so loot generation draws no combat-RNG numbers and
 * the committed combat fixtures stay byte-identical (no fixture regen).
 *
 * All authored numbers are `[TUNE]` placeholders held to the AC contract; balancing is a later pass.
 */
object LootTable {
    /** Raider: a worthwhile haul — a healthy credit span and a good chance of common ores. [TUNE] */
    val RAIDER: ArchetypeLoot =
        ArchetypeLoot(
            creditsMin = 20L,
            creditsMax = 60L,
            drops =
                listOf(
                    LootDrop(ResourceType.IRON_ORE, chance = 0.8f, minUnits = 1, maxUnits = 4),
                    LootDrop(ResourceType.COPPER, chance = 0.4f, minUnits = 1, maxUnits = 3),
                    LootDrop(ResourceType.TITANIUM, chance = 0.15f, minUnits = 1, maxUnits = 1),
                ),
        )

    /** Scavenger: lighter wreckage — a smaller credit span and a modest scrap yield. [TUNE] */
    val SCAVENGER: ArchetypeLoot =
        ArchetypeLoot(
            creditsMin = 10L,
            creditsMax = 30L,
            drops =
                listOf(
                    LootDrop(ResourceType.IRON_ORE, chance = 0.6f, minUnits = 1, maxUnits = 2),
                    LootDrop(ResourceType.SILICON, chance = 0.3f, minUnits = 1, maxUnits = 2),
                ),
        )

    /** Fallback profile for an un-catalogued archetype id — modest credits, no resources. [TUNE] */
    val DEFAULT: ArchetypeLoot =
        ArchetypeLoot(creditsMin = 5L, creditsMax = 15L, drops = emptyList())

    /**
     * A **derelict / wreck** loot profile (UC54) — a first-class [ArchetypeLoot] of salvageable resources
     * (no fake hostile archetype). Derelicts yield **no credits** (`creditsMin == creditsMax == 0`, AC#2:
     * "resources/parts"), only a spread of common materials, rolled via [roll]`(DERELICT, "derelict:$id")`
     * — a fresh RNG namespace so it adds zero draws to any combat/salvage stream (the zero-fixture-regen
     * lever). All numbers are `[TUNE]`.
     */
    val DERELICT: ArchetypeLoot =
        ArchetypeLoot(
            creditsMin = 0L,
            creditsMax = 0L,
            drops =
                listOf(
                    LootDrop(ResourceType.IRON_ORE, chance = 0.9f, minUnits = 2, maxUnits = 6),
                    LootDrop(ResourceType.ALUMINUM, chance = 0.6f, minUnits = 1, maxUnits = 4),
                    LootDrop(ResourceType.TITANIUM, chance = 0.3f, minUnits = 1, maxUnits = 2),
                ),
        )

    private val byId: Map<HostileArchetypeId, ArchetypeLoot> =
        mapOf(
            HostileArchetypes.RAIDER.id to RAIDER,
            HostileArchetypes.SCAVENGER.id to SCAVENGER,
        )

    /** The authored loot profile for [archetypeId], or [DEFAULT] if the id is not catalogued. */
    fun lootFor(archetypeId: HostileArchetypeId): ArchetypeLoot = byId[archetypeId] ?: DEFAULT

    /**
     * Roll [archetypeId]'s loot deterministically from [seedKey] (UC42 AC#1/#4). Pure: identical
     * `(archetypeId, seedKey)` always yields the same [LootResult]. Looks the profile up via [lootFor] and
     * delegates to the [roll]`(loot, seedKey)` core below.
     */
    fun roll(
        archetypeId: HostileArchetypeId,
        seedKey: String,
    ): LootResult = roll(lootFor(archetypeId), seedKey)

    /**
     * Roll an explicit [loot] profile deterministically from [seedKey] (UC42 AC#1/#4; the shared core
     * extracted in UC54 so a first-class profile like [DERELICT] rolls without a fake archetype). Pure:
     * identical `(loot, seedKey)` always yields the same [LootResult]. The stream is FNV-1a(seedKey) → LCG:
     * one draw for credits, then two draws per authored [LootDrop] (chance, then quantity) in catalog
     * order. Only positive resource yields are kept, so the result's `resources` never carries a
     * zero-unit entry.
     */
    fun roll(
        loot: ArchetypeLoot,
        seedKey: String,
    ): LootResult {
        var state = DeterministicRng.fnv1a(seedKey)

        state = DeterministicRng.lcgAdvance(state)
        val creditSpan = (loot.creditsMax - loot.creditsMin + 1L).toInt()
        val credits = loot.creditsMin + DeterministicRng.boundedInt(state, creditSpan)

        val resources = LinkedHashMap<ResourceType, Int>()
        for (drop in loot.drops) {
            state = DeterministicRng.lcgAdvance(state)
            val hit = DeterministicRng.floatFromState(state) < drop.chance
            state = DeterministicRng.lcgAdvance(state)
            if (!hit) continue
            val span = drop.maxUnits - drop.minUnits + 1
            val units = drop.minUnits + DeterministicRng.boundedInt(state, span)
            if (units > 0) resources[drop.resource] = (resources[drop.resource] ?: 0) + units
        }
        return LootResult(credits, resources)
    }
}
