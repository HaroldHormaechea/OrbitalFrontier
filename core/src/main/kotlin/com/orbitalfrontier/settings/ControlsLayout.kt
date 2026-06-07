package com.orbitalfrontier.settings

/** Which side of the screen a control occupies. */
enum class ScreenSide {
    LEFT,
    RIGHT,
}

/**
 * Pure mapping from [Handedness] to the screen sides of the two on-screen controls (AC#7/#8).
 *
 * No engine types — the screen layer reads these sides to position the joystick and the action
 * cluster, so the swap is fully unit-testable independent of libGDX.
 */
data class ControlsLayout(
    val movementStickSide: ScreenSide,
    val actionClusterSide: ScreenSide,
) {
    companion object {
        fun forHandedness(handedness: Handedness): ControlsLayout =
            when (handedness) {
                Handedness.RIGHT_HANDED -> ControlsLayout(ScreenSide.LEFT, ScreenSide.RIGHT)
                Handedness.LEFT_HANDED -> ControlsLayout(ScreenSide.RIGHT, ScreenSide.LEFT)
            }
    }
}
