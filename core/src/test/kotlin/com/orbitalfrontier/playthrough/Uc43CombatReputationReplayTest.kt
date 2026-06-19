package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.faction.ReputationParams
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC43 combat-reputation playthrough (AC#5), following the record→replay→assert
 * pattern (docs/PLAYTESTING.md).
 *
 * The committed `uc43-combat-reputation` artifact starts the player in flight in **Gamma Verge** (the
 * Independents' home turf), flies into the natural `gamma-independent-marauder` zone to spawn the
 * faction-affiliated [HostileArchetypes.INDEPENDENT_MARAUDER], then holds FIRE while loitering so the
 * crew-gated auto-aim turret destroys it. The kill folds [ReputationParams.combatKillDelta] into the
 * player's Independents standing via the lockstep [com.orbitalfrontier.combat.CombatReputation.applyKills]
 * mirror. This test replays it headlessly and asserts the AC#5 contract:
 *  - a **faction** ship (the marauder) was present and the encounter cleared (destroyed — it is AGGRESSIVE
 *    and the player loiters inside its leash, so a cleared encounter means a kill, not a break-off);
 *  - the player's Independents standing **dropped by exactly `combatKillDelta`** (derived from
 *    [ReputationParams], never a magic literal — a future tuning change that regenerates the fixture trips
 *    this assertion loudly), and the other faction (LEAGUE) is untouched (single-faction MVP, AC#2);
 *  - the standing drop **persists across a snapshot round-trip** (serialize → JSON → deserialize, AC#5 /
 *    AC#3 — reputation rides the existing snapshot path, no schema bump);
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * Authored in Gamma deliberately: no other committed fixture roams Gamma, and the natural-spawn check
 * filters by the current sector, so this zone can never perturb an existing replay (the UC43
 * fixture-stability top risk). The artifact is reproduced from [PlaythroughFixtures.uc43CombatReputation]
 * and guarded by [PlaythroughFixtureTest]; it is also loadable via the `playtest` skill
 * (`-Dplaythrough.name=uc43-combat-reputation`).
 */
class Uc43CombatReputationReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC43_COMBAT_REPUTATION)

    private val independents = Factions.INDEPENDENTS.id
    private val league = Factions.LEAGUE.id

    /** The Independents reputation lost per faction kill — derived from the params, never a magic literal. */
    private val combatKillDelta = ReputationParams().combatKillDelta

    /** The same stable, encode-defaults Json the on-disk artifacts use — for the AC#5 round-trip. */
    private val json = Json { encodeDefaults = true }

    @Test
    fun `destroying a faction marauder sours the independents standing by the combat-kill delta`() {
        val result = ReplayRunner().run(load(), capturePerTickStates = true)
        val finalState = result.finalState

        // A real fight occurred against the FACTION-affiliated marauder (not an unaligned raider): some
        // intermediate tick had an active encounter holding the Independent Marauder.
        val foughtMarauder =
            result.perTickStates.any { state ->
                state.combat.active && state.combat.hostiles.any { it.archetypeId == HostileArchetypes.INDEPENDENT_MARAUDER.id }
            }
        assertTrue("the player engaged the faction marauder", foughtMarauder)

        // The marauder is GONE: the encounter cleared (AGGRESSIVE + the player loitered within leash, so a
        // cleared encounter means it was destroyed). A kill is the only way the standing below can move.
        assertFalse("the encounter is cleared at the end", finalState.combat.active)
        assertTrue("no hostiles remain", finalState.combat.hostiles.isEmpty())

        // AC#1/#3: the kill applied combatKillDelta to the Independents standing via the Reputation.with seam.
        assertEquals(
            "Independents standing dropped by exactly combatKillDelta",
            combatKillDelta,
            finalState.reputation.valueFor(independents),
        )
        assertTrue("the change was an actual loss (the marauder is faction-affiliated)", combatKillDelta < 0)
        // AC#2: single-faction MVP — no allied/rival propagation, so the unrelated LEAGUE is untouched.
        assertEquals("the other faction's standing is untouched (single-faction MVP)", 0, finalState.reputation.valueFor(league))
    }

    @Test
    fun `the soured standing persists across a snapshot round-trip`() {
        // AC#5/AC#3: reputation rides the existing replay/save snapshot path (no schema bump). Serialize the
        // post-kill state to the on-disk DTO, JSON round-trip it, and confirm the standing survives intact.
        val finalState = ReplayRunner().run(load()).finalState
        assertNotEquals("precondition: the run actually moved the standing off neutral", 0, finalState.reputation.valueFor(independents))

        val snapshot = StateSnapshotDto.from(finalState)
        val restored = json.decodeFromString<StateSnapshotDto>(json.encodeToString(snapshot)).toSimulationState()

        assertEquals(
            "the Independents standing survives the snapshot round-trip",
            combatKillDelta,
            restored.reputation.valueFor(independents),
        )
        assertEquals("the full reputation map round-trips intact", finalState.reputation, restored.reputation)
    }

    @Test
    fun `the combat-reputation replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(load(), capturePerTickStates = true)
        val second = ReplayRunner().run(load(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }
}
