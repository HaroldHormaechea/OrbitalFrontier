package com.orbitalfrontier.outfit

import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ShipStats] (UC09 AC#2/#7) — the one place "ship type + loadout → effective stat"
 * lives, and the home of the **byte-identical contract** the Stage A Fleet refactor relies on.
 *
 * Two jobs:
 *  - pin the byte-identical guard (starter + empty fit derives exactly today's constants, and
 *    [ShipStats.effectiveMovementParams] returns the base params **unchanged**), and
 *  - prove stat derivation: an engine upgrade lifts only speed/accel (deadzone/cone invariant), a
 *    cargo pod / fuel tank lifts the matching capacity, deltas compose order-insensitively, and an
 *    unknown upgrade id contributes nothing (never stranded).
 */
class ShipStatsTest {
    private val starter = ShipRoster.STARTER
    private val baseParams = ShipMovementParams()

    /** Build a loadout by installing [ids] into [category], bounded by the starter's slot count. */
    private fun loadoutOf(
        category: SlotCategory,
        ids: List<UpgradeId>,
    ): Loadout {
        var loadout = Loadout.EMPTY
        val slotCount = starter.slotCount(category)
        for (id in ids) {
            loadout = (loadout.install(category, slotCount, id) as InstallResult.Installed).loadout
        }
        return loadout
    }

    // --- Byte-identical contract (HARD invariant, Stage A) ---

    @Test
    fun `starter with an empty loadout derives exactly today's cargo capacity`() {
        assertEquals(Cargo.DEFAULT_CAPACITY, ShipStats.cargoCapacity(starter, Loadout.EMPTY))
    }

    @Test
    fun `starter with an empty loadout derives exactly today's fuel capacity`() {
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, ShipStats.fuelCapacity(starter, Loadout.EMPTY), 0f)
    }

    @Test
    fun `effectiveMovementParams returns the base params unchanged for starter + empty loadout`() {
        val result = ShipStats.effectiveMovementParams(baseParams, starter, Loadout.EMPTY)

        // Same instance — the code returns `base` itself when neither field moves (identity profile,
        // zero deltas), which is what keeps the pre-UC09 movement fixtures replaying bit-for-bit.
        assertSame("must return the base params instance unchanged", baseParams, result)
    }

    // --- Engine delta touches ONLY speed/accel; deadzone + handling are invariant ---

    @Test
    fun `an engine upgrade raises only max speed and acceleration`() {
        val loadout = loadoutOf(SlotCategory.ENGINES, listOf(UpgradeCatalog.ENGINE_TUNE_I))

        val result = ShipStats.effectiveMovementParams(baseParams, starter, loadout)

        // engine-tune-i = +20 speed, +12 accel (from UpgradeCatalog.MVP).
        assertEquals(baseParams.maxSpeed + 20f, result.maxSpeed, 0f)
        assertEquals(baseParams.maxAcceleration + 12f, result.maxAcceleration, 0f)
        // Everything else — including the input deadzone and the cones — is copied through unchanged.
        assertEquals(baseParams.inputDeadzone, result.inputDeadzone, 0f)
        assertEquals(baseParams.forwardConeRadians, result.forwardConeRadians, 0f)
        assertEquals(baseParams.reverseConeRadians, result.reverseConeRadians, 0f)
        assertEquals(baseParams.maxReverseSpeed, result.maxReverseSpeed, 0f)
        assertEquals(baseParams.rotationAcceleration, result.rotationAcceleration, 0f)
        assertEquals(baseParams.maxRotationSpeed, result.maxRotationSpeed, 0f)
        assertEquals(baseParams.driftDecay, result.driftDecay, 0f)
    }

    @Test
    fun `the input deadzone is invariant under every catalog engine upgrade`() {
        for (engine in listOf(UpgradeCatalog.ENGINE_TUNE_I, UpgradeCatalog.ENGINE_TUNE_II)) {
            val loadout = loadoutOf(SlotCategory.ENGINES, listOf(engine))
            val result = ShipStats.effectiveMovementParams(baseParams, starter, loadout)
            assertEquals(
                "engine ${engine.value} must not move the input deadzone",
                baseParams.inputDeadzone,
                result.inputDeadzone,
                0f,
            )
            // Sanity: it DID change the speed cap (so the invariant isn't vacuously holding on a no-op).
            assertNotEquals("engine ${engine.value} should raise max speed", baseParams.maxSpeed, result.maxSpeed)
        }
    }

    @Test
    fun `stacking two engine upgrades sums their speed and acceleration deltas`() {
        val loadout = loadoutOf(SlotCategory.ENGINES, listOf(UpgradeCatalog.ENGINE_TUNE_I, UpgradeCatalog.ENGINE_TUNE_II))

        val result = ShipStats.effectiveMovementParams(baseParams, starter, loadout)

        // (+20 +45) speed, (+12 +28) accel.
        assertEquals(baseParams.maxSpeed + 65f, result.maxSpeed, 0f)
        assertEquals(baseParams.maxAcceleration + 40f, result.maxAcceleration, 0f)
    }

    // --- Capacity deltas: cargo pod -> cargo cap, fuel tank -> fuel cap ---

    @Test
    fun `a cargo pod raises cargo capacity by its delta and leaves fuel untouched`() {
        val loadout = loadoutOf(SlotCategory.CARGO, listOf(UpgradeCatalog.CARGO_POD_I))

        assertEquals(Cargo.DEFAULT_CAPACITY + 25, ShipStats.cargoCapacity(starter, loadout))
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, ShipStats.fuelCapacity(starter, loadout), 0f)
    }

    @Test
    fun `a fuel tank raises fuel capacity by its delta and leaves cargo untouched`() {
        val loadout = loadoutOf(SlotCategory.FUEL_TANK, listOf(UpgradeCatalog.FUEL_TANK_I))

        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY + 50f, ShipStats.fuelCapacity(starter, loadout), 0f)
        assertEquals(Cargo.DEFAULT_CAPACITY, ShipStats.cargoCapacity(starter, loadout))
    }

    @Test
    fun `a scanner raises scan range by its delta`() {
        val loadout = loadoutOf(SlotCategory.SENSORS, listOf(UpgradeCatalog.SCANNER_I))

        // starter base scan range 500 + scanner-i 150.
        assertEquals(650f, ShipStats.scanRange(starter, loadout), 0f)
    }

    // --- UC11: crew capacity = base crew + crew-quarters deltas (no MVP crew part yet) ---

    @Test
    fun `starter with an empty loadout derives its base crew capacity`() {
        // The starter's base crew capacity is the authored DEFAULT_STARTER_CREW (2).
        assertEquals(starter.baseCrewCapacity, ShipStats.crewCapacity(starter, Loadout.EMPTY))
        assertEquals(2, ShipStats.crewCapacity(starter, Loadout.EMPTY))
    }

    @Test
    fun `a crew-quarters part raises crew capacity by its delta and leaves cargo and fuel untouched`() {
        // The MVP catalog ships no crew-quarters part yet, so use a synthetic catalog carrying one
        // (+3 crew) to exercise the crew delta path.
        val crewQuartersI = UpgradeId("crew-quarters-i-test")
        val crewCatalog =
            UpgradeCatalog(
                listOf(
                    Upgrade(
                        id = crewQuartersI,
                        category = SlotCategory.CREW_QUARTERS,
                        displayName = "Crew Quarters I (test)",
                        price = 200,
                        statDeltas = StatDelta(crew = 3),
                    ),
                ),
            )
        val slotCount = starter.slotCount(SlotCategory.CREW_QUARTERS)
        val loadout = (Loadout.EMPTY.install(SlotCategory.CREW_QUARTERS, slotCount, crewQuartersI) as InstallResult.Installed).loadout

        // base crew 2 + crew-quarters 3.
        assertEquals(5, ShipStats.crewCapacity(starter, loadout, crewCatalog))
        // The crew part touches neither cargo nor fuel.
        assertEquals(Cargo.DEFAULT_CAPACITY, ShipStats.cargoCapacity(starter, loadout, crewCatalog))
        assertEquals(FuelParams.DEFAULT_TANK_CAPACITY, ShipStats.fuelCapacity(starter, loadout, crewCatalog), 0f)
    }

    // --- Robustness: unknown upgrade ids contribute nothing; order does not matter ---

    @Test
    fun `an upgrade id the catalog does not know contributes no delta`() {
        // A loadout carrying an id absent from the MVP catalog (e.g. a removed part in an old save).
        val ghost = Loadout(mapOf(SlotCategory.ENGINES to mapOf(0 to UpgradeId("ghost-engine"))))

        assertEquals(Cargo.DEFAULT_CAPACITY, ShipStats.cargoCapacity(starter, ghost))
        assertSame(baseParams, ShipStats.effectiveMovementParams(baseParams, starter, ghost))
    }

    @Test
    fun `derived stats are independent of install order`() {
        val a = loadoutOf(SlotCategory.CARGO, listOf(UpgradeCatalog.CARGO_POD_I, UpgradeCatalog.CARGO_POD_II))
        val b = loadoutOf(SlotCategory.CARGO, listOf(UpgradeCatalog.CARGO_POD_II, UpgradeCatalog.CARGO_POD_I))

        assertEquals(ShipStats.cargoCapacity(starter, a), ShipStats.cargoCapacity(starter, b))
        // +25 +60 on top of the base, regardless of order.
        assertEquals(Cargo.DEFAULT_CAPACITY + 85, ShipStats.cargoCapacity(starter, a))
    }

    @Test
    fun `a non-identity hull movement profile scales the base before deltas`() {
        // SWIFT is intrinsically faster (x1.25 speed, x1.2 accel) with no upgrades.
        val swift = ShipRoster.SWIFT
        val result = ShipStats.effectiveMovementParams(baseParams, swift, Loadout.EMPTY)

        assertEquals(baseParams.maxSpeed * 1.25f, result.maxSpeed, 1e-3f)
        assertEquals(baseParams.maxAcceleration * 1.2f, result.maxAcceleration, 1e-3f)
        // The handling params are still copied through untouched.
        assertEquals(baseParams.inputDeadzone, result.inputDeadzone, 0f)
    }

    // --- UC13: derived per-section HP + weapon fit ---

    @Test
    fun `the starter ship derives the authored base section HP`() {
        assertEquals("base HULL", 100, ShipStats.sectionHp(starter, Loadout.EMPTY, ShipSection.HULL))
        assertEquals("base ENGINE", 40, ShipStats.sectionHp(starter, Loadout.EMPTY, ShipSection.ENGINE))
        assertEquals("base TURRET", 30, ShipStats.sectionHp(starter, Loadout.EMPTY, ShipSection.TURRET))
        assertEquals("base WEAPON", 30, ShipStats.sectionHp(starter, Loadout.EMPTY, ShipSection.WEAPON))
    }

    @Test
    fun `sectionHpMap covers every section`() {
        val map = ShipStats.sectionHpMap(starter, Loadout.EMPTY)
        assertEquals("every ShipSection has a derived max HP", ShipSection.entries.toSet(), map.keys)
        assertEquals(100, map[ShipSection.HULL])
        assertEquals(40, map[ShipSection.ENGINE])
    }

    @Test
    fun `installed hull plating adds HP to the HULL only`() {
        // The HULL_PLATING category carries no catalogued upgrade yet, but ShipStats reads the installed
        // COUNT — so a directly-built loadout with one plating part exercises the +25 HULL bonus.
        val plated = Loadout(mapOf(SlotCategory.HULL_PLATING to mapOf(0 to UpgradeId("hull-plate"))))

        assertEquals("HULL gains +25 per plating part", 125, ShipStats.sectionHp(starter, plated, ShipSection.HULL))
        // Other sections are untouched by hull plating.
        assertEquals("ENGINE unaffected by hull plating", 40, ShipStats.sectionHp(starter, plated, ShipSection.ENGINE))
        assertEquals("TURRET unaffected by hull plating", 30, ShipStats.sectionHp(starter, plated, ShipSection.TURRET))
    }

    @Test
    fun `the starter weapon fit is one fixed gun and one crew-gated turret`() {
        val fit = ShipStats.weaponLoadout(starter, Loadout.EMPTY)
        assertEquals("one built-in fixed weapon", 1, fit.fixed.size)
        assertEquals("one built-in turret", 1, fit.turrets.size)
        assertEquals("the turret needs 1 crew to operate", 1, fit.turrets.single().requiredCrew)
        // At the new-game crew of 0 the turret is inoperable (AC#2 demo).
        assertTrue("no operable turret at 0 crew", fit.operableTurrets(0).isEmpty())
        assertEquals("the turret is operable at 1 crew", 1, fit.operableTurrets(1).size)
    }

    @Test
    fun `each installed weapons-slot part adds another fixed weapon`() {
        val armed = Loadout(mapOf(SlotCategory.WEAPONS to mapOf(0 to UpgradeId("extra-gun"))))
        val fit = ShipStats.weaponLoadout(starter, armed)
        assertEquals("built-in gun + one installed = two fixed weapons", 2, fit.fixed.size)
        assertEquals("the turret count is unchanged", 1, fit.turrets.size)
    }
}
