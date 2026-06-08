package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.outfit.InstallResult
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [Scanning] resolver (UC10 AC#3/#4/#5).
 *
 * Three jobs, all against the production [MvpSectorMap] so the geometry tracks the real authored map:
 *  - **AC#5 — purity:** [Scanning.resolve] is a side-effect-free function of its inputs (it never
 *    mutates the `revealed` set passed in) and returns the **same set instance** when nothing new is
 *    revealed (`NONE`, or a `SCAN` that reaches nothing / only already-known contacts), so a caller
 *    can cheaply detect "nothing changed" with a `!==` check.
 *  - **AC#4 — monotonic:** a `SCAN` only ever **unions** newly-in-range contacts; a revealed id is
 *    never removed, even when a later scan happens from out of range.
 *  - **AC#3 — sensor-range gating + the upgrade payoff:** a base scan from [MvpSectorMap.SCAN_POINT]
 *    reveals only `alpha-derelict` (d=300 < base 500); an upgraded (SCANNER_I) scan additionally
 *    reveals `alpha-smuggler` (d=600 < 650); `alpha-ghost` (d=800) is never revealed from there.
 *
 * The sensor ranges are **derived** via [ShipStats.scanRange] from the starter ship's type + loadout
 * (the same derivation the simulation/device uses), so the "sensors upgrade widens the reveal radius"
 * claim is tested through the real stat path, not a hard-coded number.
 */
class ScanningTest {
    private val world: SectorWorld = MvpSectorMap.build()
    private val sector: SectorId = MvpSectorMap.START_SECTOR
    private val scanPoint: Vec2 = MvpSectorMap.SCAN_POINT

    private val derelict = PoiId("alpha-derelict")
    private val smuggler = PoiId("alpha-smuggler")
    private val ghost = PoiId("alpha-ghost")

    /** Starter sensor range with no sensors upgrade installed — 500 wu (dev-pinned geometry). */
    private val baseRange: Float = ShipStats.scanRange(ShipRoster.STARTER, Loadout.EMPTY)

    /** Starter sensor range with a SCANNER_I installed — 650 wu (dev-pinned geometry). */
    private val upgradedRange: Float =
        ShipStats.scanRange(ShipRoster.STARTER, loadoutWithScannerI())

    private fun loadoutWithScannerI(): Loadout {
        val slotCount = ShipRoster.STARTER.slotCount(SlotCategory.SENSORS)
        return (Loadout.EMPTY.install(SlotCategory.SENSORS, slotCount, UpgradeCatalog.SCANNER_I) as InstallResult.Installed)
            .loadout
    }

    private fun scan(
        range: Float,
        revealed: Set<PoiId> = emptySet(),
        action: ScanAction = ScanAction.SCAN,
    ): Set<PoiId> = Scanning.resolve(world, sector, scanPoint, range, revealed, action)

    // --- Geometry precondition: the dev-pinned ranges are what ShipStats derives ---

    @Test
    fun `derived sensor ranges match the dev-pinned geometry`() {
        assertEquals("starter base scan range", 500f, baseRange, 0f)
        assertEquals("SCANNER_I-upgraded scan range", 650f, upgradedRange, 0f)
    }

    // --- AC#3: sensor-range gating + the sensors-upgrade payoff ---

    @Test
    fun `a base scan from the scan point reveals only the in-range derelict`() {
        val revealed = scan(baseRange)

        assertEquals("base scan reveals exactly alpha-derelict", setOf(derelict), revealed)
        assertFalse("alpha-smuggler is outside base range", smuggler in revealed)
        assertFalse("alpha-ghost is outside base range", ghost in revealed)
    }

    @Test
    fun `an upgraded scan additionally reveals the smuggler the base scan missed`() {
        val revealed = scan(upgradedRange)

        assertEquals(
            "the SCANNER_I scan reveals both the derelict and the smuggler",
            setOf(derelict, smuggler),
            revealed,
        )
        assertFalse("alpha-ghost is outside even the upgraded range", ghost in revealed)
    }

    @Test
    fun `the ghost is never revealed from the scan point, even upgraded`() {
        assertFalse("base scan never reaches the ghost", ghost in scan(baseRange))
        assertFalse("upgraded scan never reaches the ghost", ghost in scan(upgradedRange))
    }

    // --- AC#4: monotonic union — reveal only ever grows, never re-hides ---

    @Test
    fun `a scan unions newly-in-range contacts into the prior revealed set`() {
        // Start with the smuggler already revealed (e.g. from an earlier upgraded scan); a base scan
        // now adds the derelict WITHOUT dropping the smuggler.
        val revealed = scan(baseRange, revealed = setOf(smuggler))

        assertEquals("the base scan unions the derelict onto the prior set", setOf(smuggler, derelict), revealed)
    }

    @Test
    fun `an already-revealed contact stays revealed even when scanning out of range`() {
        // Reveal the derelict, then scan from a point far from every contact (huge offset): nothing new
        // is in range, but the derelict must remain revealed (monotonic — it does not re-hide).
        val faraway = Vec2(100_000f, 100_000f)
        val result = Scanning.resolve(world, sector, faraway, upgradedRange, setOf(derelict), ScanAction.SCAN)

        assertTrue("the previously-revealed derelict stays known out of range", derelict in result)
    }

    // --- AC#5: purity — no mutation, and same-instance no-op for change detection ---

    @Test
    fun `resolve does not mutate the revealed set passed in`() {
        val input = setOf(smuggler)
        scan(upgradedRange, revealed = input)

        assertEquals("the input set is left untouched (pure function)", setOf(smuggler), input)
    }

    @Test
    fun `a NONE action returns the same set instance unchanged`() {
        val input = setOf(derelict)
        val result = scan(baseRange, revealed = input, action = ScanAction.NONE)

        assertSame("NONE must return the SAME instance for cheap change detection", input, result)
    }

    @Test
    fun `a scan that reveals nothing new returns the same set instance`() {
        // The derelict is the only contact in base range; scanning again with it already revealed adds
        // nothing, so the SAME instance comes back (the !== change-detection contract).
        val input = setOf(derelict)
        val result = scan(baseRange, revealed = input, action = ScanAction.SCAN)

        assertSame("a no-new-contact SCAN must return the SAME instance", input, result)
    }

    @Test
    fun `a scan that reaches nothing at all returns the same empty set instance`() {
        val input = emptySet<PoiId>()
        // A zero range reaches nothing, so the same (empty) instance threads through unchanged.
        val result = Scanning.resolve(world, sector, scanPoint, 0f, input, ScanAction.SCAN)

        assertSame("an out-of-range SCAN must return the SAME instance", input, result)
    }

    @Test
    fun `resolve is deterministic — identical inputs yield equal results`() {
        assertEquals(scan(upgradedRange), scan(upgradedRange))
    }

    // --- AC#1/AC#2: contact taxonomy + the minimap visibility rule (model level) ---
    //
    // The live minimap (render/MinimapRenderer) is libGDX-bound and not JVM-testable, but the *rule* it
    // implements is: a POI is drawn iff it is a Transponder (auto-show, AC#1) OR its id has been revealed
    // by a scan (AC#2/#3). These tests pin that rule against the production map's authored contacts.

    @Test
    fun `gates and stations broadcast transponders, so they auto-show (AC#1)`() {
        val s = world.sector(sector)
        assertTrue("the start sector authors gates", s.gates.isNotEmpty())
        assertTrue("the start sector authors stations", s.stations.isNotEmpty())
        assertTrue("every gate is a Transponder (auto-shown)", s.gates.all { it is Transponder })
        assertTrue("every station is a Transponder (auto-shown)", s.stations.all { it is Transponder })
    }

    @Test
    fun `hidden contacts are contacts but not transponders, so they stay hidden until scanned (AC#2)`() {
        val hidden = world.sector(sector).hiddenContacts
        assertTrue("the start sector authors hidden contacts", hidden.isNotEmpty())
        for (h in hidden) {
            assertTrue("a hidden contact is a Contact (can show once revealed)", h is Contact)
            assertFalse("a hidden contact must NOT be a Transponder (else it would auto-show)", h is Transponder)
        }
    }

    @Test
    fun `the visibility rule shows transponders always and hidden contacts only once revealed`() {
        // Mirrors MinimapRenderer's predicate exactly, without importing the libGDX-bound renderer.
        fun visible(
            poi: Poi,
            revealed: Set<PoiId>,
        ): Boolean = poi is Contact && (poi is Transponder || poi.id in revealed)

        val pois = world.sector(sector).pois
        // Before any scan: every transponder shows (AC#1); no hidden contact shows (AC#2).
        for (poi in pois) {
            if (poi is Transponder) assertTrue("a transponder auto-shows", visible(poi, emptySet()))
            if (poi is HiddenContact) assertFalse("a hidden contact is hidden until scanned", visible(poi, emptySet()))
        }

        // After a base scan revealing the derelict: only it (among hidden contacts) becomes visible (AC#3).
        val revealed = scan(baseRange)
        assertTrue("the revealed derelict now shows", visible(pois.first { it.id == derelict }, revealed))
        assertFalse("the unrevealed ghost stays hidden", visible(pois.first { it.id == ghost }, revealed))
    }
}
