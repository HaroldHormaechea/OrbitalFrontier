package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [HazardEffect] resolver (UC54 AC#2) — the per-tick fuel drain a [HazardZone]
 * applies while the ship is inside its radius.
 *
 * Two worlds are exercised:
 *  - the production [MvpSectorMap] (real authored geometry — the Beta `beta-hazard` field), so the test
 *    tracks the real map; and
 *  - a small hand-built [SectorWorld] with two overlapping hazards, to assert the drain **sums** across
 *    every overlapping zone and the clamp-at-empty / no-brick invariants with exact, controlled numbers.
 *
 * The headline AC#2 invariant is **no-brick**: a hazard drains fuel but can never strand the ship — the
 * [Fuel.speedFactor] floor ([FuelParams.floorSpeedFraction]) is strictly positive, so even at a dead-empty
 * tank the ship keeps a positive fraction of its top speed and can always limp clear.
 */
class HazardEffectTest {
    private val world: SectorWorld = MvpSectorMap.build()
    private val beta: SectorId = SectorId("beta")

    /** The authored Beta hazard zone — real geometry, read from the production map. */
    private val betaHazard: HazardZone = world.sector(beta).hazardZones.single()

    private val fullTank = Fuel(level = 100f, capacity = 100f)

    // --- AC#2: per-tick drain inside the radius; no drain outside ---

    @Test
    fun `a ship inside a hazard radius drains fuel each tick`() {
        // At the hazard centre (inside the radius), one second of dwell drains exactly fuelDrainPerSecond.
        val after = HazardEffect.resolve(world, beta, betaHazard.position, fullTank, dt = 1f)

        assertEquals(
            "one second inside the hazard drains exactly fuelDrainPerSecond units",
            fullTank.level - betaHazard.fuelDrainPerSecond,
            after.level,
            1e-4f,
        )
    }

    @Test
    fun `the drain scales with the tick's dt`() {
        val dt = 1f / 60f
        val after = HazardEffect.resolve(world, beta, betaHazard.position, fullTank, dt)

        assertEquals(
            "the drain is fuelDrainPerSecond * dt",
            fullTank.level - betaHazard.fuelDrainPerSecond * dt,
            after.level,
            1e-4f,
        )
    }

    @Test
    fun `a ship outside every hazard does not drain and gets the same Fuel instance back`() {
        // Far from the Beta hazard (and every other), so nothing drains: a no-hazard tick is byte-identical.
        val faraway = Vec2(100_000f, 100_000f)
        val after = HazardEffect.resolve(world, beta, faraway, fullTank, dt = 1f)

        assertSame("an out-of-hazard tick must return the SAME Fuel instance", fullTank, after)
    }

    @Test
    fun `a hazard just outside the radius does not drain`() {
        // Place the ship just beyond the hazard edge along +x: distance = radius + 1, so it is outside.
        val justOutside = betaHazard.position + Vec2(betaHazard.radius + 1f, 0f)
        val after = HazardEffect.resolve(world, beta, justOutside, fullTank, dt = 1f)

        assertSame("a ship just outside the radius does not drain", fullTank, after)
    }

    // --- AC#2: drain clamped >= 0 (never negative) + the no-brick invariant ---

    @Test
    fun `draining past an empty tank clamps at zero, never negative`() {
        val nearlyEmpty = Fuel(level = 1f, capacity = 100f)
        // A huge dt drains far more than is left; the tank clamps at empty rather than going negative.
        val after = HazardEffect.resolve(world, beta, betaHazard.position, nearlyEmpty, dt = 1000f)

        assertEquals("the tank clamps at empty", 0f, after.level, 0f)
    }

    @Test
    fun `the speed floor is strictly positive, so a hazard never bricks the ship (no-brick proof)`() {
        // The headline AC#2 invariant: floorSpeedFraction > 0 means even a dead-empty tank keeps a positive
        // fraction of top speed — the ship can always limp out of a hazard.
        val params = FuelParams()
        assertTrue("floorSpeedFraction must be strictly positive (no-brick)", params.floorSpeedFraction > 0f)

        // Drain to dead empty inside the hazard, then prove the emptied tank still moves.
        val empty = HazardEffect.resolve(world, beta, betaHazard.position, Fuel(level = 1f, capacity = 100f), dt = 1000f)
        assertEquals("precondition: drained to empty", 0f, empty.level, 0f)
        assertTrue(
            "an empty tank still keeps a positive speed factor (the ship can limp clear)",
            empty.speedFactor(params) > 0f,
        )
        assertEquals(
            "the empty-tank speed factor is exactly the floor",
            params.floorSpeedFraction,
            empty.speedFactor(params),
            1e-6f,
        )
    }

    // --- AC#2: the drain SUMS across every overlapping hazard (hand-built world for exact control) ---

    @Test
    fun `the drain sums across overlapping hazards`() {
        // Two hazards both centred at the origin (so a ship at the origin is inside BOTH), drains 1.0 + 2.0.
        val sectorId = SectorId("test")
        val twoHazardWorld =
            SectorWorld(
                listOf(
                    Sector(
                        id = sectorId,
                        displayName = "Test",
                        contentExtent = 1000f,
                        pois =
                            listOf(
                                HazardZone(PoiId("haz-a"), Vec2(0f, 0f), radius = 300f, fuelDrainPerSecond = 1f),
                                HazardZone(PoiId("haz-b"), Vec2(0f, 0f), radius = 300f, fuelDrainPerSecond = 2f),
                            ),
                    ),
                ),
            )

        val after = HazardEffect.resolve(twoHazardWorld, sectorId, Vec2(0f, 0f), fullTank, dt = 1f)

        assertEquals(
            "overlapping hazards each contribute their drain (1.0 + 2.0)",
            fullTank.level - 3f,
            after.level,
            1e-4f,
        )
    }

    @Test
    fun `resolve is deterministic — identical inputs yield equal results`() {
        val a = HazardEffect.resolve(world, beta, betaHazard.position, fullTank, dt = 1f)
        val b = HazardEffect.resolve(world, beta, betaHazard.position, fullTank, dt = 1f)
        assertEquals(a, b)
    }
}
