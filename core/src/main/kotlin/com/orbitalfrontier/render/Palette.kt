package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color

/**
 * The Orbital Frontier design-system colour palette (UC27 / AC#8), transcribed from the design bundle's
 * `tokens/colors.css` ("used future" — cold void/steel surfaces, warm amber primary, cold cyan secondary,
 * and status signals).
 *
 * These are the single source of truth for non-sprite colours across the game: screen clear-colours, HUD
 * text, the minimap panel/border, the ship-schematic states, and control tints. Screens and renderers
 * reference these constants instead of ad-hoc literals so the look is consistent and one edit re-themes
 * everything. The built-in [com.badlogic.gdx.graphics.g2d.BitmapFont] is retained (custom fonts deferred).
 *
 * Each [Color] is parsed once from its hex token via [Color.valueOf] (RRGGBB). They are shared, immutable
 * intent — **never** mutate a returned instance in a draw loop (set components on the renderer's own Color,
 * or pass these straight to `setColor`). Defined at class-load, so no per-frame allocation.
 */
object Palette {
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

    // ---- HAZARD & STATUS ----
    val HAZARD_500: Color = Color.valueOf("f4c40e")
    val SUCCESS: Color = Color.valueOf("4fb477") // docking clear, profit, healthy
    val WARNING: Color = Color.valueOf("ffb24d") // caution
    val DANGER: Color = Color.valueOf("e0563f") // hull breach, loss, critical

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
