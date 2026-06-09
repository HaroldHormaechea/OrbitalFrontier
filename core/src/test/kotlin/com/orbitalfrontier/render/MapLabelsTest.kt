package com.orbitalfrontier.render

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.render.MapLabels.Surface
import com.orbitalfrontier.world.AsteroidField
import com.orbitalfrontier.world.ContactKind
import com.orbitalfrontier.world.GateLink
import com.orbitalfrontier.world.HiddenContact
import com.orbitalfrontier.world.JumpGate
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.Station
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of [MapLabels.shouldLabel] — the policy deciding **which**
 * map markers get a name label (UC24). The renderers ([MinimapRenderer]/[MapOverlayRenderer]) own the
 * GL drawing; this predicate owns the decision, so it is asserted directly here (ADR 0001) and the
 * actual text rendering/legibility/clipping is left to a live emulator pass.
 *
 * The decision combines three rules (see [MapLabels]):
 *  1. **Visible as a contact** — exactly the renderers' marker-skip inverse: a [com.orbitalfrontier.world.Transponder]
 *     always shows; a hidden contact only once its id is in `revealedContacts` (UC10).
 *  2. **Named, non-blank** — [com.orbitalfrontier.world.Named] with a non-blank name (AC#3: no empty labels).
 *  3. **Anti-clutter per surface** (AC#4) — `MINIMAP` labels only stations; `OVERLAY` labels every
 *     visible named contact.
 *
 * **Testability note (sealed `Poi`).** `Poi` is a sealed interface, so the test source set (a separate
 * Kotlin module) cannot fabricate a fake POI to cover every theoretical branch. These behavioural
 * tests therefore use the real POI types — [Station] (Named + Transponder, STATION-kind), [JumpGate]
 * (Transponder, GATE-kind, not Named), [HiddenContact] (Contact, not Transponder, not Named), and
 * [AsteroidField] (a plain non-Contact POI). Two branches cannot be reached with the types that exist
 * today and are pinned instead by the source guard [com.orbitalfrontier.screen.Uc24MapLabelsGuardTest]:
 *  - the blank-name rejection (`displayName.isBlank()`) — [Station] guards its name non-blank at
 *    construction, so no real Named POI can carry a blank name; and
 *  - the MINIMAP-vs-OVERLAY divergence for a *named non-station* — the only Named type today is
 *    [Station], which is always STATION-kind, so a named non-station cannot be constructed.
 */
class MapLabelsTest {
    // --- AC#1 / AC#2: a named, visible station is labelled on BOTH surfaces -------------------------

    @Test
    fun `a named visible station is labelled on the minimap`() {
        // AC#1/#2 + AC#4: a station is the one thing the cluttered minimap always labels. This also
        // positively exercises the MINIMAP branch's `contactKind == STATION` check.
        assertTrue(
            "AC#1/#2: a named station is labelled on the HUD minimap",
            MapLabels.shouldLabel(station(), revealedContacts = emptySet(), surface = Surface.MINIMAP),
        )
    }

    @Test
    fun `a named visible station is labelled on the overlay`() {
        assertTrue(
            "AC#1/#2: a named station is labelled on the zoomed overlay",
            MapLabels.shouldLabel(station(), revealedContacts = emptySet(), surface = Surface.OVERLAY),
        )
    }

    @Test
    fun `a station is labelled with no revealed contacts because it broadcasts a transponder`() {
        // Visibility rule (UC10): a Transponder is always visible, so an empty revealed set still labels
        // it. Guards against the label pass accidentally depending on the scan/reveal state.
        val s = station()
        assertTrue(MapLabels.shouldLabel(s, revealedContacts = emptySet(), surface = Surface.OVERLAY))
        assertTrue(MapLabels.shouldLabel(s, revealedContacts = emptySet(), surface = Surface.MINIMAP))
    }

    // --- AC#3: items without a name are never labelled (no empty/placeholder labels) ----------------

    @Test
    fun `an unnamed visible gate is not labelled on either surface`() {
        // A jump gate is a visible Transponder (its marker IS drawn) but carries no name, so it must not
        // be labelled — AC#3. It is also a *visible non-station* contact, so the minimap not labelling it
        // is the closest behavioural evidence of the anti-clutter rule that the real types allow.
        val gate = gate()
        assertFalse(
            "AC#3: an unnamed gate is not labelled on the minimap",
            MapLabels.shouldLabel(gate, revealedContacts = emptySet(), surface = Surface.MINIMAP),
        )
        assertFalse(
            "AC#3: an unnamed gate is not labelled on the overlay",
            MapLabels.shouldLabel(gate, revealedContacts = emptySet(), surface = Surface.OVERLAY),
        )
    }

    @Test
    fun `a revealed but unnamed hidden contact is not labelled`() {
        // Visibility passes (it is in the revealed set, so its marker draws), but it has no name, so the
        // name rule still rejects it — AC#3. Proves the two rules are independent (visible != labelled).
        val ghost = hiddenContact()
        val revealed = setOf(ghost.id)
        assertFalse(
            "AC#3: a revealed yet unnamed contact draws a marker but no label",
            MapLabels.shouldLabel(ghost, revealedContacts = revealed, surface = Surface.OVERLAY),
        )
        assertFalse(
            MapLabels.shouldLabel(ghost, revealedContacts = revealed, surface = Surface.MINIMAP),
        )
    }

    // --- Visibility / UC10: an unrevealed hidden contact is never labelled --------------------------

    @Test
    fun `an unrevealed hidden contact is not labelled on either surface`() {
        // UC10: a hidden contact has no transponder and is absent from the revealed set, so its marker is
        // skipped — and a label must never appear without its marker.
        val ghost = hiddenContact()
        assertFalse(
            "UC10: an unscanned hidden contact is not labelled on the minimap",
            MapLabels.shouldLabel(ghost, revealedContacts = emptySet(), surface = Surface.MINIMAP),
        )
        assertFalse(
            "UC10: an unscanned hidden contact is not labelled on the overlay",
            MapLabels.shouldLabel(ghost, revealedContacts = emptySet(), surface = Surface.OVERLAY),
        )
    }

    // --- A non-contact POI is never labelled --------------------------------------------------------

    @Test
    fun `a non-contact POI is never labelled`() {
        // An asteroid field is a plain Poi (not a Contact), so it never appears on the map and must never
        // be labelled — even on the roomy overlay.
        val belt = asteroidField()
        assertFalse(
            "a non-contact POI is never labelled on the minimap",
            MapLabels.shouldLabel(belt, revealedContacts = emptySet(), surface = Surface.MINIMAP),
        )
        assertFalse(
            "a non-contact POI is never labelled on the overlay",
            MapLabels.shouldLabel(belt, revealedContacts = emptySet(), surface = Surface.OVERLAY),
        )
    }

    private companion object {
        private fun station(name: String = "Helios Station"): Station =
            Station(
                id = PoiId("test-station"),
                position = Vec2(10f, -20f),
                displayName = name,
            )

        private fun gate(): JumpGate =
            JumpGate(
                id = PoiId("test-gate"),
                position = Vec2(5f, 5f),
                triggerRadius = 50f,
                link = GateLink(destinationSector = SectorId("beta"), destinationGate = PoiId("beta-gate")),
            )

        private fun hiddenContact(): HiddenContact =
            HiddenContact(
                id = PoiId("test-ghost"),
                position = Vec2(-30f, 40f),
                contactKind = ContactKind.SHIP,
            )

        private fun asteroidField(): AsteroidField =
            AsteroidField(
                id = PoiId("test-belt"),
                position = Vec2(0f, 0f),
                deposits = mapOf(ResourceType.HYDROGEN to 10),
            )
    }
}
