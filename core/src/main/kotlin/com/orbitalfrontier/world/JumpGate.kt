package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * Directed link from a jump gate to the gate it arrives at in another sector (ADR 0004).
 *
 * The MVP topology is a **fixed graph** of these links. [SectorWorld] validates at construction that
 * every link resolves (the destination sector exists and contains the destination gate) and that
 * links are **reciprocal** (the destination gate links back to the origin gate) — a one-way or
 * dangling link is a malformed map and fails fast.
 */
data class GateLink(
    val destinationSector: SectorId,
    val destinationGate: PoiId,
)

/**
 * A fixed jump gate — the only inter-sector travel mechanism in the MVP (ADR 0004).
 *
 * Flying the ship inside [triggerRadius] of [position] transports it to the linked gate in the
 * destination sector (see [GateTraversal]). [triggerRadius] is authored **per gate** in the map data
 * (not a global constant) so individual gates can be tuned independently.
 *
 * Pure data — no engine types — so it is part of the JVM-testable world model (ADR 0001).
 */
data class JumpGate(
    override val id: PoiId,
    override val position: Vec2,
    /** Radius (world-units) of the trigger circle around [position] that initiates a jump. */
    val triggerRadius: Float,
    val link: GateLink,
) : Poi {
    init {
        require(triggerRadius > 0f) { "JumpGate $id triggerRadius must be positive: $triggerRadius" }
    }
}
