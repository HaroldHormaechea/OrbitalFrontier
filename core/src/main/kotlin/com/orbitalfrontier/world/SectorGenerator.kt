package com.orbitalfrontier.world

import com.orbitalfrontier.common.DeterministicRng
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.economy.TradeOffer

/**
 * Builds a [SectorWorld] from a [WorldSeed] (UC53; docs/design/world-and-sector.md "Layout —
 * procedural, with hand-authored test maps"; ADR 0041).
 *
 * **Additive — the authored map stays the canonical default.** [generate] returns
 * [MvpSectorMap.build] **verbatim** for [WorldSeed.MVP] (the reserved zero seed) and only procedurally
 * generates for any non-zero seed. Because every existing save / fixture / new game resolves to
 * [WorldSeed.MVP] (the DB column DEFAULTs to 0, [WorldState.worldSeed] defaults to MVP), the MVP branch
 * is what keeps all existing replay fixtures byte-identical — the zero-fixture-regen constraint holds
 * *by construction* (ADR 0041). A structural guard test pins `generate(MVP) ≡ MvpSectorMap.build()`.
 *
 * **Determinism.** The procedural branch derives every choice from the seed via [DeterministicRng]
 * ONLY (fnv1a → LCG, pure 64-bit integer arithmetic) — no wall clock, no `Math.random`, no
 * enum/identity `hashCode`. Same seed ⇒ identical world on any JVM, which is what lets a replay capture
 * be re-pinned to a fixed seed (UC53 AC#4). The one non-integer step, [Math.toRadians] for gate/POI
 * angles, mirrors [MvpSectorMap]'s authored use of it.
 *
 * **Connectivity by construction (AC#2).** The generated sectors are wired into a **ring** of
 * reciprocal jump gates (`sector_i ↔ sector_{i+1 mod N}`), plus an optional seed-decided **chord** for
 * N ≥ 4. A ring is connected for any N ≥ 2, so every sector is reachable from any other — this is a
 * **generator invariant**, NOT something [SectorWorld] enforces (it only re-validates reciprocity +
 * global id uniqueness). For N = 3 the ring is the complete triangle, mirroring the authored topology.
 *
 * **Scope boundary (ADR 0041).** A non-MVP world gets templated **markets** only; it has NO
 * encounter / bounty content. The natural-encounter zones, bounty zones, bounty contracts and the
 * curated per-station markets are consumed via the static `MvpSectorMap.*` tables keyed by the literal
 * `"alpha"`/`"beta"`/`"gamma"` ids, so they never apply to a generated sector — a deliberate,
 * documented follow-up, out of scope for this UC.
 */
object SectorGenerator {
    /**
     * The validated [SectorWorld] for [seed]: the hand-authored [MvpSectorMap] for [WorldSeed.MVP], or
     * a procedurally generated, connected world for any non-zero seed. Pure and cheap; each call
     * re-validates the graph via [SectorWorld].
     */
    fun generate(seed: WorldSeed): SectorWorld =
        if (seed == WorldSeed.MVP) {
            MvpSectorMap.build()
        } else {
            generateProcedural(seed)
        }

    // -- procedural generation -------------------------------------------------------------------

    /** Minimum generated sectors — matches the authored 3-sector count; a ring of 3 is the triangle. */
    private const val MIN_SECTORS: Int = 3

    /** Max extra sectors above [MIN_SECTORS] a seed may add (so N ∈ [3, 5]) — for variety. [TUNE] */
    private const val MAX_EXTRA_SECTORS: Int = 2

    /** Soft content radius (world-units) of each generated sector; reuses the authored MVP extent. [TUNE] */
    private const val CONTENT_EXTENT: Float = MvpSectorMap.CONTENT_EXTENT_WORLD_UNITS

    /** Distance (world-units) of each gate from its sector centre — out toward the content edge. [TUNE] */
    private const val GATE_ORBIT_RADIUS: Float = 1300f

    /** Per-gate trigger radius (world-units). [TUNE] */
    private const val GATE_TRIGGER_RADIUS: Float = 80f

    /** Inner / outer radii (world-units) the templated content (station, belt, contact) is placed within. [TUNE] */
    private const val CONTENT_MIN_RADIUS: Float = 350f
    private const val CONTENT_MAX_RADIUS: Float = 1400f

    /** Asteroid-field deposit bounds (units) per resource — both ends positive so the field is never empty. [TUNE] */
    private const val DEPOSIT_MIN: Int = 10
    private const val DEPOSIT_SPAN: Int = 30

    private fun generateProcedural(seed: WorldSeed): SectorWorld {
        val rng = Rng(seed.value)
        val sectorCount = MIN_SECTORS + rng.nextInt(MAX_EXTRA_SECTORS + 1)

        val adjacency = ringAdjacency(sectorCount, rng)
        val sectors =
            (0 until sectorCount).map { i ->
                buildSector(i, adjacency[i].sorted(), rng)
            }
        // SectorWorld re-validates reciprocity + graph-global POI-id uniqueness and fails fast if the
        // generator ever produced a malformed graph — the connectivity invariant is the generator's.
        return SectorWorld(sectors)
    }

    /**
     * Undirected adjacency as a per-sector neighbour set: a **ring** (`i ↔ (i+1) mod N`) — connected
     * for any N ≥ 2 (for N = 3 the ring is already the complete triangle) — plus, for N ≥ 4, an
     * optional seed-decided **chord** from sector 0 to its opposite, adding a redundant cross-link.
     */
    private fun ringAdjacency(
        sectorCount: Int,
        rng: Rng,
    ): List<MutableSet<Int>> {
        val adjacency = List(sectorCount) { mutableSetOf<Int>() }
        for (i in 0 until sectorCount) {
            val next = (i + 1) % sectorCount
            adjacency[i].add(next)
            adjacency[next].add(i)
        }
        // Draw the chord coin unconditionally so the RNG stream is stable regardless of N (a chord is
        // only actually wired for N >= 4, where sector 0 and its opposite are not already ring-adjacent).
        val wantChord = rng.nextInt(2) == 1
        if (wantChord && sectorCount >= 4) {
            val opposite = sectorCount / 2
            adjacency[0].add(opposite)
            adjacency[opposite].add(0)
        }
        return adjacency
    }

    private fun buildSector(
        index: Int,
        neighbours: List<Int>,
        rng: Rng,
    ): Sector {
        val pois = mutableListOf<Poi>()

        // Gates: one per neighbour, spread evenly around the centre with a seeded angular jitter, each
        // reciprocally named so SectorWorld's reciprocity check passes (sector i ↔ sector j gates).
        val gateSlice = 360f / neighbours.size
        neighbours.forEachIndexed { gateIndex, dest ->
            val jitter = (rng.nextFloat() - 0.5f) * (gateSlice * 0.5f)
            val angleDeg = gateIndex * gateSlice + jitter
            pois.add(
                JumpGate(
                    id = PoiId("sector$index-to-$dest"),
                    position = polar(angleDeg, GATE_ORBIT_RADIUS),
                    triggerRadius = GATE_TRIGGER_RADIUS,
                    link =
                        GateLink(
                            destinationSector = SectorId("sector$dest"),
                            destinationGate = PoiId("sector$dest-to-$index"),
                        ),
                ),
            )
        }

        // One templated station near the centre (markets only — no encounter/bounty content; ADR 0041).
        pois.add(
            Station(
                id = PoiId("sector$index-station"),
                position = contentPoint(rng),
                displayName = "Sector $index Station",
                market = TEMPLATE_MARKET,
            ),
        )

        // One asteroid field with seed-derived (always positive) deposits.
        pois.add(
            AsteroidField(
                id = PoiId("sector$index-belt"),
                position = contentPoint(rng),
                deposits =
                    mapOf(
                        ResourceType.IRON_ORE to DEPOSIT_MIN + rng.nextInt(DEPOSIT_SPAN + 1),
                        ResourceType.WATER_ICE to DEPOSIT_MIN + rng.nextInt(DEPOSIT_SPAN + 1),
                    ),
            ),
        )

        // 0-1 hidden contacts, seed-decided. Coin drawn unconditionally to keep the stream stable.
        val wantContact = rng.nextInt(2) == 1
        val contactPoint = contentPoint(rng)
        if (wantContact) {
            pois.add(
                HiddenContact(
                    id = PoiId("sector$index-contact"),
                    position = contactPoint,
                ),
            )
        }

        return Sector(
            id = SectorId("sector$index"),
            displayName = "Sector $index",
            contentExtent = CONTENT_EXTENT,
            pois = pois,
        )
    }

    /** A seeded point within the content annulus (angle + radius), for placing a station/belt/contact. */
    private fun contentPoint(rng: Rng): Vec2 {
        val angleDeg = rng.nextFloat() * 360f
        val radius = CONTENT_MIN_RADIUS + rng.nextFloat() * (CONTENT_MAX_RADIUS - CONTENT_MIN_RADIUS)
        return polar(angleDeg, radius)
    }

    private fun polar(
        angleDegrees: Float,
        magnitude: Float,
    ): Vec2 = Vec2.fromAngle(Math.toRadians(angleDegrees.toDouble()).toFloat(), magnitude)

    /**
     * The templated trade desk every generated station carries (ADR 0041 scope boundary: markets only).
     * Fixed authored prices keeping `0 <= sellPrice <= buyPrice` so the [TradeOffer] invariants hold.
     */
    private val TEMPLATE_MARKET: StationMarket =
        StationMarket(
            mapOf(
                ResourceType.HYDROGEN to TradeOffer(buyPrice = 6, sellPrice = 4),
                ResourceType.IRON_ORE to TradeOffer(buyPrice = 12, sellPrice = 9),
                ResourceType.SILICON to TradeOffer(buyPrice = 16, sellPrice = 12),
            ),
        )

    /**
     * A minimal stateful wrapper over the [DeterministicRng] primitives — the same fnv1a-seed → LCG
     * pattern mission instancing uses, kept local so generation draws are self-contained. Pure 64-bit
     * integer state; identical seed ⇒ identical stream.
     */
    private class Rng(seedValue: Long) {
        private var state: Long = DeterministicRng.fnv1a("worldseed:$seedValue")

        /** A uniform int in `[0, bound)`. */
        fun nextInt(bound: Int): Int {
            state = DeterministicRng.lcgAdvance(state)
            return DeterministicRng.boundedInt(state, bound)
        }

        /** A uniform float in `[0, 1)`. */
        fun nextFloat(): Float {
            state = DeterministicRng.lcgAdvance(state)
            return DeterministicRng.floatFromState(state)
        }
    }
}
