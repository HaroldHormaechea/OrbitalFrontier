package com.orbitalfrontier.render

/**
 * Pure, libGDX-free open/closed state for the click-to-zoom map overlay (UC23).
 *
 * The minimap (UC22) stays the always-on HUD element; tapping it toggles this larger inspection
 * overlay open, and any tap while it is open dismisses it. Modelled as an immutable value with
 * [toggled]/[dismissed] transitions so the toggle logic is JVM-unit-testable (ADR 0001) independent
 * of any Scene2D actor — [com.orbitalfrontier.screen.PlayScreen] holds one of these and swaps it on a
 * tap, then reads [isOpen] each frame to drive the overlay draw + control visibility.
 */
data class MapOverlayState(val isOpen: Boolean = false) {
    /** The state after a minimap tap: flip open<->closed. */
    fun toggled(): MapOverlayState = copy(isOpen = !isOpen)

    /** The state after a dismiss gesture: always closed (returns `this` when already closed). */
    fun dismissed(): MapOverlayState = if (isOpen) copy(isOpen = false) else this
}
