package com.orbitalfrontier.notify

import com.orbitalfrontier.combat.CombatEvent
import com.orbitalfrontier.combat.HostileId
import com.orbitalfrontier.combat.ShipSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [GameNotifications] — the pure mapping from gameplay seams to the toast a
 * [NotificationQueue] surfaces (UC35 AC#1).
 *
 * Mirrors the role [com.orbitalfrontier.audio.SfxMappingTest] plays for audio: the model decides *which*
 * discrete events become a player-facing notification (and at what severity) without any engine in the
 * loop, so the same deterministic seams that drive sound (UC31) drive the feed. The combat mapping in
 * particular is the flood-defense layer-1 contract — only the encounter boundary surfaces; every per-tick
 * combat event maps to `null` so a firefight can't spam the queue (AC#2 pitfall).
 */
class GameNotificationsTest {
    // --- AC#1: forCombatEvent — only the encounter boundary surfaces, per-tick events are silent --------

    @Test
    fun `encounter cleared maps to the left-combat notification`() {
        val n = GameNotifications.forCombatEvent(CombatEvent.EncounterCleared)
        assertEquals("EncounterCleared surfaces LEFT_COMBAT", NotificationKind.LEFT_COMBAT, n?.kind)
        assertEquals("left combat is an INFO cue", NotificationSeverity.INFO, n?.severity)
    }

    @Test
    fun `every per-tick combat event is silent (no toast)`() {
        assertNull("player fire is silent", GameNotifications.forCombatEvent(CombatEvent.PlayerFired(turret = false)))
        assertNull("turret fire is silent", GameNotifications.forCombatEvent(CombatEvent.PlayerFired(turret = true)))
        assertNull("hostile fire is silent", GameNotifications.forCombatEvent(CombatEvent.HostileFired(HostileId(1))))
        assertNull(
            "a hostile hit is silent",
            GameNotifications.forCombatEvent(CombatEvent.HostileHit(HostileId(1), ShipSection.HULL)),
        )
        assertNull(
            "a hostile destruction is silent",
            GameNotifications.forCombatEvent(CombatEvent.HostileDestroyed(HostileId(2))),
        )
        assertNull("a hostile break-off is silent", GameNotifications.forCombatEvent(CombatEvent.HostileBrokeOff(HostileId(3))))
        assertNull("a player hit is silent", GameNotifications.forCombatEvent(CombatEvent.PlayerHit(ShipSection.ENGINE)))
        assertNull("player destruction is silent", GameNotifications.forCombatEvent(CombatEvent.PlayerDestroyed))
    }

    @Test
    fun `every CombatEvent subtype is given a deliberate notification-or-null decision`() {
        // One representative of every sealed subtype. `expected` below is a SEPARATE, compiler-checked
        // exhaustive `when` (no `else`), so adding a new CombatEvent subtype breaks THIS test's compilation
        // until a deliberate notification/`null` decision is recorded here too. (The project avoids
        // sealedSubclasses — no kotlin-reflect on the test classpath; see SfxMappingTest/WorldGlyphsTest.)
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
                expectedKind(event),
                GameNotifications.forCombatEvent(event)?.kind,
            )
        }

        // The exact AC#1 contract: precisely the encounter boundary produces a toast; all others are null.
        val surfaced = samples.mapNotNull { GameNotifications.forCombatEvent(it)?.kind }.toSet()
        assertEquals("exactly LEFT_COMBAT is surfaced from combat events", setOf(NotificationKind.LEFT_COMBAT), surfaced)
    }

    /**
     * Independent, exhaustive (no `else`) restatement of the combat-mapping contract. The compiler forces
     * every [CombatEvent] subtype to appear, so a future event added to the sealed hierarchy cannot slip
     * through without a deliberate notification/`null` decision being mirrored here.
     */
    private fun expectedKind(event: CombatEvent): NotificationKind? =
        when (event) {
            CombatEvent.EncounterCleared -> NotificationKind.LEFT_COMBAT
            is CombatEvent.PlayerFired -> null
            is CombatEvent.HostileFired -> null
            is CombatEvent.HostileHit -> null
            is CombatEvent.HostileDestroyed -> null
            is CombatEvent.HostileBrokeOff -> null
            is CombatEvent.PlayerHit -> null
            CombatEvent.PlayerDestroyed -> null
        }

    // --- AC#1: creditDelta — gain (INFO) / loss (WARNING) / no-change (null) ----------------------------

    @Test
    fun `a credit gain maps to an INFO gain notification`() {
        val n = GameNotifications.creditDelta(old = 100L, new = 250L)
        assertEquals(NotificationKind.CREDIT_GAIN, n?.kind)
        assertEquals("a gain is INFO", NotificationSeverity.INFO, n?.severity)
        assertEquals("the delta magnitude is shown", "+150 CR", n?.message)
    }

    @Test
    fun `a credit loss maps to a WARNING loss notification`() {
        val n = GameNotifications.creditDelta(old = 250L, new = 100L)
        assertEquals(NotificationKind.CREDIT_LOSS, n?.kind)
        assertEquals("a loss is WARNING", NotificationSeverity.WARNING, n?.severity)
        assertEquals("the (positive) magnitude is shown", "-150 CR", n?.message)
    }

    @Test
    fun `an unchanged balance produces no notification`() {
        assertNull("no delta means no toast", GameNotifications.creditDelta(old = 500L, new = 500L))
        assertNull("zero balance unchanged means no toast", GameNotifications.creditDelta(old = 0L, new = 0L))
    }

    @Test
    fun `creditDelta handles a balance crossing zero in both directions`() {
        val gain = GameNotifications.creditDelta(old = -50L, new = 30L)
        assertEquals(NotificationKind.CREDIT_GAIN, gain?.kind)
        assertEquals("+80 CR", gain?.message)
        val loss = GameNotifications.creditDelta(old = 30L, new = -50L)
        assertEquals(NotificationKind.CREDIT_LOSS, loss?.kind)
        assertEquals("-80 CR", loss?.message)
    }

    // --- AC#1: the non-combat builders surface the right kind + carry a message -------------------------

    @Test
    fun `the non-combat builders produce the expected kinds`() {
        assertEquals(NotificationKind.JUMP_COMPLETED, GameNotifications.jumpCompleted("CYGNUS").kind)
        assertEquals(NotificationKind.DOCKED, GameNotifications.docked("PORT NADIR").kind)
        assertEquals(NotificationKind.UNDOCKED, GameNotifications.undocked().kind)
        assertEquals(NotificationKind.MISSION_ACCEPTED, GameNotifications.missionAccepted().kind)
        assertEquals(NotificationKind.MISSION_COMPLETED, GameNotifications.missionCompleted().kind)
        assertEquals(NotificationKind.MISSION_FAILED_TIMEOUT, GameNotifications.missionFailedTimeout().kind)
        assertEquals(NotificationKind.ENTERED_COMBAT, GameNotifications.enteredCombat().kind)
        assertEquals(NotificationKind.LOW_FUEL, GameNotifications.lowFuel().kind)
    }

    @Test
    fun `builders thread their context into the message`() {
        assertTrue("the jump toast names the sector", GameNotifications.jumpCompleted("CYGNUS").message.contains("CYGNUS"))
        assertTrue("the dock toast names the station", GameNotifications.docked("PORT NADIR").message.contains("PORT NADIR"))
    }

    // --- AC#1/AC#2: severity classification lives with the model, per kind ------------------------------

    @Test
    fun `each kind carries its documented default severity`() {
        val warning =
            setOf(
                NotificationKind.LOW_FUEL,
                NotificationKind.MISSION_FAILED_TIMEOUT,
                NotificationKind.ENTERED_COMBAT,
                NotificationKind.CREDIT_LOSS,
            )
        for (kind in NotificationKind.entries) {
            val expected = if (kind in warning) NotificationSeverity.WARNING else NotificationSeverity.INFO
            assertEquals("$kind must default to $expected", expected, kind.defaultSeverity)
        }
    }

    @Test
    fun `a notification inherits its kind's default severity unless overridden`() {
        assertEquals(NotificationKind.LOW_FUEL.defaultSeverity, GameNotification(NotificationKind.LOW_FUEL, "LOW FUEL").severity)
        assertEquals(NotificationKind.JUMP_COMPLETED.defaultSeverity, GameNotifications.jumpCompleted("X").severity)
    }

    // --- AC#2 pitfall: exactly the burst-prone kinds coalesce; one-shot events never do -----------------

    @Test
    fun `only the burst-prone kinds are coalescable`() {
        val coalescable =
            setOf(
                NotificationKind.LOW_FUEL,
                NotificationKind.MISSION_FAILED_TIMEOUT,
                NotificationKind.CREDIT_GAIN,
                NotificationKind.CREDIT_LOSS,
            )
        for (kind in NotificationKind.entries) {
            assertEquals("$kind coalescable flag", kind in coalescable, kind.coalescable)
        }
    }

    @Test
    fun `a notification coalesces on its kind by default`() {
        // coalesceKey defaults to the kind, so two same-kind coalescable events collapse in the queue.
        assertEquals(NotificationKind.CREDIT_GAIN, GameNotifications.creditDelta(0L, 5L)?.coalesceKey)
        assertEquals(NotificationKind.LOW_FUEL, GameNotifications.lowFuel().coalesceKey)
    }
}
