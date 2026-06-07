package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * The hand-authored 3-sector MVP map (UC03 AC#1; docs/design/world-and-sector.md, ADR 0004).
 *
 * This is the **single source of truth** for the MVP world, consumed both by the live game
 * ([com.orbitalfrontier.screen.PlayScreen]) and — via [START_SECTOR] as the default current sector —
 * by the deterministic test harness, so live and replay agree on the same topology.
 *
 * Topology: three sectors (Alpha, Beta, Gamma) wired into a **triangle** — every sector has two
 * gates, one to each of the others, with **reciprocal** links (validated by [SectorWorld]). Content
 * clusters at each sector's centre (origin); gates sit out toward the edge of the content area so the
 * player flies *out from* the cluster to jump.
 *
 * Sizing: [CONTENT_EXTENT_WORLD_UNITS] is a soft content **radius** of 1800 wu, i.e. ~3600 wu across
 * — about **30 s to cross at the ship's max speed** (120 wu/s, `ShipMovementParams.maxSpeed`). These
 * are authored tunables, not derived constants (see docs/design/world-and-sector.md).
 *
 * When seed-based procedural generation arrives (design note "Open questions"), it replaces this
 * authored data behind the same [SectorWorld] type without touching consumers.
 */
object MvpSectorMap {
    /** The canonical sector a new game / a fresh simulation starts in. */
    val START_SECTOR: SectorId = SectorId("alpha")

    /** Soft content radius (world-units) of each MVP sector; ~30 s to cross at max speed. [TUNE] */
    const val CONTENT_EXTENT_WORLD_UNITS: Float = 1800f

    /** Distance (world-units) of each gate from its sector centre — out toward the content edge. [TUNE] */
    private const val GATE_ORBIT_RADIUS: Float = 1300f

    /** Per-gate trigger radius (world-units); authored here, not hard-coded in traversal. [TUNE] */
    private const val GATE_TRIGGER_RADIUS: Float = 80f

    /** Docking-range radius (world-units) of each authored station. [TUNE] */
    private const val STATION_DOCKING_RADIUS: Float = 100f

    private val ALPHA = SectorId("alpha")
    private val BETA = SectorId("beta")
    private val GAMMA = SectorId("gamma")

    /**
     * Build a fresh, validated [SectorWorld] for the MVP map. Cheap to call; each call re-validates
     * the (small) authored graph and fails fast if it were ever edited into an inconsistent state.
     */
    fun build(): SectorWorld =
        SectorWorld(
            listOf(
                Sector(
                    id = ALPHA,
                    displayName = "Alpha Reach",
                    contentExtent = CONTENT_EXTENT_WORLD_UNITS,
                    pois =
                        listOf(
                            gate("alpha-to-beta", angleDegrees = 0f, dest = BETA, destGate = "beta-to-alpha"),
                            gate("alpha-to-gamma", angleDegrees = 120f, dest = GAMMA, destGate = "gamma-to-alpha"),
                            station("alpha-station", "Alpha Station", Vec2(0f, 600f)),
                        ),
                ),
                Sector(
                    id = BETA,
                    displayName = "Beta Expanse",
                    contentExtent = CONTENT_EXTENT_WORLD_UNITS,
                    pois =
                        listOf(
                            gate("beta-to-alpha", angleDegrees = 180f, dest = ALPHA, destGate = "alpha-to-beta"),
                            gate("beta-to-gamma", angleDegrees = 60f, dest = GAMMA, destGate = "gamma-to-beta"),
                            station("beta-station", "Beta Station", Vec2(300f, -300f)),
                        ),
                ),
                Sector(
                    id = GAMMA,
                    displayName = "Gamma Verge",
                    contentExtent = CONTENT_EXTENT_WORLD_UNITS,
                    pois =
                        listOf(
                            gate("gamma-to-beta", angleDegrees = 240f, dest = BETA, destGate = "beta-to-gamma"),
                            gate("gamma-to-alpha", angleDegrees = 300f, dest = ALPHA, destGate = "alpha-to-gamma"),
                        ),
                ),
            ),
        )

    private fun gate(
        id: String,
        angleDegrees: Float,
        dest: SectorId,
        destGate: String,
    ): JumpGate =
        JumpGate(
            id = PoiId(id),
            position = Vec2.fromAngle(Math.toRadians(angleDegrees.toDouble()).toFloat(), GATE_ORBIT_RADIUS),
            triggerRadius = GATE_TRIGGER_RADIUS,
            link = GateLink(destinationSector = dest, destinationGate = PoiId(destGate)),
        )

    private fun station(
        id: String,
        displayName: String,
        position: Vec2,
    ): Station =
        Station(
            id = PoiId(id),
            position = position,
            displayName = displayName,
            dockingRadius = STATION_DOCKING_RADIUS,
        )
}
