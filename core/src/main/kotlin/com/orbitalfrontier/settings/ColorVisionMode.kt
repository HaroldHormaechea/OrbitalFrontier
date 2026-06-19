package com.orbitalfrontier.settings

/**
 * Colour-vision palette preference (UC39 AC#1). Persisted via the settings store, restored into the
 * render-layer [com.orbitalfrontier.render.Palette] at startup.
 *
 * [STANDARD] is the default — the original design-system palette. [COLORBLIND_SAFE] swaps the
 * STATE-conveying colours (hostile/danger, friendly/success, warning, info) for an Okabe-Ito
 * colourblind-safe set so red-green-deficient players can still tell those states apart; the brand
 * accents (amber/cyan) and the structural neutrals (void/steel) are unaffected.
 *
 * A pure value beside [Handedness] / [AudioSettings] — no engine types — so it is JVM-testable
 * (ADR 0001) and round-trips through the settings store unchanged. [parse] mirrors [Handedness]'s
 * unknown-value handling: an unrecognised stored value degrades to [DEFAULT].
 */
enum class ColorVisionMode {
    STANDARD,
    COLORBLIND_SAFE,
    ;

    /** The other mode — used by the settings toggle. */
    fun toggled(): ColorVisionMode = if (this == STANDARD) COLORBLIND_SAFE else STANDARD

    companion object {
        val DEFAULT = STANDARD

        /**
         * The mode named [value], or [DEFAULT] when [value] is null or not a known mode (a corrupt or
         * future-written save row), so a bad value can never crash the palette restore — it falls back to
         * the standard palette with the caller free to log a WARN.
         */
        fun parse(value: String?): ColorVisionMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
