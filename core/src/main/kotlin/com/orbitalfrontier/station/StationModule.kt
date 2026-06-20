package com.orbitalfrontier.station

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType

/**
 * Stable identity of a [StationModule] (UC15). A value class over a [String] slug — the
 * [com.orbitalfrontier.outfit.UpgradeId] analogue — so a module is keyed by a diffable, save-stable
 * name (`station_module.module_type`) rather than an ordinal: appending or reordering the
 * [StationModuleCatalog] never invalidates a saved station. Blank ids are rejected (an authoring
 * error — fail fast).
 */
@JvmInline
value class StationModuleId(val value: String) {
    init {
        require(value.isNotBlank()) { "StationModuleId must not be blank" }
    }
}

/**
 * What it costs to build one [StationModule] (UC15 AC#1) — a credits price **and/or** a bill of mined
 * resources, paid out of the active ship's cargo hold.
 *
 * Pure authored data (no engine types). Both parts are optional: a module may cost only credits
 * (empty [resources]), only resources (zero [credits]), or both. Affordability is **atomic** — the
 * builder ([StationBuilder]) checks credits and *every* resource up front and deducts all-or-nothing,
 * so a shortfall in any single line leaves the wallet and hold untouched (no partial deduction).
 *
 * All money math is [Long] so a large balance never overflows; resource amounts are positive unit
 * counts keyed by [ResourceType].
 */
data class StationBuildCost(
    /** Credits charged to build the module (>= 0). [TUNE] */
    val credits: Long = 0L,
    /** Mined-resource units charged, drawn from the active ship's cargo (each amount >= 1). [TUNE] */
    val resources: Map<ResourceType, Int> = emptyMap(),
) {
    init {
        require(credits >= 0) { "StationBuildCost credits must not be negative: $credits" }
        require(resources.values.all { it >= 1 }) {
            "StationBuildCost resource amounts must be >= 1: $resources"
        }
    }

    /**
     * Whether [credits] **and** every resource line in this cost are covered by [credits] + [cargo]
     * (UC51) — the **atomic** affordability check, all-or-nothing. The single source of truth used both
     * by [StationBuilder] before it deducts (so the wallet/hold are never partially drawn down) and by
     * the build-screen model ([StationBuildMenu]) to show a per-option affordable flag (so the preview
     * never disagrees with the charge). Resources are read from the active ship's hold via
     * [Cargo.contents]; an absent key counts as 0.
     */
    fun canAfford(
        credits: Long,
        cargo: Cargo,
    ): Boolean {
        if (credits < this.credits) return false
        for ((resource, units) in resources) {
            if ((cargo.contents[resource] ?: 0) < units) return false
        }
        return true
    }
}

/**
 * One buildable station module (UC15 AC#1/#2) — a piece a player snaps onto a station, exposing a
 * single [function] (commerce, retrofit, …) and built for a [cost].
 *
 * Pure authored data (no engine types), catalogued in [StationModuleCatalog] — the
 * [com.orbitalfrontier.outfit.Upgrade] analogue. An [OwnedStation] stores only the [id] per slot and
 * resolves the full [StationModule] back through the catalog on load, so a cost/function retune lives
 * in one place and a saved station referencing a module that was later removed simply resolves to
 * null and is skipped (never stranded — see
 * [com.orbitalfrontier.save.SqlDelightGameStateRepository]).
 */
data class StationModule(
    val id: StationModuleId,
    val function: StationFunction,
    val displayName: String,
    val cost: StationBuildCost,
) {
    init {
        require(displayName.isNotBlank()) { "StationModule ${id.value} displayName must not be blank" }
    }
}
