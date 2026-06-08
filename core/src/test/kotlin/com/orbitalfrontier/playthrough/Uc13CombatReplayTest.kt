package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.SectionDamages
import com.orbitalfrontier.combat.ShipSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay test for the UC13 combat playthrough (AC#8), following the record→replay→assert pattern
 * (docs/PLAYTESTING.md).
 *
 * The committed `uc13-combat` artifact flies the player from outside into the alpha-raider-picket zone
 * to spawn a RAIDER, then holds FIRE while loitering so the crew-gated auto-aim turret destroys it. This
 * test replays it headlessly and asserts the AC#8 contract:
 *  - a fight actually happened: at some tick the encounter was **active** with a hostile present;
 *  - the hostile is **gone**: the final combat state is cleared ([com.orbitalfrontier.combat.CombatState.NONE]);
 *  - the player **survived** (no respawn) and the **sectional damage taken is recorded** on the active ship;
 *  - the replay is **bit-for-bit deterministic** across two runs.
 *
 * The RAIDER is AGGRESSIVE (never flees) and the player loiters well inside its leash, so the only way the
 * encounter can clear is the raider's **destruction** — a cleared final state therefore means it was killed,
 * not that it broke off.
 *
 * The artifact is reproduced from [PlaythroughFixtures.uc13Combat] and guarded by [PlaythroughFixtureTest];
 * it is also loadable via the `playtest` skill (`-Dplaythrough.name=uc13-combat`).
 */
class Uc13CombatReplayTest {
    private fun load(): Playthrough = PlaythroughResources.load(PlaythroughFixtures.UC13_COMBAT)

    @Test
    fun `flying into the picket spawns a raider that the turret then destroys, with damage recorded`() {
        val result = ReplayRunner().run(load(), capturePerTickStates = true)
        val finalState = result.finalState

        // A real fight occurred: some intermediate tick had an active encounter with a hostile present.
        val fought = result.perTickStates.any { it.combat.active && it.combat.hostiles.isNotEmpty() }
        assertTrue("the player was ambushed by the picket raider", fought)

        // The hostile is GONE: the encounter cleared (RAIDER never flees + the player loitered within leash,
        // so a cleared encounter means the raider was destroyed).
        assertFalse("the encounter is cleared at the end", finalState.combat.active)
        assertTrue("no hostiles remain", finalState.combat.hostiles.isEmpty())

        // The player SURVIVED (no respawn) and the sectional damage it took is recorded on the active ship.
        // With the fixture's HULL-funnelled tuning, the damage lands on (and only on) the HULL.
        val active = finalState.fleet.active
        val hullHp = SectionDamages.currentHp(active.sectionDamage, ShipSection.HULL, 100)
        assertTrue("the player took recorded HULL damage", hullHp < 100)
        assertTrue("the player survived (hull not destroyed)", hullHp > 0)
        assertEquals("only the HULL was damaged (pinned tuning)", setOf(ShipSection.HULL), active.sectionDamage.keys)
    }

    @Test
    fun `the combat replay is bit-for-bit deterministic`() {
        val first = ReplayRunner().run(load(), capturePerTickStates = true)
        val second = ReplayRunner().run(load(), capturePerTickStates = true)

        assertEquals("final states match exactly", first.finalState, second.finalState)
        assertEquals("every intermediate tick matches exactly", first.perTickStates, second.perTickStates)
    }
}
