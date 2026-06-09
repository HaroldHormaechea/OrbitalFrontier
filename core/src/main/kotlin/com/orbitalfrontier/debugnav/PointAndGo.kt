package com.orbitalfrontier.debugnav

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Station
import kotlin.math.atan2

/**
 * Pure, deterministic destination resolution for the debug point-and-go navigation aid (UC25 AC#2/#3).
 *
 * The libGDX/input glue (the arm toggle, the world-tap input processor, applying the result to the
 * Box2D body) lives in the play screen and is GL-bound; this object holds only the engine-free
 * "where does a tap put the ship?" math so it is JVM-unit-testable headlessly (UC25 risk), mirroring
 * the project's pure-model pattern. It mutates nothing — the caller applies the [Resolution].
 *
 * Resolution rule, given a [tappedWorldPoint] and the sector's [pois]:
 *  - If the tap lands inside some station's [Station.dockingRadius] circle, the **nearest** such
 *    station wins and the ship is placed just inside that station's dock range — at
 *    `dockingRadius * dockApproachFraction` from the station centre, along the station→ship
 *    direction — facing the station, with velocity zeroed. This leaves the ship in the normal
 *    dockable range so the existing DOCK prompt appears (UC25 AC#3); it does **not** auto-dock.
 *  - Otherwise the ship is placed at the exact tapped point, retaining its current heading, with
 *    velocity zeroed.
 *
 * Every branch zeroes linear and angular velocity — "teleport + face", left stationary (UC25 AC#2).
 */
object PointAndGo {
    /** The resolved teleport: the ship's new [kinematics] and the POI it targeted ([targetPoiId]), if any. */
    data class Resolution(val kinematics: ShipKinematics, val targetPoiId: PoiId?)

    /**
     * Resolve where a tap at [tappedWorldPoint] should place the ship, given the sector's [pois] and
     * the ship's [current] kinematics. [dockApproachFraction] is how far along the station radius to
     * place the ship when snapping to a station (0..1; 0.6 leaves comfortable dock clearance). See the
     * class doc for the full rule.
     */
    fun resolve(
        tappedWorldPoint: Vec2,
        pois: List<Poi>,
        current: ShipKinematics,
        dockApproachFraction: Float = 0.6f,
    ): Resolution {
        val station = nearestDockableStation(tappedWorldPoint, pois)
        if (station == null) {
            return Resolution(
                current.copy(position = tappedWorldPoint, velocity = Vec2.ZERO, angularVelocity = 0f),
                targetPoiId = null,
            )
        }

        val stationToShip = (current.position - station.position).normalizedOrZero()
        val approachDir = if (stationToShip == Vec2.ZERO) FIXED_AXIS_FALLBACK else stationToShip
        val position = station.position + approachDir * (station.dockingRadius * dockApproachFraction)
        val toStation = -approachDir
        val heading = atan2(toStation.y, toStation.x)
        return Resolution(
            ShipKinematics(position = position, velocity = Vec2.ZERO, headingRadians = heading, angularVelocity = 0f),
            targetPoiId = station.id,
        )
    }

    /** The nearest station whose dock circle contains [tappedWorldPoint], or null if none do. */
    private fun nearestDockableStation(
        tappedWorldPoint: Vec2,
        pois: List<Poi>,
    ): Station? =
        pois.filterIsInstance<Station>()
            .filter { (tappedWorldPoint - it.position).length <= it.dockingRadius }
            .minByOrNull { (tappedWorldPoint - it.position).length }

    /** Approach direction when the ship sits exactly on the station centre (degenerate direction). */
    private val FIXED_AXIS_FALLBACK = Vec2(1f, 0f)
}
