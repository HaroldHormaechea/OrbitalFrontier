package com.orbitalfrontier.faction

/**
 * Stable identity of a [Faction] (UC14 AC#1). A `value class` over a non-blank `String` slug, so the
 * authored catalog ([Factions]) can grow without renumbering and a persisted / replayed reference
 * stays stable across runs and refactors. Blank ids are an authoring error (fail fast).
 *
 * Mirrors [com.orbitalfrontier.combat.HostileArchetypeId] / [com.orbitalfrontier.ship.ShipTypeId]:
 * pure, no engine types, so the whole `faction` package is JVM-testable (UC14 AC#5). The slug — never
 * an enum ordinal / identity hashCode — is what persistence stores ([com.orbitalfrontier.world.Station]
 * faction column, the `reputation` table key) and what the deterministic gate/reputation logic keys on,
 * so it must be stable.
 */
@JvmInline
value class FactionId(val value: String) {
    init {
        require(value.isNotBlank()) { "FactionId must not be blank" }
    }
}
