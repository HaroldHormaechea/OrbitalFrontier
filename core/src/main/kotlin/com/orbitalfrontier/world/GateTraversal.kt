package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * The outcome of flying into a gate: which sector the ship arrives in, and where (ADR 0004).
 *
 * Pure value type — the unit a UC02 replay test asserts against (AC#9): a recorded playthrough that
 * crosses a gate can assert the resulting [destinationSector] and [arrivalPosition].
 */
data class Traversal(
    val destinationSector: SectorId,
    val arrivalPosition: Vec2,
)

/**
 * Pure, deterministic gate-traversal resolution (UC03 AC#3/#8/#10).
 *
 * [resolve] is a side-effect-free function of (world, current sector, ship position): identical
 * inputs always yield an identical result, so it slots into the deterministic simulation stepper and
 * is fully JVM-unit-testable (no I/O, no engine types). It does **not** mutate anything — the caller
 * (the play screen on device, the replay harness in tests) applies the jump.
 *
 * Anti-bounce-back (UC03 pitfall): the ship arrives **offset from** the destination gate, pushed
 * toward the destination sector's centre (the origin, where content clusters) by
 * `destGate.triggerRadius + margin`. Since that distance strictly exceeds the destination gate's
 * trigger radius, the ship spawns *outside* that gate's trigger circle and cannot immediately
 * re-trigger a jump back.
 */
object GateTraversal {
    /**
     * Default extra buffer (world-units) added beyond the destination gate's trigger radius when
     * placing the arriving ship, guaranteeing it lands outside the trigger circle. Chosen as a small
     * fraction of the MVP content extent — comfortably clear of the trigger ring without flinging the
     * ship far from the gate. [TUNE]
     */
    const val DEFAULT_ARRIVAL_MARGIN: Float = 40f

    /**
     * The sector centre, in a sector's own coordinate space. Content clusters at the origin
     * (docs/design/world-and-sector.md), so "toward the centre" means toward [Vec2.ZERO].
     */
    private val SECTOR_CENTER = Vec2.ZERO

    /**
     * Fallback arrival direction used in the degenerate case where the destination gate sits exactly
     * at the sector centre (so "toward centre" has no direction). Deterministic so replays stay
     * reproducible.
     */
    private val FALLBACK_ARRIVAL_DIRECTION = Vec2(1f, 0f)

    /**
     * Resolve a jump for a ship at [shipPosition] in [currentSector], or null if the ship is not
     * inside any gate's trigger circle.
     *
     * When several gates overlap the ship, the **first** gate (in the sector's authored POI order)
     * whose trigger circle contains the ship wins — deterministic by construction.
     *
     * @param margin extra buffer beyond the destination gate's trigger radius for the arrival offset
     *   (see [DEFAULT_ARRIVAL_MARGIN]).
     */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        margin: Float = DEFAULT_ARRIVAL_MARGIN,
    ): Traversal? {
        val sector = world.sector(currentSector)
        val gate =
            sector.gates.firstOrNull { (shipPosition - it.position).length <= it.triggerRadius }
                ?: return null

        // The destination gate exists and the link is reciprocal — guaranteed by SectorWorld validation.
        val destinationSector = world.sector(gate.link.destinationSector)
        val destinationGate =
            destinationSector.gate(gate.link.destinationGate)
                ?: throw IllegalStateException(
                    "validated gate ${gate.link.destinationGate} missing in ${destinationSector.id}",
                )

        val towardCenter = (SECTOR_CENTER - destinationGate.position).normalizedOrZero()
        val direction = if (towardCenter == Vec2.ZERO) FALLBACK_ARRIVAL_DIRECTION else towardCenter
        val arrivalPosition = destinationGate.position + direction * (destinationGate.triggerRadius + margin)

        return Traversal(
            destinationSector = gate.link.destinationSector,
            arrivalPosition = arrivalPosition,
        )
    }
}
