package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * A point of interest sitting in a sector's content cluster.
 *
 * Modelled as a **sealed interface** for the Open/Closed Principle (coding-guidelines § O): the set
 * of POI kinds is closed and exhaustive at compile time, yet new kinds are added by introducing a
 * new subtype — never by editing a central `when`. [JumpGate] is the only concrete kind in the UC03
 * MVP; stations and asteroid fields (see docs/design/world-and-sector.md) plug in here later without
 * touching existing code.
 *
 * Every POI has a stable [id] (unique within its sector) and a [position] in the sector's
 * world-unit coordinate space, whose origin is the sector centre.
 */
sealed interface Poi {
    val id: PoiId
    val position: Vec2
}
