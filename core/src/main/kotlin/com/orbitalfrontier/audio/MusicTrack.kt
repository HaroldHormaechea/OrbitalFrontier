package com.orbitalfrontier.audio

/**
 * The catalogue of background music tracks (UC31 AC#2).
 *
 * Pure, engine-free enum (the platform-side audio service maps each entry to a libGDX `Music` asset).
 * [FLIGHT] plays while roaming the sector; [STATION] plays while docked / on foot at a station, so the
 * two contexts have distinct ambience.
 */
enum class MusicTrack {
    /** Ambient track played while flying the sector. */
    FLIGHT,

    /** Ambient track played while docked at / walking around a station. */
    STATION,
}
