package com.orbitalfrontier.combat

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.MvpSectorMap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the destruction **consequence summary** (UC33) — the
 * deterministic AC#2/#5 driver. A destruction is driven end-to-end through the pure [Respawn] and the
 * resulting [Respawn.RespawnResult] folded into a [DestructionSummary] via [DestructionSummary.from],
 * exactly as [com.orbitalfrontier.screen.PlayScreen.respawnPlayer] does on the device — so the value the
 * destruction screen reports (cargo lost, credit/insurance penalty, respawn location) is asserted
 * without any GL types (AC#5: "a test drives a destruction event and asserts the consequence summary").
 *
 * The GL-bound glue that resolves the respawn location and shows the overlay is pinned separately by the
 * source-anchored [com.orbitalfrontier.screen.Uc33DestructionScreenGuardTest]; here the focus is the pure
 * data contract: the cargo loss comes straight from the respawn result, the credit penalty is the
 * constant insurance-covered `0`, and the location name is carried verbatim.
 */
class DestructionSummaryTest {
    private val params = CombatParams() // respawnCargoLossFraction 0.25

    @Test
    fun `the summary reports the cargo units the respawn actually jettisoned`() {
        // 8 units * 0.25 -> floor 2 lost (the AC#2 cargo-loss figure the screen shows).
        val cargo = Cargo(mapOf(ResourceType.HYDROGEN to 8), Cargo.DEFAULT_CAPACITY)
        val result = Respawn.respawn(Vec2(0f, 600f), cargo, params)

        val summary = DestructionSummary.from(result, "Alpha Station")

        assertEquals("AC#2: the summary's cargo loss is exactly the respawn's unitsLost", result.unitsLost, summary.cargoUnitsLost)
        assertEquals("the floor-25% loss is surfaced (8 -> 2)", 2, summary.cargoUnitsLost)
    }

    @Test
    fun `the credit penalty is zero - insurance covered, no permadeath`() {
        val cargo = Cargo(mapOf(ResourceType.IRON_ORE to 20), Cargo.DEFAULT_CAPACITY)
        val result = Respawn.respawn(Vec2.ZERO, cargo, params)

        val summary = DestructionSummary.from(result, "Beta Station")

        assertEquals(
            "AC#2: the MVP destruction charges no credits (insurance covered, ADR 0022)",
            DestructionSummary.INSURANCE_COVERED_PENALTY,
            summary.creditPenalty,
        )
        assertEquals("the insurance-covered penalty is the constant 0", 0L, summary.creditPenalty)
    }

    @Test
    fun `the respawn location name is carried verbatim`() {
        val result = Respawn.respawn(Vec2(10f, 20f), Cargo.empty(), params)

        val summary = DestructionSummary.from(result, "Gamma Junkyard")

        assertEquals("AC#2: the summary reports where the player respawns", "Gamma Junkyard", summary.respawnLocationName)
    }

    @Test
    fun `the no-prior-dock fallback carries the game-start location label`() {
        // Edge case (UC33 pitfall): destroyed before the first dock -> the respawn point is the game-start
        // sector, whose authored displayName is what the screen reports. Pinned here so the fallback label
        // is a real authored value, not an invented string.
        val startLabel = MvpSectorMap.build().sector(MvpSectorMap.START_SECTOR).displayName
        val result = Respawn.respawn(Vec2.ZERO, Cargo.empty(), params)

        val summary = DestructionSummary.from(result, startLabel)

        assertEquals("the fallback respawn label is the game-start sector's display name", startLabel, summary.respawnLocationName)
        assertEquals("sanity: the authored game-start label is Alpha Reach", "Alpha Reach", summary.respawnLocationName)
    }

    @Test
    fun `an empty hold yields a zero-loss, zero-penalty summary`() {
        val result = Respawn.respawn(Vec2.ZERO, Cargo.empty(), params)

        val summary = DestructionSummary.from(result, "Alpha Station")

        assertEquals("an empty hold loses no cargo", 0, summary.cargoUnitsLost)
        assertEquals("still no credit penalty", 0L, summary.creditPenalty)
    }
}
