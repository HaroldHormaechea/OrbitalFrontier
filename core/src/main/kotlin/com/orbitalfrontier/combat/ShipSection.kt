package com.orbitalfrontier.combat

/**
 * The damageable **sections** of a ship in the per-section combat damage model (UC13 AC#3;
 * docs/design/combat.md). A closed `enum` (coding-guidelines § O): the MVP holds exactly four
 * sections, each with a distinct effect when hit, and a new section is added by introducing a new
 * constant — never by editing a central `when`.
 *
 * **The constant name is the persisted/seed key** — section damage is stored per `ShipSection.name`
 * (stable across reordering, unlike the ordinal) and the damage-model's weighted section pick is keyed
 * by [name], so appending a section is save- and replay-safe but renaming one would be a migration.
 */
enum class ShipSection {
    /** Structural integrity; reaching 0 HP **destroys** the ship (respawn, UC13 AC#5). */
    HULL,

    /** Propulsion; damage scales the ship's max speed down (UC13 AC#3; [CombatLimitedMovement]). */
    ENGINE,

    /** Auto-aim turret mount; at 0 HP the turret is **disabled** (stops firing). */
    TURRET,

    /** Fixed/forward weapon mount; at 0 HP the fixed weapon is **disabled**. */
    WEAPON,
}

/**
 * A ship's **current** per-section hit points (UC13 AC#3). Modelled exactly like
 * [com.orbitalfrontier.world.WorldState.fieldDepletion]: a map of *current* HP per section where an
 * **absent** section is **pristine** (full HP, [com.orbitalfrontier.outfit.ShipStats.sectionHp]). The
 * empty map is therefore an undamaged ship, which keeps the default byte-identical and the canonical
 * representation unique (a section restored to full drops out of the map).
 *
 * Max HP is **never stored** here — it is a derived ship stat
 * ([com.orbitalfrontier.outfit.ShipStats.sectionHp], from type + loadout), so this map only carries the
 * mutable damage state. Helpers live in [SectionDamages].
 */
typealias SectionDamage = Map<ShipSection, Int>

/**
 * Pure helpers over [SectionDamage] (UC13 AC#3). Side-effect-free functions keyed by [ShipSection] —
 * never `hashCode`/ordinal — so they are deterministic and JVM-testable. Every mutator returns a new
 * canonical map (a section back at full HP is dropped, mirroring `fieldDepletion`).
 */
object SectionDamages {
    /** No damage — a pristine ship. The byte-identical default. */
    val PRISTINE: SectionDamage = emptyMap()

    /** Current HP of [section] given its derived [maxHp]; an absent entry means pristine (= [maxHp]). */
    fun currentHp(
        damage: SectionDamage,
        section: ShipSection,
        maxHp: Int,
    ): Int = damage[section] ?: maxHp

    /** True when [section] is fully destroyed (0 current HP). A disabled turret/weapon / dead hull. */
    fun isDestroyed(
        damage: SectionDamage,
        section: ShipSection,
        maxHp: Int,
    ): Boolean = currentHp(damage, section, maxHp) <= 0

    /**
     * Apply [amount] damage to [section] (clamped to `0..maxHp`), returning the new canonical map. A
     * section restored to (or already at) full HP carries no entry; a non-positive [amount] is a no-op
     * that returns the **same** map instance (byte-identical, no allocation).
     */
    fun applyDamage(
        damage: SectionDamage,
        section: ShipSection,
        amount: Int,
        maxHp: Int,
    ): SectionDamage {
        if (amount <= 0) return damage
        val newHp = (currentHp(damage, section, maxHp) - amount).coerceIn(0, maxHp)
        return setHp(damage, section, newHp, maxHp)
    }

    /**
     * Set [section] to exactly [hp] (clamped to `0..maxHp`), returning the new canonical map: at full HP
     * the entry is removed (pristine), otherwise it is stored. Used by repair ([fullRepair]) and damage.
     */
    fun setHp(
        damage: SectionDamage,
        section: ShipSection,
        hp: Int,
        maxHp: Int,
    ): SectionDamage {
        val clamped = hp.coerceIn(0, maxHp)
        return if (clamped >= maxHp) {
            if (section in damage) damage - section else damage
        } else {
            damage + (section to clamped)
        }
    }

    /** Fully repaired — the pristine [SectionDamage] (used by respawn / dock repair, UC13 AC#5). */
    fun fullRepair(): SectionDamage = PRISTINE
}
