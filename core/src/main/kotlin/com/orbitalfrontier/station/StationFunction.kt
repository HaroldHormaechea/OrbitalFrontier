package com.orbitalfrontier.station

/**
 * What a built station module lets a player-owned station **do** (UC15 AC#2/#6).
 *
 * A closed set modelled as an `enum` (coding-guidelines § O): the MVP station functions are fixed and
 * known at compile time, and a new function is added by introducing a new constant — never by editing
 * a central `when`. Each [StationModule] exposes exactly one function; an [OwnedStation] derives the
 * **set** of functions its installed modules provide ([OwnedStation.availableFunctions], AC#2).
 *
 * Pure identity only (no engine types, no behaviour) so the whole station model stays JVM-testable
 * (UC15 AC#5). What a function *does* in the wider game (a commerce module opening a trade desk, a
 * retrofit module opening an outfitting desk) is a deferred, post-MVP surfacing concern — see
 * `docs/design/station-building.md` and ADR 0014; only the ownership + function-availability sim is
 * built in UC15.
 */
enum class StationFunction {
    /** A trade hub — passive/active commerce (the player-owned analogue of a station trade desk). */
    COMMERCE,

    /** An outfitting / refit bay — retrofit (the player-owned analogue of an outfit/junkyard desk). */
    RETROFIT,
}
