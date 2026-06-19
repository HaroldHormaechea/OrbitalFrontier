package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
import com.orbitalfrontier.settings.ColorVisionMode

/**
 * The Orbital Frontier design-system colour palette (UC27 / AC#8), transcribed from the design bundle's
 * `tokens/colors.css` ("used future" — cold void/steel surfaces, warm amber primary, cold cyan secondary,
 * and status signals).
 *
 * These are the single source of truth for non-sprite colours across the game: screen clear-colours, HUD
 * text, the minimap panel/border, the ship-schematic states, and control tints. Screens and renderers
 * reference these constants instead of ad-hoc literals so the look is consistent and one edit re-themes
 * everything. Text is drawn in the bundled game font ([com.orbitalfrontier.render.GameFont]) as of UC28;
 * these colours tint it (the font is baked white, so `font.color` tints survive — UC27 AC#8).
 *
 * Each [Color] is parsed once from its hex token via [Color.valueOf] (RRGGBB). They are shared, immutable
 * intent — **never** mutate a returned instance in a draw loop (set components on the renderer's own Color,
 * or pass these straight to `setColor`). Defined at class-load, so no per-frame allocation.
 *
 * **Colour-vision mode (UC39 AC#1).** The STATE-conveying tokens — [DANGER], [SUCCESS], [WARNING],
 * [HAZARD_500] and the [CONTACT_HOSTILE] / [STATION_FRIENDLY] map markers — are **mode-aware**: each is a
 * `get()` that returns one of two CACHED, IMMUTABLE [Color] instances (the standard token, or an Okabe-Ito
 * colourblind-safe variant) depending on [mode]. Because each mode has a single stable instance, there is
 * still **no per-frame allocation** and reference-identity assertions stay valid. The brand accents
 * (amber/cyan) and the structural neutrals (void/steel) are colour-vision-independent and stay plain `val`s
 * — they do not convey state, so they are never remapped. [mode] is a clamped mutable global restored at
 * startup ([setMode]) from the persisted [ColorVisionMode], with the single-writer (settings control) /
 * single-reader-per-frame access pattern of [UiScale] / [TextScale] / [MotionPreference]; [reset] returns
 * it to the default for tests.
 */
object Palette {
    // ---- COLOUR-VISION MODE (UC39) ----
    private var currentMode: ColorVisionMode = ColorVisionMode.DEFAULT

    /** The active colour-vision palette mode (UC39 AC#1). [STANDARD][ColorVisionMode.STANDARD] by default. */
    val mode: ColorVisionMode get() = currentMode

    /** Switch the active colour-vision [mode] (UC39). Restored at startup and toggled by the settings control. */
    fun setMode(mode: ColorVisionMode) {
        currentMode = mode
    }

    /** Reset the colour-vision mode to the default — for tests / a settings "reset to default" path. */
    fun reset() {
        currentMode = ColorVisionMode.DEFAULT
    }

    private val colorblind: Boolean get() = currentMode == ColorVisionMode.COLORBLIND_SAFE

    // ---- VOID & STEEL (structural neutrals) ----
    val VOID_900: Color = Color.valueOf("06080b") // deepest space, app backdrop
    val VOID_800: Color = Color.valueOf("0b0f14") // primary surface
    val VOID_700: Color = Color.valueOf("11161d") // raised surface
    val STEEL_600: Color = Color.valueOf("19212a") // panel fill
    val STEEL_500: Color = Color.valueOf("232e39") // panel fill (raised)
    val STEEL_400: Color = Color.valueOf("34424f") // hairline borders / bevels
    val STEEL_300: Color = Color.valueOf("4d5e6c") // disabled / muted strokes
    val STEEL_200: Color = Color.valueOf("788a98") // secondary text, icons
    val STEEL_100: Color = Color.valueOf("aab8c4") // body text on dark
    val STEEL_050: Color = Color.valueOf("d8e0e7") // high-emphasis text
    val STEEL_000: Color = Color.valueOf("f1f5f8") // pure readout white

    // ---- SIGNAL: AMBER (primary brand accent) ----
    val AMBER_600: Color = Color.valueOf("e07f12")
    val AMBER_500: Color = Color.valueOf("ff9e2c") // PRIMARY accent
    val AMBER_400: Color = Color.valueOf("ffb24d")
    val AMBER_300: Color = Color.valueOf("ffc878")

    // ---- SIGNAL: CYAN (secondary accent / cold tech) ----
    val CYAN_600: Color = Color.valueOf("119aa6")
    val CYAN_500: Color = Color.valueOf("1fcad6") // SECONDARY accent
    val CYAN_400: Color = Color.valueOf("4fe0ea")

    // ---- OKABE-ITO COLOURBLIND-SAFE CONSTANTS (UC39 AC#1) ----
    // The vetted Okabe-Ito qualitative palette: maximally distinguishable across the common colour-vision
    // deficiencies. Used only as the COLOURBLIND_SAFE variants of the state tokens + the faction colours
    // ([com.orbitalfrontier.faction.FactionColors]). Public so those resolvers and tests can reference them.
    val OKABE_VERMILLION: Color = Color.valueOf("d55e00") // hostile / danger
    val OKABE_BLUISH_GREEN: Color = Color.valueOf("009e73") // friendly / success
    val OKABE_ORANGE: Color = Color.valueOf("e69f00") // warning
    val OKABE_SKY_BLUE: Color = Color.valueOf("56b4e9") // info
    val OKABE_YELLOW: Color = Color.valueOf("f0e442") // hazard (distinct from the orange warning)
    val OKABE_BLUE: Color = Color.valueOf("0072b2") // faction (League)

    // ---- HAZARD & STATUS (mode-aware: standard token | Okabe-Ito colourblind-safe variant) ----
    // Each token caches ONE Color per mode (no per-frame alloc; stable reference identity). The standard
    // instances keep the exact UC27 hex so STANDARD-mode rendering is byte-identical to pre-UC39.
    private val HAZARD_500_STANDARD: Color = Color.valueOf("f4c40e")
    private val SUCCESS_STANDARD: Color = Color.valueOf("4fb477") // docking clear, profit, healthy
    private val WARNING_STANDARD: Color = Color.valueOf("ffb24d") // caution
    private val DANGER_STANDARD: Color = Color.valueOf("e0563f") // hull breach, loss, critical

    val HAZARD_500: Color get() = if (colorblind) OKABE_YELLOW else HAZARD_500_STANDARD
    val SUCCESS: Color get() = if (colorblind) OKABE_BLUISH_GREEN else SUCCESS_STANDARD
    val WARNING: Color get() = if (colorblind) OKABE_ORANGE else WARNING_STANDARD
    val DANGER: Color get() = if (colorblind) OKABE_VERMILLION else DANGER_STANDARD

    // ---- MAP-MARKER STATE COLOURS (UC39 AC#1; the actual red/green "hostile vs friendly" site) ----
    // The MapOverlayRenderer (and the minimap markers) draw a friendly-green station marker and a
    // hostile-red contact marker. These mode-aware accessors are the single source for those markers, so
    // the colourblind palette remaps them (UC39). The STANDARD instances reproduce the EXACT pre-UC39
    // marker hues (the migrated class-load literals) so STANDARD-mode markers are unchanged.
    private val STATION_FRIENDLY_STANDARD: Color = Color(0.5f, 1f, 0.6f, 1f)
    private val CONTACT_HOSTILE_STANDARD: Color = Color(1f, 0.4f, 0.4f, 1f)

    /** Friendly/station map-marker colour (green → Okabe-Ito bluish-green in colourblind mode). */
    val STATION_FRIENDLY: Color get() = if (colorblind) OKABE_BLUISH_GREEN else STATION_FRIENDLY_STANDARD

    /** Hostile/contact map-marker colour (red → Okabe-Ito vermillion in colourblind mode). */
    val CONTACT_HOSTILE: Color get() = if (colorblind) OKABE_VERMILLION else CONTACT_HOSTILE_STANDARD

    // ---- SEMANTIC ALIASES (reach for these) ----

    /** App backdrop / deepest-space clear colour (the flight screen). */
    val SURFACE_APP: Color = VOID_900

    /** Primary surface clear colour (menus, station/desk screens). */
    val SURFACE_BASE: Color = VOID_800

    /** Primary brand accent (amber). */
    val ACCENT: Color = AMBER_500

    /** Secondary accent (cyan). */
    val ACCENT_SECONDARY: Color = CYAN_500

    /** High-emphasis text on dark surfaces. */
    val TEXT_STRONG: Color = STEEL_050

    /** Body text on dark surfaces. */
    val TEXT_BODY: Color = STEEL_100

    /** Hairline border / panel edge. */
    val BORDER: Color = STEEL_400
}
