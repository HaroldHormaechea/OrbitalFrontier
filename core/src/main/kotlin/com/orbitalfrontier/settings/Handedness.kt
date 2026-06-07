package com.orbitalfrontier.settings

/**
 * Control-layout handedness preference (AC#8). Persisted via the settings store.
 *
 * [RIGHT_HANDED] is the default: movement stick on the left, action cluster on the right.
 * [LEFT_HANDED] mirrors the two.
 */
enum class Handedness {
    RIGHT_HANDED,
    LEFT_HANDED,
    ;

    /** The opposite handedness — used by the settings toggle. */
    fun toggled(): Handedness = if (this == RIGHT_HANDED) LEFT_HANDED else RIGHT_HANDED

    companion object {
        val DEFAULT = RIGHT_HANDED
    }
}
