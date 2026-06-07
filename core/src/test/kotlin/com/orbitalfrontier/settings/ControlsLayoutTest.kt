package com.orbitalfrontier.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure handedness → screen-side mapping (the JVM-testable half of AC#8).
 */
class ControlsLayoutTest {
    @Test
    fun `right-handed places the movement stick on the left and actions on the right`() {
        val layout = ControlsLayout.forHandedness(Handedness.RIGHT_HANDED)

        assertEquals(ScreenSide.LEFT, layout.movementStickSide)
        assertEquals(ScreenSide.RIGHT, layout.actionClusterSide)
    }

    @Test
    fun `left-handed mirrors the two controls`() {
        val layout = ControlsLayout.forHandedness(Handedness.LEFT_HANDED)

        assertEquals(ScreenSide.RIGHT, layout.movementStickSide)
        assertEquals(ScreenSide.LEFT, layout.actionClusterSide)
    }

    @Test
    fun `toggling handedness swaps to the opposite value`() {
        assertEquals(Handedness.LEFT_HANDED, Handedness.RIGHT_HANDED.toggled())
        assertEquals(Handedness.RIGHT_HANDED, Handedness.LEFT_HANDED.toggled())
    }

    @Test
    fun `default handedness is right-handed`() {
        assertEquals(Handedness.RIGHT_HANDED, Handedness.DEFAULT)
    }

    @Test
    fun `toggling twice returns to the original handedness`() {
        assertEquals(Handedness.RIGHT_HANDED, Handedness.RIGHT_HANDED.toggled().toggled())
    }
}
