package com.orbitalfrontier.combat

import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.ship.ShipKinematics

/**
 * Stable identity of a [Hostile] within a [CombatState] (UC13). A `value class` over a `Long`,
 * allocated from [CombatState.nextHostileId] so ids are **monotonic** and never reused. Crucially this
 * gives hostiles a deterministic **total order** — the tie-break in [TargetingPriority] is *ascending
 * [HostileId]*, never `hashCode`/identity ordering — so targeting is byte-stable across a replay.
 */
@JvmInline
value class HostileId(val value: Long) : Comparable<HostileId> {
    override fun compareTo(other: HostileId): Int = value.compareTo(other.value)
}

/**
 * Stable identity of a [HostileArchetype] (UC13 AC#4). A `value class` over a `String` slug, so the
 * authored roster ([HostileArchetypes]) can grow without renumbering and a persisted/replayed reference
 * stays stable. Blank ids are an authoring error (fail fast).
 */
@JvmInline
value class HostileArchetypeId(val value: String) {
    init {
        require(value.isNotBlank()) { "HostileArchetypeId must not be blank" }
    }
}

/**
 * The rule-based enemy AI behaviour of an archetype (UC13 AC#4 — **no ML**). A closed `enum`; the
 * decision logic lives in [EnemyAi], driven by archetype **data**, not hardcoded per-hostile branches.
 */
enum class AiBehavior {
    /** Always closes on the player and fires when in weapon range. */
    AGGRESSIVE,

    /** Closes and fires while healthy, but turns and runs once its hull drops below a threshold. */
    FLEE_WHEN_DAMAGED,

    /**
     * Closes and fires while healthy; once its hull drops below `fleeHullFraction` it **retreats** while
     * inside `regroupRange` (running directly away, holding fire) and then **re-engages** from that
     * recovered standoff distance rather than fleeing outright (UC45 AC#2). Memoryless and distance-driven,
     * so it stays pure and deterministic — see [EnemyAi].
     */
    RETREAT_AND_REGROUP,
}

/**
 * A coarse, data-driven difficulty label for an archetype (UC13 AC#4). The actual challenge comes from
 * the archetype's authored stats (HP, weapon, speed); this tier is the display/grouping handle and the
 * knob a future spawn-director would scale, so difficulty stays **data-driven**, not branch-driven.
 */
enum class DifficultyTier {
    EASY,
    NORMAL,
    HARD,
}

/**
 * A hand-authored enemy ship template (UC13 AC#4) — its AI behaviour, difficulty, kinematic limits,
 * per-section HP and weapon. Pure authored data (no engine types), catalogued in [HostileArchetypes]
 * and referenced by id from an [EncounterZone]; a spawned [Hostile] keeps only its mutable state and
 * resolves the template back here, so a balance retune lives in one place. All numbers are `[TUNE]`.
 */
data class HostileArchetype(
    val id: HostileArchetypeId,
    val displayName: String,
    val behavior: AiBehavior,
    val difficulty: DifficultyTier,
    /** Max speed (world-units/s) the hostile pursues / flees at. */
    val maxSpeed: Float,
    /** Acceleration (world-units/s²) toward its AI-chosen heading. */
    val acceleration: Float,
    /** Per-section max HP for this hostile (absent section ⇒ that section is effectively invulnerable). */
    val sectionHp: Map<ShipSection, Int>,
    /** The hostile's single fixed weapon (fired toward the player). */
    val weapon: FixedWeapon,
    /** Distance (world-units) within which the hostile engages / fires. */
    val engageRange: Float,
    /** Distance from the player beyond which the hostile breaks off and the encounter can clear (AC#6). */
    val leashRange: Float,
    /** Hull fraction (0..1) below which a [AiBehavior.FLEE_WHEN_DAMAGED] hostile runs. */
    val fleeHullFraction: Float,
    /**
     * For [AiBehavior.RETREAT_AND_REGROUP] (UC45 AC#2): the distance (world-units) the hostile retreats to
     * while damaged before turning back to re-engage. Authored **`< leashRange`** so the hostile regroups
     * and returns rather than running far enough to leash off. Ignored by every other behavior; defaults to
     * `0f` (no retreat) so existing archetypes are unchanged. [TUNE]
     */
    val regroupRange: Float = 0f,
    /**
     * When `true`, this archetype's fire is stamped with the player's **weakest section** at fire time and
     * applies its damage **directly** to that section (UC45 AC#2) — no RNG-weighted hit pick. **Opt-in**
     * (defaults to `false`): every existing archetype keeps the [DamageModel] RNG path, so existing combat
     * replays consume the RNG stream identically and stay byte-identical. See [WeakestSection] / ADR 0033.
     */
    val targetsWeakestSection: Boolean = false,
    /**
     * The faction this ship type belongs to (UC43), or `null` when it is **unaligned** — a freelance
     * raider/scavenger no power claims. A hostile's faction is **intrinsic to the ship type** (not to
     * where it spawned), so it lives on the archetype, not the [EncounterZone]. Destroying a
     * faction-affiliated hostile sours the player's standing with [factionId] (combat→reputation seam,
     * ADR 0031); an unaligned kill has no faction effect (the neutral-hostile rule). Combat depends on
     * `faction` here (acyclic: `faction` never imports `combat`, and `faction` is engine-free, so the
     * combat purity guards are unaffected).
     */
    val factionId: FactionId? = null,
) {
    init {
        require(displayName.isNotBlank()) { "HostileArchetype ${id.value} displayName must not be blank" }
        require(maxSpeed > 0f) { "HostileArchetype ${id.value} maxSpeed must be positive" }
        require(acceleration > 0f) { "HostileArchetype ${id.value} acceleration must be positive" }
        require((sectionHp[ShipSection.HULL] ?: 0) > 0) {
            "HostileArchetype ${id.value} must have positive HULL hp (it is the destruction gate)"
        }
        require(engageRange > 0f) { "HostileArchetype ${id.value} engageRange must be positive" }
        require(leashRange > 0f) { "HostileArchetype ${id.value} leashRange must be positive" }
        require(fleeHullFraction in 0f..1f) { "HostileArchetype ${id.value} fleeHullFraction must be in 0..1" }
        if (behavior == AiBehavior.RETREAT_AND_REGROUP) {
            require(regroupRange > 0f && regroupRange < leashRange) {
                "HostileArchetype ${id.value} RETREAT_AND_REGROUP regroupRange must be in (0, leashRange)"
            }
        }
    }

    /** Max HP of [section] for this archetype, or 0 if the archetype has no such section. */
    fun sectionHp(section: ShipSection): Int = sectionHp[section] ?: 0
}

/**
 * One spawned hostile in a live [CombatState] (UC13) — its [id], the [archetypeId] it resolves stats
 * from, its current [kinematics], its mutable per-section damage, and its weapon [cooldownRemaining].
 * Pure value data so the whole combat snapshot compares structurally for replay.
 */
data class Hostile(
    val id: HostileId,
    val archetypeId: HostileArchetypeId,
    val kinematics: ShipKinematics,
    val sectionDamage: SectionDamage = SectionDamages.PRISTINE,
    val cooldownRemaining: Float = 0f,
) {
    /** True when this hostile's HULL has been destroyed (0 HP) — the sim culls it (AC#8). */
    fun isDestroyed(archetype: HostileArchetype): Boolean =
        SectionDamages.isDestroyed(sectionDamage, ShipSection.HULL, archetype.sectionHp(ShipSection.HULL))

    /** Current hull HP as a 0..1 fraction of max — drives the FLEE_WHEN_DAMAGED decision (AC#4). */
    fun hullFraction(archetype: HostileArchetype): Float {
        val maxHp = archetype.sectionHp(ShipSection.HULL)
        if (maxHp <= 0) return 0f
        return SectionDamages.currentHp(sectionDamage, ShipSection.HULL, maxHp).toFloat() / maxHp
    }
}

/**
 * The data-driven catalog of authored enemy archetypes (UC13 AC#4), mirroring
 * [com.orbitalfrontier.ship.ShipRoster]. Reconstructed at runtime (never persisted): a spawned hostile
 * stores only its [HostileArchetypeId] and resolves the template here, so a stat retune is one edit and
 * an unknown id degrades gracefully (the spawner skips it). All stats are `[TUNE]` placeholders.
 */
object HostileArchetypes {
    /**
     * The MVP encounter enemy: an aggressive light raider with modest per-section HP and a single
     * forward gun. Tuned so the AC#8 recorded playthrough can spawn it, hit it a few times and destroy
     * it deterministically. [TUNE]
     */
    val RAIDER: HostileArchetype =
        HostileArchetype(
            id = HostileArchetypeId("raider"),
            displayName = "Raider",
            behavior = AiBehavior.AGGRESSIVE,
            difficulty = DifficultyTier.NORMAL,
            maxSpeed = 90f,
            acceleration = 80f,
            sectionHp =
                mapOf(
                    ShipSection.HULL to 30,
                    ShipSection.ENGINE to 15,
                    ShipSection.TURRET to 10,
                    ShipSection.WEAPON to 10,
                ),
            weapon =
                FixedWeapon(
                    damage = 4,
                    cooldownSeconds = 1.2f,
                    projectileSpeed = 320f,
                    range = 520f,
                ),
            engageRange = 480f,
            leashRange = 1400f,
            fleeHullFraction = 0f,
        )

    /**
     * A skittish scavenger: lighter and faster, runs once its hull is below a third — exercises the
     * FLEE_WHEN_DAMAGED behaviour and the AC#6 outrun/leash path. [TUNE]
     */
    val SCAVENGER: HostileArchetype =
        HostileArchetype(
            id = HostileArchetypeId("scavenger"),
            displayName = "Scavenger",
            behavior = AiBehavior.FLEE_WHEN_DAMAGED,
            difficulty = DifficultyTier.EASY,
            maxSpeed = 130f,
            acceleration = 110f,
            sectionHp =
                mapOf(
                    ShipSection.HULL to 18,
                    ShipSection.ENGINE to 10,
                    ShipSection.TURRET to 6,
                    ShipSection.WEAPON to 6,
                ),
            weapon =
                FixedWeapon(
                    damage = 3,
                    cooldownSeconds = 1.5f,
                    projectileSpeed = 300f,
                    range = 440f,
                ),
            engageRange = 420f,
            leashRange = 1100f,
            fleeHullFraction = 0.34f,
        )

    /**
     * A faction-affiliated raider flying the colours of the unaligned [Factions.INDEPENDENTS] fringe
     * (UC43) — the first archetype with a non-null [HostileArchetype.factionId], so destroying it moves
     * the player's Independents standing through the combat→reputation seam (ADR 0031). Stats mirror
     * [RAIDER] (AGGRESSIVE, 30-HP hull) so the same HULL-funnelled combat tuning lands a compact,
     * deterministic kill in the UC43 replay. [RAIDER]/[SCAVENGER] stay deliberately **unaligned** (null
     * faction) — preserving the neutral-hostile rule and keeping every committed combat fixture
     * byte-identical (no authored zone spawns this archetype except the new disjoint UC43 zone). [TUNE]
     */
    val INDEPENDENT_MARAUDER: HostileArchetype =
        HostileArchetype(
            id = HostileArchetypeId("independent-marauder"),
            displayName = "Independent Marauder",
            behavior = AiBehavior.AGGRESSIVE,
            difficulty = DifficultyTier.NORMAL,
            maxSpeed = 90f,
            acceleration = 80f,
            sectionHp =
                mapOf(
                    ShipSection.HULL to 30,
                    ShipSection.ENGINE to 15,
                    ShipSection.TURRET to 10,
                    ShipSection.WEAPON to 10,
                ),
            weapon =
                FixedWeapon(
                    damage = 4,
                    cooldownSeconds = 1.2f,
                    projectileSpeed = 320f,
                    range = 520f,
                ),
            engageRange = 480f,
            leashRange = 1400f,
            fleeHullFraction = 0f,
            factionId = Factions.INDEPENDENTS.id,
        )

    /**
     * A wary marauder that fights, then **retreats-and-regroups** (UC45 AC#2): it closes and fires while
     * healthy but, once its hull drops below a third, runs out to [HostileArchetype.regroupRange] and then
     * turns back to re-engage from that standoff distance instead of fleeing for good. Deliberately
     * **unaligned** (`factionId = null`, CONDITION #2) so it carries no reputation effect. Slightly tankier
     * than a [RAIDER] so the regroup cycle is observable in a replay. [TUNE]
     */
    val REGROUP_MARAUDER: HostileArchetype =
        HostileArchetype(
            id = HostileArchetypeId("regroup-marauder"),
            displayName = "Regroup Marauder",
            behavior = AiBehavior.RETREAT_AND_REGROUP,
            difficulty = DifficultyTier.HARD,
            maxSpeed = 100f,
            acceleration = 90f,
            sectionHp =
                mapOf(
                    ShipSection.HULL to 36,
                    ShipSection.ENGINE to 16,
                    ShipSection.TURRET to 12,
                    ShipSection.WEAPON to 12,
                ),
            weapon =
                FixedWeapon(
                    damage = 4,
                    cooldownSeconds = 1.2f,
                    projectileSpeed = 320f,
                    range = 520f,
                ),
            engageRange = 480f,
            leashRange = 1400f,
            fleeHullFraction = 0.34f,
            regroupRange = 700f,
        )

    /**
     * A precision raider whose fire **targets the player's weakest section** (UC45 AC#2): instead of the
     * RNG-weighted hit spread, each shot is stamped at fire time with the player's lowest-HP-fraction
     * section ([WeakestSection]) and applies its damage there directly. AGGRESSIVE otherwise. Deliberately
     * **unaligned** (`factionId = null`, CONDITION #2). [TUNE]
     */
    val PRECISION_RAIDER: HostileArchetype =
        HostileArchetype(
            id = HostileArchetypeId("precision-raider"),
            displayName = "Precision Raider",
            behavior = AiBehavior.AGGRESSIVE,
            difficulty = DifficultyTier.HARD,
            maxSpeed = 90f,
            acceleration = 80f,
            sectionHp =
                mapOf(
                    ShipSection.HULL to 30,
                    ShipSection.ENGINE to 15,
                    ShipSection.TURRET to 10,
                    ShipSection.WEAPON to 10,
                ),
            weapon =
                FixedWeapon(
                    damage = 4,
                    cooldownSeconds = 1.2f,
                    projectileSpeed = 320f,
                    range = 520f,
                ),
            engageRange = 480f,
            leashRange = 1400f,
            fleeHullFraction = 0f,
            targetsWeakestSection = true,
        )

    /** Every authored archetype, in authored order. */
    val all: List<HostileArchetype> =
        listOf(RAIDER, SCAVENGER, INDEPENDENT_MARAUDER, REGROUP_MARAUDER, PRECISION_RAIDER)

    private val byId: Map<HostileArchetypeId, HostileArchetype> = all.associateBy { it.id }

    /** The archetype with [id], or null if it is not catalogued (e.g. a removed/typo'd authored id). */
    fun byId(id: HostileArchetypeId): HostileArchetype? = byId[id]
}
