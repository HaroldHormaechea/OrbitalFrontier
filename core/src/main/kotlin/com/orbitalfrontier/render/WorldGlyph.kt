package com.orbitalfrontier.render

/**
 * The primitive shape a [WorldGlyph] is drawn as by [WorldObjectRenderer].
 *
 * A small closed set (coding-guidelines § O): each value names an authored placeholder marker until
 * real sprites exist. The renderer switches its drawing on this enum; a new shape is added by extending
 * both this enum and the renderer's switch together.
 */
enum class GlyphShape {
    /** A filled diamond (two triangles) — the jump-gate marker. */
    DIAMOND,

    /** A filled square centred on the position — the station / hidden-contact placeholder. */
    BOX,

    /** A small cluster of filled rocks — the asteroid-field marker. */
    ROCK_CLUSTER,
}

/**
 * An immutable, **engine-free** descriptor of how a [com.orbitalfrontier.world.Poi] looks in world
 * space (ADR 0015) — the structural guarantee that *every* POI has an in-world graphic.
 *
 * It carries a [shape], an RGBA colour as four plain floats (deliberately **not** a libGDX `Color`, so
 * this stays a pure `core` type that is JVM-testable per ADR 0001 and carries no rendering dependency),
 * a [sizeWorldUnits] (the marker's half-extent / characteristic radius in world units), and an optional
 * [label] (only stations carry one — their display name; all other glyphs are unlabelled constants).
 *
 * [WorldObjectRenderer] is the only thing that maps these fields onto libGDX draw calls, so the
 * "what does a POI look like" decision lives in pure code ([WorldGlyphs]) and only the *drawing* touches
 * the engine.
 */
data class WorldGlyph(
    val shape: GlyphShape,
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
    val sizeWorldUnits: Float,
    val label: String? = null,
) {
    init {
        require(sizeWorldUnits > 0f) { "WorldGlyph sizeWorldUnits must be positive: $sizeWorldUnits" }
    }
}
