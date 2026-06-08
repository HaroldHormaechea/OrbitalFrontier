package com.orbitalfrontier.render

import com.badlogic.gdx.utils.viewport.ScreenViewport

/**
 * Apply the global [UiScale.factor] to a Scene2D [ScreenViewport] (ADR 0015).
 *
 * A [ScreenViewport]'s `unitsPerPixel` is libGDX's built-in scale knob: the world size it reports is
 * `screenPixels * unitsPerPixel`, and actors laid out in those world units therefore occupy
 * `1 / unitsPerPixel` pixels each. Setting it to `1 / factor` (e.g. `0.5` for a ×2 factor) makes every
 * actor + font on the stage render `factor` times larger, with zero per-widget edits — the single DRY
 * application point every screen routes through.
 *
 * `unitsPerPixel` persists across `ScreenViewport.update(...)` (resize), so calling this once at stage
 * construction is sufficient; it is exposed as an extension so each screen's `Stage(ScreenViewport())`
 * becomes `Stage(ScreenViewport().apply { applyUiScale() })`.
 */
fun ScreenViewport.applyUiScale() {
    setUnitsPerPixel(1f / UiScale.factor)
}
