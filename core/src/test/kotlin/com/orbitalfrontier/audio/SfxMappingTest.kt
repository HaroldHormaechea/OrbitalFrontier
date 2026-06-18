package com.orbitalfrontier.audio

import com.orbitalfrontier.combat.CombatEvent
import com.orbitalfrontier.combat.HostileId
import com.orbitalfrontier.combat.ShipSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [Sfx.forCombatEvent] — the pure mapping from a [CombatEvent] to the SFX cue the device
 * audio layer plays (UC31 AC#1/#4).
 *
 * The mapping is the seam that keeps combat sound driven by the model's structured output rather than
 * embedded in the deterministic simulation: only the three cues AC#1 calls out (player fire, hostile
 * hit, hostile destroyed) map to a sound; every other combat event is intentionally silent. A
 * completeness check over the sealed [CombatEvent] hierarchy fails fast if a future event subtype is
 * added without a deliberate cue/`null` decision here.
 */
class SfxMappingTest {
    @Test
    fun `player fire maps to the weapon-fire cue`() {
        assertEquals(Sfx.WEAPON_FIRE, Sfx.forCombatEvent(CombatEvent.PlayerFired(turret = false)))
        assertEquals(Sfx.WEAPON_FIRE, Sfx.forCombatEvent(CombatEvent.PlayerFired(turret = true)))
    }

    @Test
    fun `a hostile hit maps to the enemy-hit cue`() {
        assertEquals(
            Sfx.ENEMY_HIT,
            Sfx.forCombatEvent(CombatEvent.HostileHit(HostileId(1), ShipSection.HULL)),
        )
    }

    @Test
    fun `a hostile destruction maps to the enemy-destroyed cue`() {
        assertEquals(
            Sfx.ENEMY_DESTROYED,
            Sfx.forCombatEvent(CombatEvent.HostileDestroyed(HostileId(7))),
        )
    }

    @Test
    fun `combat events with no AC#1 cue map to null (silent)`() {
        assertNull("hostile fire is silent", Sfx.forCombatEvent(CombatEvent.HostileFired(HostileId(2))))
        assertNull("hostile break-off is silent", Sfx.forCombatEvent(CombatEvent.HostileBrokeOff(HostileId(3))))
        assertNull("a player hit is silent", Sfx.forCombatEvent(CombatEvent.PlayerHit(ShipSection.ENGINE)))
        assertNull("player destruction is silent", Sfx.forCombatEvent(CombatEvent.PlayerDestroyed))
        assertNull("encounter cleared is silent", Sfx.forCombatEvent(CombatEvent.EncounterCleared))
    }

    @Test
    fun `every CombatEvent subtype is given a deliberate cue-or-null decision`() {
        // One representative of every sealed subtype. The `expectedCue` mapping below is a SEPARATE,
        // compiler-checked exhaustive `when` (no `else`), so introducing a new CombatEvent subtype breaks
        // THIS test's compilation until a deliberate cue/`null` decision is recorded here too — the
        // project avoids `sealedSubclasses` (no kotlin-reflect on the test classpath; see WorldGlyphsTest),
        // and a compile-time guard is stronger than a reflective count anyway.
        val samples: List<CombatEvent> =
            listOf(
                CombatEvent.PlayerFired(turret = false),
                CombatEvent.HostileFired(HostileId(1)),
                CombatEvent.HostileHit(HostileId(1), ShipSection.WEAPON),
                CombatEvent.HostileDestroyed(HostileId(1)),
                CombatEvent.HostileBrokeOff(HostileId(1)),
                CombatEvent.PlayerHit(ShipSection.TURRET),
                CombatEvent.PlayerDestroyed,
                CombatEvent.EncounterCleared,
            )

        for (event in samples) {
            assertEquals(
                "forCombatEvent must match the deliberate mapping for $event",
                expectedCue(event),
                Sfx.forCombatEvent(event),
            )
        }

        // The exact AC#1 cue set: precisely these three events produce a sound; all others are null.
        val soundedCues = samples.mapNotNull { Sfx.forCombatEvent(it) }.toSet()
        assertEquals(
            "exactly weapon-fire/enemy-hit/enemy-destroyed are sounded",
            setOf(Sfx.WEAPON_FIRE, Sfx.ENEMY_HIT, Sfx.ENEMY_DESTROYED),
            soundedCues,
        )
    }

    /**
     * Independent, exhaustive (no `else`) restatement of the AC#1 contract. The compiler forces every
     * [CombatEvent] subtype to appear, so a future event added to the sealed hierarchy cannot slip
     * through without a deliberate cue/`null` decision being mirrored here.
     */
    private fun expectedCue(event: CombatEvent): Sfx? =
        when (event) {
            is CombatEvent.PlayerFired -> Sfx.WEAPON_FIRE
            is CombatEvent.HostileHit -> Sfx.ENEMY_HIT
            is CombatEvent.HostileDestroyed -> Sfx.ENEMY_DESTROYED
            is CombatEvent.HostileFired -> null
            is CombatEvent.HostileBrokeOff -> null
            is CombatEvent.PlayerHit -> null
            CombatEvent.PlayerDestroyed -> null
            CombatEvent.EncounterCleared -> null
        }

    @Test
    fun `the cue catalogue covers every AC#1 gameplay event`() {
        // AC#1 names these eight cue families; assert each is a real enum entry so a rename can't silently
        // drop one. (Combat cues are mapped via forCombatEvent above; the rest fire off non-combat seams.)
        val required =
            listOf(
                Sfx.THRUST,
                Sfx.WEAPON_FIRE,
                Sfx.ENEMY_HIT,
                Sfx.ENEMY_DESTROYED,
                Sfx.MINING_TICK,
                Sfx.DOCK,
                Sfx.JUMP,
                Sfx.UI_TAP,
                Sfx.MISSION_ACCEPT,
                Sfx.MISSION_COMPLETE,
            )
        assertTrue("all AC#1 cues present in the catalogue", Sfx.entries.containsAll(required))
    }
}
