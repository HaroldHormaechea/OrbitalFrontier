package com.orbitalfrontier.world

import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.CombatState
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [DistressEvent] resolver (UC54 AC#2/#4) — the edge-triggered distress-signal
 * mini-event that branches into a **reward** XOR an **ambush**.
 *
 * The branch is decided deterministically by a fresh RNG namespace keyed `"distress:$id"`, so a given signal
 * id always resolves to the same branch. To cover **both** branches the test discovers (via the real
 * resolver — no RNG replication) one authored id that resolves to AMBUSH and one that resolves to REWARD,
 * then asserts the concrete outcome of each. It also pins the edge-trigger, combat-suppression, consumed,
 * and no-op contracts.
 */
class DistressEventTest {
    private val sectorId = SectorId("test")
    private val combatParams = CombatParams()
    private val params = DistressParams()

    /** A one-signal world with the distress signal at the origin (trigger radius default). */
    private fun worldWith(id: String): SectorWorld =
        SectorWorld(
            listOf(
                Sector(
                    id = sectorId,
                    displayName = "Test",
                    contentExtent = 5000f,
                    pois = listOf(DistressSignal(PoiId(id), Vec2(0f, 0f))),
                ),
            ),
        )

    private val triggerRadius = DistressSignal.DEFAULT_TRIGGER_RADIUS
    private val outside = Vec2(triggerRadius * 4f, 0f)
    private val inside = Vec2(0f, 0f)

    /** Resolve an outside→inside crossing for the signal id in a one-signal world. */
    private fun cross(
        id: String,
        consumedPois: Set<PoiId> = emptySet(),
        combat: CombatState = CombatState.NONE,
        cargo: Cargo = Cargo.empty(),
        credits: Long = 100L,
        previous: Vec2 = outside,
        next: Vec2 = inside,
    ): DistressResult =
        DistressEvent.resolve(
            world = worldWith(id),
            currentSector = sectorId,
            previousPosition = previous,
            newPosition = next,
            consumedPois = consumedPois,
            combat = combat,
            cargo = cargo,
            credits = credits,
            spawnTick = 7,
            combatParams = combatParams,
            params = params,
        )

    /** Find the first id in a candidate sequence whose crossing resolves to [outcome] (via the real resolver). */
    private fun idResolvingTo(outcome: DistressOutcome): String =
        generateSequence(0) { it + 1 }
            .map { "distress-$it" }
            .first { cross(it).outcome == outcome }

    private val ambushId = idResolvingTo(DistressOutcome.AMBUSH)
    private val rewardId = idResolvingTo(DistressOutcome.REWARD)

    // --- AC#2: the AMBUSH branch spawns hostiles into active combat ---

    @Test
    fun `the ambush branch spawns a hostile into active combat and consumes the signal`() {
        val result = cross(ambushId)

        assertEquals("the ambush branch resolved", DistressOutcome.AMBUSH, result.outcome)
        assertEquals("the signal that triggered is reported", PoiId(ambushId), result.triggered)
        assertTrue("an ambush makes combat active", result.combat.active)
        assertTrue("an ambush spawns at least one hostile", result.combat.hostiles.isNotEmpty())
        assertTrue("the triggered signal is marked consumed", PoiId(ambushId) in result.consumedPois)
        // An ambush grants no reward: credits + cargo are untouched on this branch.
        assertEquals("an ambush does not change credits", 100L, result.credits)
        assertTrue("an ambush does not change cargo", result.cargo.contents.isEmpty())
    }

    // --- AC#2: the REWARD branch grants credits + cargo ---

    @Test
    fun `the reward branch grants credits and cargo and consumes the signal`() {
        val result = cross(rewardId)

        assertEquals("the reward branch resolved", DistressOutcome.REWARD, result.outcome)
        assertEquals("the signal that triggered is reported", PoiId(rewardId), result.triggered)
        assertFalse("a reward does not start combat", result.combat.active)
        assertTrue("the triggered signal is marked consumed", PoiId(rewardId) in result.consumedPois)

        // Credits rose by exactly the params' reward; cargo equals the shared-fill result (derived, no literal).
        assertEquals("credits rose by exactly the reward", 100L + params.rewardCredits, result.credits)
        val expectedFill = Salvage.fillCargo(Cargo.empty(), params.rewardResources)
        assertEquals("the reward cargo equals the shared-fill result", expectedFill.cargo, result.cargo)
    }

    // --- AC#2: edge-triggered only (outside→inside), suppressed during combat, no double-fire ---

    @Test
    fun `no crossing (already inside) is a same-instance no-op`() {
        val cargo = Cargo.empty()
        val consumed = emptySet<PoiId>()
        // previous already inside ⇒ not an outside→inside crossing.
        val result =
            DistressEvent.resolve(
                worldWith(rewardId), sectorId,
                previousPosition = inside, newPosition = inside,
                consumedPois = consumed, combat = CombatState.NONE,
                cargo = cargo, credits = 100L, spawnTick = 7,
                combatParams = combatParams, params = params,
            )

        assertNull("no crossing ⇒ nothing triggers", result.triggered)
        assertSame("the SAME cargo threads through", cargo, result.cargo)
        assertSame("the SAME consumed set threads through", consumed, result.consumedPois)
        assertEquals("credits untouched", 100L, result.credits)
    }

    @Test
    fun `leaving the radius (inside to outside) does not trigger`() {
        val result = cross(rewardId, previous = inside, next = outside)
        assertNull("an inside→outside crossing does not fire", result.triggered)
    }

    @Test
    fun `a distress event is suppressed while combat is already active`() {
        val active = cross(ambushId).combat
        assertTrue("precondition: a live combat", active.active)

        // Now cross a REWARD signal while that fight is active: suppressed, a no-op.
        val cargo = Cargo.empty()
        val consumed = emptySet<PoiId>()
        val result =
            DistressEvent.resolve(
                worldWith(rewardId), sectorId,
                previousPosition = outside, newPosition = inside,
                consumedPois = consumed, combat = active,
                cargo = cargo, credits = 100L, spawnTick = 7,
                combatParams = combatParams, params = params,
            )

        assertNull("a distress event cannot fire mid-combat", result.triggered)
        assertSame("the SAME combat threads through (suppressed)", active, result.combat)
        assertSame("the SAME cargo threads through", cargo, result.cargo)
    }

    @Test
    fun `an already-consumed signal does not re-fire`() {
        val cargo = Cargo.empty()
        val consumed = setOf(PoiId(rewardId))
        val result = cross(rewardId, consumedPois = consumed, cargo = cargo)

        assertNull("a consumed signal never fires again", result.triggered)
        assertSame("the SAME cargo threads through", cargo, result.cargo)
        assertSame("the SAME consumed set threads through", consumed, result.consumedPois)
    }

    // --- determinism ---

    @Test
    fun `the branch decision is seed-deterministic for a given signal id`() {
        assertEquals("same id → same branch", cross(ambushId).outcome, cross(ambushId).outcome)
        assertEquals("same id → same branch", cross(rewardId).outcome, cross(rewardId).outcome)
    }
}
