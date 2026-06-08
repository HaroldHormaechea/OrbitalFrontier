package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.TradeKind
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.ScanAction

/**
 * Reproducible test playthrough fixtures, built from the [PlaythroughRecorder] (UC02 AC#4/#7).
 *
 * The committed JSON artifacts under `core/src/test/resources/playthroughs/` are generated from
 * these builders (see [PlaythroughFixtureTest], which both verifies the committed file matches and
 * regenerates it on demand). Keeping the builder in code makes the artifact reproducible: anyone
 * can rebuild the exact same playthrough rather than hand-editing JSON.
 */
object PlaythroughFixtures {
    /** Canonical UC01 movement playthrough name (matches docs/PLAYTESTING.md + the playtest skill). */
    const val UC01_THRUST_NORTH: String = "uc01-thrust-north"

    /** UC03 jump-gate playthrough name: thrust the ship through a gate and across sectors. */
    const val UC03_JUMP: String = "uc03-jump"

    /** UC05 docking playthrough name: thrust into Alpha Station's dock range and dock. */
    const val UC05_DOCK: String = "uc05-dock"

    /** UC06 mining playthrough name: thrust into the alpha-belt and hold MINE until the hold fills. */
    const val UC06_MINE: String = "uc06-mine"

    /** UC07 low-fuel playthrough name: start near-empty and thrust until the tank crosses below the threshold. */
    const val UC07_LOW_FUEL: String = "uc07-low-fuel"

    /** UC08 trade playthrough name: start docked at Alpha Station with cargo and sell it for credits. */
    const val UC08_TRADE: String = "uc08-trade"

    /**
     * UC09 outfitting playthrough name: start docked at Alpha Station with credits, buy + install an
     * engine upgrade (active ship's max speed rises), buy a second ship, and switch the active ship.
     */
    const val UC09_OUTFIT: String = "uc09-outfit"

    /**
     * UC10 scanning playthrough name: in flight at the sector-centre scan point, run a single active
     * scan that reveals the in-range hidden derelict while the out-of-range ghost stays hidden.
     */
    const val UC10_SCAN: String = "uc10-scan"

    /**
     * Starting credit balance the UC09 outfit fixture seeds — comfortably above the engine-tune-i
     * (300) + Swift hull (1800) total so both purchases clear. Authored as a literal so the fixture
     * stays self-contained.
     */
    const val UC09_STARTING_CREDITS: Long = 5000L

    /**
     * Starting credit balance the UC08 trade fixture seeds (mirrors the new-game wallet seed). Authored
     * here as a literal so the fixture stays self-contained — it does not depend on the production
     * bootstrap constant — and so the sale's effect is asserted against a known non-zero baseline.
     */
    const val UC08_STARTING_CREDITS: Long = 500L

    /** Units of Titanium the UC08 fixture starts with and sells in one go. */
    const val UC08_TITANIUM_UNITS: Int = 6

    /**
     * Power tuning **pinned for the UC07 low-fuel fixture only** — a deliberately thirsty draw
     * (10 fuel-units/s while thrusting vs. the default 2) so a short, compact playthrough actually
     * burns the tank below the low-fuel threshold (the default burn rate would need hundreds of
     * ticks). Other fixtures keep the default [PowerParams]. Pinning per artifact (UC02 config-pin
     * rationale) means a later default-tuning change can't silently invalidate this replay.
     */
    private val UC07_THIRSTY_POWER: PowerParams = PowerParams(baseModuleDraw = 0.5f, thrustDraw = 9.5f)

    /** Fixed timestep used by the fixtures (60 Hz) — a *fixed* step, not a live frame delta. */
    const val DT_SECONDS: Float = 1f / 60f

    /**
     * Every committed fixture, name → builder, in a stable order. [PlaythroughFixtureTest] iterates
     * this map to (a) verify each committed JSON matches its builder and (b) regenerate them all on
     * demand, so adding a fixture here is all that's needed to bring it under the guard.
     */
    val ALL: Map<String, () -> Playthrough> =
        linkedMapOf(
            UC01_THRUST_NORTH to ::uc01ThrustNorth,
            UC03_JUMP to ::uc03Jump,
            UC05_DOCK to ::uc05Dock,
            UC06_MINE to ::uc06Mine,
            UC07_LOW_FUEL to ::uc07LowFuel,
            UC08_TRADE to ::uc08Trade,
            UC09_OUTFIT to ::uc09Outfit,
            UC10_SCAN to ::uc10Scan,
        )

    /**
     * UC01 movement scenario: thrust "north" (stick → +y) for 60 ticks, then coast (no input) for
     * 30 trailing ticks so drift decay is exercised. Seeded deterministically; starts at rest at
     * the origin. Total span: 90 ticks. This is the exact recipe documented in docs/PLAYTESTING.md.
     */
    fun uc01ThrustNorth(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC01_THRUST_NORTH,
                seed = 1L,
                dtSeconds = DT_SECONDS,
                initialState = SimulationState(),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        for (tick in 0 until 60) {
            recorder.recordMovement(tick, north)
        }
        // 30 trailing no-input (drift) ticks: ticks 60..89 ⇒ tickCount 90.
        recorder.extendToTick(89)
        return recorder.build()
    }

    /**
     * UC03 jump scenario (AC#3/#9/#10): the ship starts in the [MvpSectorMap.START_SECTOR] (Alpha)
     * already cruising east at max speed, just short of the `alpha-to-beta` gate's trigger circle,
     * and thrusts straight east into it. Within ~20 ticks it crosses the trigger radius and jumps to
     * Beta, arriving offset from the `beta-to-alpha` gate (anti-bounce-back) with its eastward
     * momentum intact. Thrust continues for the full 30 ticks so the post-jump ticks prove the ship
     * doesn't immediately re-trigger a jump back.
     *
     * Starting mid-flight (rather than from rest at the origin) keeps the artifact small while still
     * genuinely *thrusting* into the gate. The geometry is read from the production [MvpSectorMap],
     * so the fixture tracks the real map.
     */
    fun uc03Jump(): Playthrough {
        // The alpha→beta gate sits on the +x axis; start just inside the content area, east-bound, a
        // little short of its trigger circle so a few ticks of eastward thrust carry the ship in.
        val recorder =
            PlaythroughRecorder(
                name = UC03_JUMP,
                seed = 3L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        fleet =
                            singleShipFleet(
                                kinematics =
                                    ShipKinematics(
                                        position = Vec2(1180f, 0f),
                                        velocity = Vec2(120f, 0f),
                                        headingRadians = 0f,
                                    ),
                            ),
                        // currentSector defaults to MvpSectorMap.START_SECTOR (alpha).
                    ),
            )
        val east = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 1f, released = false)
        for (tick in 0 until 30) {
            recorder.recordMovement(tick, east)
        }
        return recorder.build()
    }

    /**
     * UC05 dock scenario (AC#6): the ship starts in [MvpSectorMap.START_SECTOR] (Alpha) just south
     * of Alpha Station (at `(0, 600)`, dock radius 100) already cruising north at max speed, and
     * thrusts straight north into the station's dock circle. After ~15 ticks it is comfortably inside
     * the dock range, at which point an explicit [DockAction.DOCK] docks the ship; the remaining
     * "held" ticks carry no input, so — being docked — the ship is **frozen** (no drift, no bounce),
     * proving the dock state both engages and freezes movement. The geometry is read from the
     * production [MvpSectorMap], so the fixture tracks the real map.
     */
    fun uc05Dock(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC05_DOCK,
                seed = 5L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        fleet =
                            singleShipFleet(
                                kinematics =
                                    ShipKinematics(
                                        position = Vec2(0f, 480f),
                                        velocity = Vec2(0f, 120f),
                                        headingRadians = (Math.PI / 2).toFloat(),
                                    ),
                            ),
                        // currentSector defaults to MvpSectorMap.START_SECTOR (alpha).
                    ),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        // Thrust north into Alpha Station's (0,600) r100 dock circle.
        for (tick in 0 until 15) {
            recorder.recordMovement(tick, north)
        }
        // In range now: issue the explicit dock action (proximity + action, never auto-dock).
        recorder.recordDockAction(15, DockAction.DOCK)
        // Hold: ticks 16..20 carry no input, so the docked ship stays frozen (no movement/bounce).
        recorder.extendToTick(20)
        return recorder.build()
    }

    /**
     * UC06 mining scenario (AC#2/#3/#4/#5/#7): the ship starts in [MvpSectorMap.START_SECTOR] (Alpha)
     * just south of the authored `alpha-belt` field (centre `(-600, -400)`, mining radius 100),
     * already cruising north at max speed, and thrusts straight north into the field's mining circle
     * while **holding MINE the whole time**. Holding MINE out of range is a no-op (proximity gating),
     * so extraction auto-starts once the ship is in range and continues every tick until the hold is
     * full.
     *
     * The field's authored deposits total 70 units (Hydrogen 20, Water-Ice 15, Iron-Ore 25, Copper
     * 10) — more than the starter hold's [com.orbitalfrontier.economy.Cargo.DEFAULT_CAPACITY] of 50 —
     * so mining to a full hold (50 units, extracted in [com.orbitalfrontier.economy.ResourceType]
     * ordinal order: all 20 Hydrogen, all 15 Water-Ice, then 15 of the Iron-Ore) leaves the field
     * **partially depleted** (20 units remain), exercising both the capacity stop (AC#3) and field
     * depletion (AC#4). At the default 2 units/tick the fill takes 25 mining ticks; the 40-tick span
     * leaves comfortable margin to enter range first. Geometry is read from the production
     * [MvpSectorMap], so the fixture tracks the real map.
     */
    fun uc06Mine(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC06_MINE,
                seed = 6L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        fleet =
                            singleShipFleet(
                                kinematics =
                                    ShipKinematics(
                                        // x aligned with the field centre; ~110 wu south of it (just out of
                                        // the radius-100 circle) so a few ticks of northward thrust enter it.
                                        position = Vec2(-600f, -510f),
                                        velocity = Vec2(0f, 120f),
                                        headingRadians = (Math.PI / 2).toFloat(),
                                    ),
                            ),
                        // currentSector defaults to MvpSectorMap.START_SECTOR (alpha).
                    ),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        // Thrust north into the alpha-belt while holding MINE; extraction auto-starts once in range
        // and runs until the hold fills (25 mining ticks at the default 2 units/tick).
        for (tick in 0 until 40) {
            recorder.recordMovement(tick, north)
            recorder.recordMineAction(tick, MineAction.MINE)
        }
        return recorder.build()
    }

    /**
     * UC07 low-fuel scenario (AC#3/#7): the ship starts at rest at the origin in
     * [MvpSectorMap.START_SECTOR] (Alpha) with a **nearly-empty tank** — `Fuel(level = 24, capacity =
     * 100)`, i.e. fraction 0.24, just above the 0.20 low-fuel threshold — and thrusts straight north
     * for 90 ticks while the pinned thirsty power draw ([UC07_THIRSTY_POWER], 10 units/s thrusting)
     * burns it down. The tank crosses **below** the threshold within ~24 ticks and ends at ~9 units
     * (fraction ~0.09): low enough that [Fuel.speedFactor] scales the max-speed cap well below 1.0
     * (reduced effective max speed, AC#3) yet **never empty** (nonzero remaining fuel — never
     * stranded, AC#3).
     *
     * Replayed at this low starting fuel the ship's terminal speed is capped below its full-fuel
     * terminal speed; [Uc07FuelReplayTest] proves that by replaying the *same* input script at a full
     * tank (the control) and asserting the low-fuel terminal speed is strictly slower while remaining
     * fuel stays positive. North thrust from the origin keeps the ship clear of any gate/station/field
     * for the whole run (it travels only a few dozen world-units before the speed penalty bites).
     */
    fun uc07LowFuel(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC07_LOW_FUEL,
                seed = 7L,
                dtSeconds = DT_SECONDS,
                powerConfig = UC07_THIRSTY_POWER,
                initialState =
                    SimulationState(
                        // At rest at the origin, with a near-empty tank just above the 0.20 threshold
                        // so the burn crosses it during the run.
                        fleet = singleShipFleet(fuel = Fuel(level = 24f, capacity = 100f)),
                    ),
            )
        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        // Thrust north the whole time: the tank crosses below the low-fuel threshold and the speed cap
        // ramps down with it, while the tank never empties (nonzero remaining fuel at the end).
        for (tick in 0 until 90) {
            recorder.recordMovement(tick, north)
        }
        return recorder.build()
    }

    /**
     * UC08 trade scenario (AC#1/#7): the ship starts **docked at Alpha Station** in
     * [MvpSectorMap.START_SECTOR] (Alpha) carrying [UC08_TITANIUM_UNITS] units of Titanium and a
     * [UC08_STARTING_CREDITS] wallet. At tick 0 it sells all its Titanium to the station; Alpha pays
     * 50/unit for Titanium (the authored high sell on the map), so the wallet rises by
     * `units * sellPrice` and the hold empties of Titanium. The remaining held ticks carry no trade
     * event, so — being docked and idle — credits and cargo stay put (the docked freeze, now covering
     * the wallet too).
     *
     * Starting docked (rather than flying in and docking) keeps the artifact tiny while still exercising
     * the full trade path: the docked-station market is looked up from the production [MvpSectorMap], the
     * pure [com.orbitalfrontier.economy.Trading] resolves the sale, and the resulting credits + cargo
     * thread into the snapshot the save layer persists. [Uc08TradeReplayTest] asserts the exact credit
     * gain and cargo drop; the fixture tracks the real map's prices.
     */
    fun uc08Trade(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC08_TRADE,
                seed = 8L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        // Docked at Alpha Station so the trade desk's authored market resolves; at rest,
                        // in the start sector (currentSector defaults to MvpSectorMap.START_SECTOR / alpha).
                        // A hold of sellable Titanium — Alpha pays the map's highest sell (50/unit) for it.
                        fleet =
                            singleShipFleet(
                                cargo = Cargo(mapOf(ResourceType.TITANIUM to UC08_TITANIUM_UNITS), Cargo.DEFAULT_CAPACITY),
                            ),
                        dockedStation = PoiId("alpha-station"),
                        // A non-zero starting wallet so the sale's gain is measured from a known baseline.
                        credits = UC08_STARTING_CREDITS,
                    ),
            )
        // Tick 0: sell all the Titanium to Alpha Station. The docked freeze resolves the trade once; the
        // trailing held ticks (1..3) carry no trade event, proving credits + cargo stay put while docked.
        recorder.recordTrade(0, TradeKind.SELL, ResourceType.TITANIUM, UC08_TITANIUM_UNITS)
        recorder.extendToTick(3)
        return recorder.build()
    }

    /**
     * UC09 outfitting scenario (AC#8): the ship starts **docked at Alpha Station** in
     * [MvpSectorMap.START_SECTOR] (Alpha) — a DEALER whose outfit desk stocks `engine-tune-i` and whose
     * shipyard sells the Swift — with a single starter ship and a [UC09_STARTING_CREDITS] wallet. The
     * docked composition (refuel→trade→outfit→fleet) resolves one order per tick:
     *  - tick 0: **BuyInstall(engine-tune-i)** — installs the engine into the active starter ship, so
     *    its effective max speed rises (the engine delta is added by [com.orbitalfrontier.outfit.ShipStats]);
     *  - tick 1: **BuyShip(Swift)** — adds a second ship (id 1) to the fleet for 1800 credits;
     *  - tick 2: **SwitchActive(ship 1)** — makes the Swift the active ship (activeShipId changes 0→1).
     *
     * The single trailing held tick proves the docked state stays put with no further orders. Starting
     * docked keeps the artifact tiny while exercising the full outfit + fleet path against the real
     * [MvpSectorMap] data; [Uc09OutfitReplayTest] asserts the speed rise and the active-ship change.
     */
    fun uc09Outfit(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC09_OUTFIT,
                seed = 9L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        // Docked at Alpha Station (its outfit desk + shipyard resolve from MvpSectorMap),
                        // a single starter ship at rest, with a wallet that covers both purchases.
                        fleet = singleShipFleet(),
                        dockedStation = PoiId("alpha-station"),
                        credits = UC09_STARTING_CREDITS,
                    ),
            )
        // One docked order per tick so each effect is isolated and replay-verifiable.
        recorder.recordOutfit(0, OutfitOrder.BuyInstall(UpgradeCatalog.ENGINE_TUNE_I))
        recorder.recordFleet(1, FleetOrder.BuyShip(ShipRoster.SWIFT.id))
        recorder.recordFleet(2, FleetOrder.SwitchActive(ShipId(1)))
        // One trailing held tick: docked + no order ⇒ everything stays put.
        recorder.extendToTick(3)
        return recorder.build()
    }

    /**
     * UC10 scanning scenario (AC#3/#4/#6): the ship starts **in flight** (not docked) at rest at
     * [MvpSectorMap.SCAN_POINT] — the Alpha sector centre, the canonical scan reference point the three
     * hidden contacts are spaced against — with a single starter ship (base sensor range 500 wu). At
     * tick 0 it runs a single active [ScanAction.SCAN]: that reveals `alpha-derelict` (d=300, inside the
     * base range) but **not** `alpha-smuggler` (d=600, only the SCANNER_I-upgraded range reaches it) and
     * **not** `alpha-ghost` (d=800, outside even the upgraded range). The two trailing held ticks carry
     * no scan event, so the revealed set stays put — proving reveal is monotonic (a revealed contact
     * does not re-hide, AC#4).
     *
     * The scan point sits far from every gate/station/field, so the at-rest ship never jumps, docks, or
     * mines — the run isolates scanning. Geometry is read from the production [MvpSectorMap], so the
     * fixture tracks the real map; [Uc10ScanReplayTest] asserts the derelict becomes known, the ghost
     * stays hidden, and the replay is deterministic.
     */
    fun uc10Scan(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC10_SCAN,
                seed = 10L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        // In flight (dockedStation null), at rest at the sector-centre scan point; a single
                        // starter ship (currentSector defaults to MvpSectorMap.START_SECTOR / alpha).
                        fleet =
                            singleShipFleet(
                                kinematics = ShipKinematics(position = MvpSectorMap.SCAN_POINT),
                            ),
                    ),
            )
        // Tick 0: a single active scan reveals the in-range derelict; the trailing held ticks (1..2)
        // carry no scan event, proving the revealed set persists (monotonic, AC#4).
        recorder.recordScanAction(0, ScanAction.SCAN)
        recorder.extendToTick(2)
        return recorder.build()
    }
}
