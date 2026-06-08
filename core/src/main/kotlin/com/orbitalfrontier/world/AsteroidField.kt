package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType

/**
 * An asteroid-field POI — a minable point of interest holding a data-driven set of resource deposits
 * (UC06 AC#1; docs/design/economy-and-resources.md "Resources", world-and-sector.md "asteroids").
 *
 * Asteroid fields sit in a sector's content cluster like any other [Poi]. Flying within
 * [miningRadius] of [position] makes the field minable (see [Mining]); holding the mine action then
 * extracts [deposits] into the ship's cargo over time. A field is a **plain [Poi]** — deliberately
 * *not* a [Transponder]: asteroid fields do not broadcast and are not auto-revealed on the minimap;
 * proximity-based detection is UC10's concern, so a field only renders in-world (see
 * [com.orbitalfrontier.render.AsteroidFieldRenderer]).
 *
 * [deposits] is the field's **pristine** (authored) content. The *remaining* per-field amounts after
 * mining live separately in [WorldState.fieldDepletion] (keyed by [id]) so the authored map stays
 * immutable and a save records only what has changed — an absent depletion entry means pristine.
 *
 * Pure data — no engine types — so fields are part of the JVM-testable world model (ADR 0001) and
 * the mining logic that reads them ([Mining]) stays unit-testable on the JVM (UC06 AC#6).
 */
data class AsteroidField(
    override val id: PoiId,
    override val position: Vec2,
    /** Radius (world-units) of the circle around [position] within which the ship can mine. */
    val miningRadius: Float = DEFAULT_MINING_RADIUS,
    /** Authored pristine deposits: resource → total units present before any mining. */
    val deposits: Map<ResourceType, Int>,
) : Poi {
    init {
        require(miningRadius > 0f) { "AsteroidField $id miningRadius must be positive: $miningRadius" }
        require(deposits.isNotEmpty()) { "AsteroidField $id must have at least one deposit" }
        require(deposits.values.all { it > 0 }) {
            "AsteroidField $id deposit amounts must be positive: $deposits"
        }
    }

    companion object {
        /**
         * Default mining-range radius (world-units). Comparable to a station's docking radius so a
         * field is forgiving to line up; an authored tunable, overridable per field. [TUNE]
         */
        const val DEFAULT_MINING_RADIUS: Float = 100f
    }
}
