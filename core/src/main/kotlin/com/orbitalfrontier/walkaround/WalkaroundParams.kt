package com.orbitalfrontier.walkaround

/**
 * Authored tunables for on-foot walk-around movement + interaction (UC19).
 *
 * Pure data (no libGDX types); the same instance feeds [WalkaroundModel] in the live screen and in
 * JVM tests so behaviour matches exactly (ADR 0001 / determinism). World units per second / world
 * units. [TUNE]
 *
 * @property moveSpeed avatar walk speed in world units per second at full stick deflection.
 * @property avatarRadius drawn ball radius (purely visual here; loose single-point collision is used).
 * @property shopkeeperInteractRadius how close the avatar must be to the shopkeeper for the interact
 *   prompt to appear (AC#6).
 */
data class WalkaroundParams(
    val moveSpeed: Float = 140f,
    val avatarRadius: Float = 12f,
    val shopkeeperInteractRadius: Float = 70f,
)
