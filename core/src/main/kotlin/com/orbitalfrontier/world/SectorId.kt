package com.orbitalfrontier.world

/**
 * Stable identity of a sector in the [SectorWorld] graph (UC03 AC#1).
 *
 * A thin value type around a string slug (e.g. `"alpha"`) so sector references are type-safe and
 * cheap — never bare strings at call sites. Pure: no engine types, so the whole `world` package
 * stays JVM-testable (ADR 0001) and passes the replay-path purity guard.
 *
 * The canonical start sector is **designated by [MvpSectorMap.START_SECTOR]** (not hard-coded
 * here), which the test harness uses as the default current sector of its simulation state.
 */
@JvmInline
value class SectorId(val value: String) {
    init {
        require(value.isNotBlank()) { "SectorId must not be blank" }
    }
}

/**
 * Stable identity of a point of interest within a sector (gates today; stations/asteroids later).
 *
 * Gate-to-gate links ([GateLink]) reference the destination gate by its [PoiId], so identities must
 * be unique within their destination sector — enforced by [SectorWorld] validation at construction.
 */
@JvmInline
value class PoiId(val value: String) {
    init {
        require(value.isNotBlank()) { "PoiId must not be blank" }
    }
}
