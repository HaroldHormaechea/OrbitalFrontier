package com.orbitalfrontier.render

/**
 * An immutable, **engine-free** descriptor of how a [com.orbitalfrontier.world.Poi] looks in world
 * space (ADR 0015) — the structural guarantee that *every* POI has an in-world graphic.
 *
 * UC27 swapped the old generated-shape descriptor (a [GlyphShape] + RGBA floats) for a sprite reference:
 * a [regionName] naming a region in the design-system art atlas (an [AtlasRegions] constant). It is still
 * a pure `core` type carrying **no** libGDX dependency (just a `String` + sizes), so it stays JVM-testable
 * per ADR 0001; [WorldObjectRenderer] is the only thing that turns the region name into a draw call.
 *
 * It also carries a [sizeWorldUnits] (the marker's half-extent in world units — preserved across the art
 * swap so positions/collision/camera are unchanged, AC#4) and an optional [label] (only stations carry one
 * — their display name; the ADR-0015 station-label contract, kept even though the sprite path draws it via
 * the minimap/HUD rather than stamping it in-world).
 */
data class WorldGlyph(
    val regionName: String,
    val sizeWorldUnits: Float,
    val label: String? = null,
) {
    init {
        require(sizeWorldUnits > 0f) { "WorldGlyph sizeWorldUnits must be positive: $sizeWorldUnits" }
        require(regionName.isNotBlank()) { "WorldGlyph regionName must not be blank" }
    }
}
