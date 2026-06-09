package com.orbitalfrontier.debugnav

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.world.GateLink
import com.orbitalfrontier.world.JumpGate
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the UC25 debug point-and-go destination resolver,
 * [PointAndGo.resolve]. This is the deterministic half of AC#2/#3 — "where does a tap put the ship?"
 * math, separated from the GL/input glue so it is unit-testable headlessly (UC25 risk). The on-device
 * tap → unproject → teleport is GL-bound and verified by a live emulator pass; the gating/isolation
 * wiring is pinned by [com.orbitalfrontier.screen.Uc25PointAndGoGuardTest].
 *
 * Contract under test:
 *  - Tap inside a station's dock circle → the **nearest** such station wins; the ship is placed in
 *    normal dock range (≈ `dockingRadius * 0.6` from centre, not overlapping), facing the station,
 *    velocity zeroed, `targetPoiId == station.id` (AC#3). This leaves the existing DOCK prompt to fire.
 *  - Tap in empty space → the exact tapped point, heading retained, velocity zeroed, no target (AC#2).
 *  - Every branch zeroes linear AND angular velocity — "teleport + face", left stationary (AC#2).
 */
class PointAndGoTest {
    @Test
    fun `tap on a station within dock range snaps into dock range, faces it, and targets it`() {
        val station = station("alpha", at = Vec2(100f, 50f), radius = 80f)
        val ship = ShipKinematics(position = Vec2(400f, 50f), velocity = Vec2(12f, -7f), headingRadians = 1.2f, angularVelocity = 0.4f)

        val resolution = PointAndGo.resolve(tappedWorldPoint = Vec2(110f, 55f), pois = listOf(station), current = ship)

        // AC#3: targets the station so the caller can drive the normal DOCK prompt — no auto-dock here.
        assertEquals("AC#3: the resolved target is the tapped station", station.id, resolution.targetPoiId)

        // AC#3: placed inside dock range (within dockingRadius) but NOT at the centre (not overlapping).
        val distanceFromCentre = (resolution.kinematics.position - station.position).length
        assertTrue("AC#3: the ship lands within the station's dock range", distanceFromCentre < station.dockingRadius)
        assertTrue("AC#3: the ship is not placed at the station centre (no overlap)", distanceFromCentre > 0f)
        assertEquals(
            "AC#3: snapped to ~0.6x the docking radius from the centre",
            station.dockingRadius * 0.6f,
            distanceFromCentre,
            1e-2f,
        )

        // AC#2: left sensibly oriented — the heading's unit vector points from the resolved position
        // toward the station. Compared as a direction (not a raw angle) so the equivalent +pi/-pi wrap
        // never trips the assertion.
        val toStation = (station.position - resolution.kinematics.position).normalizedOrZero()
        val facing = Vec2.fromAngle(resolution.kinematics.headingRadians)
        assertEquals("AC#2: the ship faces the station (x component)", toStation.x, facing.x, 1e-4f)
        assertEquals("AC#2: the ship faces the station (y component)", toStation.y, facing.y, 1e-4f)

        // AC#2: left stationary — both linear and angular velocity zeroed.
        assertEquals("AC#2: linear velocity zeroed", Vec2.ZERO, resolution.kinematics.velocity)
        assertEquals("AC#2: angular velocity zeroed", 0f, resolution.kinematics.angularVelocity, 0f)
    }

    @Test
    fun `tap in empty space teleports to the exact point, retains heading, and targets nothing`() {
        val station = station("alpha", at = Vec2(0f, 0f), radius = 50f)
        val tap = Vec2(900f, -640f)
        val ship = ShipKinematics(position = Vec2(10f, 10f), velocity = Vec2(5f, 5f), headingRadians = 0.9f, angularVelocity = -0.3f)

        val resolution = PointAndGo.resolve(tappedWorldPoint = tap, pois = listOf(station), current = ship)

        // AC#2: free space → the exact tapped world point, no POI target.
        assertEquals("AC#2: teleports to the exact tapped point", tap, resolution.kinematics.position)
        assertNull("AC#2: empty-space tap targets no POI", resolution.targetPoiId)
        // AC#2: heading is retained for a free-space teleport.
        assertEquals("AC#2: heading is retained in free space", ship.headingRadians, resolution.kinematics.headingRadians, 0f)
        // AC#2: still left stationary.
        assertEquals("AC#2: linear velocity zeroed", Vec2.ZERO, resolution.kinematics.velocity)
        assertEquals("AC#2: angular velocity zeroed", 0f, resolution.kinematics.angularVelocity, 0f)
    }

    @Test
    fun `non-station POIs are ignored - a tap on a jump gate is treated as free space`() {
        // Only stations are dockable destinations; a tap on a gate (or any non-Station POI) resolves as
        // a plain free-space teleport with no target.
        val gate =
            JumpGate(
                id = PoiId("gate-1"),
                position = Vec2(200f, 200f),
                triggerRadius = 60f,
                link = GateLink(destinationSector = SectorId("beta"), destinationGate = PoiId("gate-2")),
            )
        val ship = ShipKinematics(position = Vec2(0f, 0f), headingRadians = 0.5f)

        val resolution = PointAndGo.resolve(tappedWorldPoint = Vec2(205f, 205f), pois = listOf(gate), current = ship)

        assertNull("a non-station POI is not a teleport target", resolution.targetPoiId)
        assertEquals("a gate tap teleports to the exact tapped point", Vec2(205f, 205f), resolution.kinematics.position)
    }

    @Test
    fun `when several stations contain the tap, the nearest one is chosen`() {
        val near = station("near", at = Vec2(10f, 0f), radius = 100f)
        val far = station("far", at = Vec2(60f, 0f), radius = 100f)
        val ship = ShipKinematics(position = Vec2(0f, -300f))

        // Tap at the origin sits inside BOTH dock circles; the nearer centre (near) must win.
        val resolution = PointAndGo.resolve(tappedWorldPoint = Vec2(0f, 0f), pois = listOf(far, near), current = ship)

        assertEquals("the nearest dockable station is chosen", near.id, resolution.targetPoiId)
    }

    @Test
    fun `degenerate ship-on-centre uses a fixed-axis fallback - still in range, no NaN`() {
        // The ship sits exactly on the station centre, so station->ship is zero-length (degenerate). The
        // resolver must fall back to a fixed axis rather than producing NaN from a zero-length normalize.
        val station = station("alpha", at = Vec2(0f, 0f), radius = 50f)
        val ship = ShipKinematics(position = Vec2(0f, 0f), velocity = Vec2(3f, 4f), angularVelocity = 1f)

        val resolution = PointAndGo.resolve(tappedWorldPoint = Vec2(5f, 0f), pois = listOf(station), current = ship)

        val pos = resolution.kinematics.position
        assertFalse("no NaN in the resolved x", pos.x.isNaN())
        assertFalse("no NaN in the resolved y", pos.y.isNaN())
        assertFalse("no NaN in the resolved heading", resolution.kinematics.headingRadians.isNaN())

        val distanceFromCentre = (pos - station.position).length
        assertTrue("still placed within dock range", distanceFromCentre < station.dockingRadius)
        assertTrue("still not placed at the centre", distanceFromCentre > 0f)
        assertEquals("still targets the station", station.id, resolution.targetPoiId)
        assertEquals("still left stationary", Vec2.ZERO, resolution.kinematics.velocity)
        assertEquals("still zeroes angular velocity", 0f, resolution.kinematics.angularVelocity, 0f)
    }

    @Test
    fun `dockApproachFraction controls how far inside the dock circle the ship lands`() {
        // The placement fraction is a parameter; a larger fraction sits closer to the dock edge.
        val station = station("alpha", at = Vec2(0f, 0f), radius = 100f)
        val ship = ShipKinematics(position = Vec2(500f, 0f))

        val tight = PointAndGo.resolve(Vec2(5f, 0f), listOf(station), ship, dockApproachFraction = 0.3f)
        val loose = PointAndGo.resolve(Vec2(5f, 0f), listOf(station), ship, dockApproachFraction = 0.9f)

        assertEquals(30f, (tight.kinematics.position - station.position).length, 1e-2f)
        assertEquals(90f, (loose.kinematics.position - station.position).length, 1e-2f)
    }

    private companion object {
        private fun station(
            id: String,
            at: Vec2,
            radius: Float,
        ): Station =
            Station(
                id = PoiId(id),
                position = at,
                displayName = "Station $id",
                dockingRadius = radius,
            )
    }
}
