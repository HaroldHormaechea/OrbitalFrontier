package com.orbitalfrontier.economy

/**
 * Authored tunables for the mining interaction (UC06 AC#2) — how fast a field is extracted.
 *
 * Pure data injected into [com.orbitalfrontier.world.Mining.resolve] (constructor/parameter
 * injection, coding-guidelines § DIP) so the extraction rate is configurable per call and the
 * mining logic stays a pure function of its inputs — the same params always yield the same result,
 * keeping mining deterministic and JVM-testable (UC06 AC#6).
 *
 * The **extraction order** is *not* configured here: it is a fixed code invariant equal to the
 * [ResourceType] ordinal order (see [com.orbitalfrontier.world.Mining]), so balancing only ever
 * tunes the rate, never the determinism.
 */
data class MiningParams(
    /** Total units a single mining tick extracts from a field (summed across resource types). */
    val extractionUnitsPerTick: Int = DEFAULT_EXTRACTION_UNITS_PER_TICK,
) {
    init {
        require(extractionUnitsPerTick > 0) {
            "extractionUnitsPerTick must be positive: $extractionUnitsPerTick"
        }
    }

    companion object {
        /** Default per-tick extraction budget (units). An authored balancing tunable. [TUNE] */
        const val DEFAULT_EXTRACTION_UNITS_PER_TICK: Int = 2
    }
}
