package com.orbitalfrontier.playthrough

import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.FireAction
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.TradeKind
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.sim.SimulationState
import com.orbitalfrontier.station.StationBuildOrder
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.ScanAction
import com.orbitalfrontier.world.SectorId
import kotlin.math.atan2

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
     * UC11 crew playthrough name: start docked at Alpha Station (a crew-hiring station) with crew=0 and
     * a small wallet, hire one crew, and assert the crew count rises and the turret-operability flag flips.
     */
    const val UC11_CREW: String = "uc11-crew"

    /**
     * UC12 mission playthrough name: start docked at Alpha Station, accept the board mining mission, fly
     * to the alpha-belt and mine the Hydrogen quota, fly back and dock, then turn the mission in for the
     * credit reward (AC#7).
     */
    const val UC12_MISSION: String = "uc12-mission"

    /**
     * UC13 combat playthrough name (AC#8): fly from outside into the alpha-raider-picket zone to spawn a
     * RAIDER, then hold FIRE while loitering until the auto-aim turret destroys it; assert the hostile is
     * gone and the sectional damage taken is recorded.
     */
    const val UC13_COMBAT: String = "uc13-combat"

    /**
     * UC14 factions playthrough name (AC#6): start docked at Alpha (a LEAGUE station) with neutral
     * reputation, complete the board faction mining mission, and end with +10 LEAGUE reputation — enough
     * to unlock Alpha's gated `:premium` offer. [Uc14FactionReplayTest] asserts the premium is ABSENT at
     * rep 0, PRESENT after the turn-in, and that the standing rose by exactly the mission-complete delta.
     */
    const val UC14_FACTION: String = "uc14-faction"

    /**
     * UC15 station-building playthrough name (AC#6): start docked at Alpha Station (the one build-capable
     * MVP station) with credits + mined resources, found a `commerce-hub-i` station in one tick, and end
     * owning a single station whose [com.orbitalfrontier.station.OwnedStation.availableFunctions] expose
     * COMMERCE. [Uc15StationReplayTest] asserts ownership (AC#1/#3) + the COMMERCE function (AC#2/#6) +
     * determinism. Loadable via `playtest -Dplaythrough.name=uc15-station`.
     */
    const val UC15_STATION: String = "uc15-station"

    /**
     * UC41 combat-bounty playthrough name (AC#5): start in flight just south of the authored
     * `bounty-alpha-raider` zone and **in radio range** of Alpha Station, accept the broadcast bounty
     * (UC12 radio), cross into the zone to spawn the contracted RAIDER, then hold FIRE until the turret
     * destroys it — completing the bounty and paying the reward. [Uc41CombatBountyReplayTest] asserts the
     * bounty ends COMPLETED with credits risen by exactly the reward, no reputation change (the UC43 seam),
     * and bit-for-bit determinism. Loadable via `playtest -Dplaythrough.name=uc41-combat-bounty`.
     */
    const val UC41_COMBAT_BOUNTY: String = "uc41-combat-bounty"

    /**
     * UC42 loot-&-salvage playthrough name (AC#5): fly into the natural `alpha-raider-picket` zone (NOT a
     * bounty zone, so any credit gain is attributable to salvage alone), destroy the ambushing RAIDER with
     * the auto-aim turret, then loiter so its dropped wreck is collected — credits to the wallet and ore into
     * cargo. [Uc42LootSalvageReplayTest] asserts a wreck spawned and was collected and that the final credits
     * and cargo equal the values [LootTable.roll] yields for that kill (no magic literals), plus bit-for-bit
     * determinism. Loadable via `playtest -Dplaythrough.name=uc42-loot-salvage`.
     */
    const val UC42_LOOT_SALVAGE: String = "uc42-loot-salvage"

    /**
     * UC43 combat-reputation playthrough name (AC#5): start **in flight in Gamma Verge** just west of the
     * authored `gamma-independent-marauder` zone (the Independents' home turf), fly in to spawn the
     * faction-affiliated [com.orbitalfrontier.combat.HostileArchetypes.INDEPENDENT_MARAUDER], then hold FIRE
     * until the auto-aim turret destroys it — souring the player's Independents standing through the
     * combat→reputation seam. [Uc43CombatReputationReplayTest] asserts a faction ship was destroyed, the
     * Independents standing dropped by exactly `combatKillDelta`, the drop **persists** across a snapshot
     * round-trip (AC#5), and the replay is bit-for-bit deterministic. Authored in **Gamma** because no other
     * committed fixture roams Gamma, so the new natural zone can never perturb an existing replay. Loadable
     * via `playtest -Dplaythrough.name=uc43-combat-reputation`.
     */
    const val UC43_COMBAT_REPUTATION: String = "uc43-combat-reputation"

    /**
     * UC45 richer-enemy-AI playthrough name (AC#5): start **in flight in Gamma Verge** just west of the
     * authored multi-archetype `gamma-marauder-pack` zone, fly in to spawn the pack (an
     * [com.orbitalfrontier.combat.HostileArchetypes.INDEPENDENT_MARAUDER] + a
     * [com.orbitalfrontier.combat.HostileArchetypes.REGROUP_MARAUDER] + a
     * [com.orbitalfrontier.combat.HostileArchetypes.PRECISION_RAIDER]), then hold FIRE while the crew-gated
     * turret grinds the pack down. Uses **multi-section** hit weights (NOT the HULL-only pin the other combat
     * fixtures use) so the retreat-and-regroup and weakest-section-targeting behaviours actually manifest in
     * the recording. [Uc45EnemyAiReplayTest] asserts a multi-hostile fight occurred, it cleared, the new AI
     * effects show up, and the replay is bit-for-bit deterministic. Loadable via
     * `playtest -Dplaythrough.name=uc45-enemy-ai`.
     */
    const val UC45_ENEMY_AI: String = "uc45-enemy-ai"

    /**
     * Starting credit balance the UC15 station fixture seeds — comfortably above the commerce-hub-i price
     * (1500) so the single FoundStation clears with a known non-zero remainder. Authored as a literal so the
     * fixture stays self-contained.
     */
    const val UC15_STARTING_CREDITS: Long = 2000L

    /**
     * Starting credit balance the UC11 crew fixture seeds — comfortably above one hire's cost
     * ([com.orbitalfrontier.crew.Hiring.HIRE_COST_PER_CREW] = 100) so the single hire clears and the
     * post-hire wallet is a known non-zero baseline. Authored as a literal so the fixture stays
     * self-contained.
     */
    const val UC11_STARTING_CREDITS: Long = 500L

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
            UC11_CREW to ::uc11Crew,
            UC12_MISSION to ::uc12Mission,
            UC13_COMBAT to ::uc13Combat,
            UC14_FACTION to ::uc14Faction,
            UC15_STATION to ::uc15Station,
            UC41_COMBAT_BOUNTY to ::uc41CombatBounty,
            UC42_LOOT_SALVAGE to ::uc42LootSalvage,
            UC43_COMBAT_REPUTATION to ::uc43CombatReputation,
            UC45_ENEMY_AI to ::uc45EnemyAi,
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

    /**
     * UC11 crew scenario (AC#6): the ship starts **docked at Alpha Station** in
     * [MvpSectorMap.START_SECTOR] (Alpha) — the one station that hires crew (`hiresCrew = true`) — with a
     * single starter ship (crew=0, base crew capacity 2) and a [UC11_STARTING_CREDITS] wallet. At tick 0
     * it hires **one** crew via the docked composition's final step (refuel→trade→outfit→fleet→**hire**):
     * the crew count rises 0→1, [UC11_STARTING_CREDITS] − [com.orbitalfrontier.crew.Hiring.HIRE_COST_PER_CREW]
     * credits remain, and — at the MVP turret crew requirement of 1 — the turret-operability flag flips
     * from inoperable to operable on that first hire.
     *
     * The two trailing held ticks carry no hire event, so the crew count stays put (the docked freeze now
     * covers crew too). Starting docked keeps the artifact tiny while exercising the full hire path against
     * the real [MvpSectorMap] crew-hiring flag; [Uc11CrewReplayTest] asserts the crew count, the operability
     * flip, and replay determinism.
     */
    fun uc11Crew(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC11_CREW,
                seed = 11L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        // Docked at Alpha Station (its crew desk resolves from MvpSectorMap.hiresCrew), a single
                        // starter ship at rest with crew=0, and a wallet that covers one hire.
                        fleet = singleShipFleet(),
                        dockedStation = PoiId("alpha-station"),
                        credits = UC11_STARTING_CREDITS,
                    ),
            )
        // Tick 0: hire one crew. The trailing held ticks (1..2) carry no hire event, proving crew + credits
        // stay put while docked (the docked freeze covers crew).
        recorder.recordHire(0, 1)
        recorder.extendToTick(2)
        return recorder.build()
    }

    /** The board mining mission accepted + turned in by the UC12 fixture (the golden 8-Hydrogen offer). */
    private val UC12_BOARD_MINING: MissionId = MissionId("board:alpha-station:mining")

    /** Alpha Station's dock position (read from the production map) — the fixture's start + turn-in point. */
    private val UC12_STATION: Vec2 = Vec2(0f, 600f)

    /** The alpha-belt field centre (read from the production map) — where the Hydrogen quota is mined. */
    private val UC12_BELT: Vec2 = Vec2(-600f, -400f)

    /**
     * A deliberately brisk movement profile **pinned for the UC12 mission fixture only** so the full
     * dock → fly-out → mine → fly-back → dock round trip (≈ 2 × 1166 wu between Alpha Station and the
     * alpha-belt) fits in a compact artifact instead of the ~1200 ticks the default 120 wu/s cruise would
     * need. Snappy rotation keeps the out-and-back essentially 1-D along the station↔belt axis, so the
     * ship passes cleanly through the belt's and the station's 100 wu circles. Pinning per artifact (the
     * UC02 config-pin rationale) means a later default-tuning change can't silently invalidate this replay.
     */
    private val UC12_FAST_FLIGHT: ShipMovementParams =
        ShipMovementParams(
            maxSpeed = 600f,
            maxAcceleration = 6000f,
            rotationAcceleration = 50f,
            maxRotationSpeed = 8f,
        )

    /**
     * UC12 mission scenario (AC#7): the full accept → mine → turn-in loop of the board mining mission.
     *
     * The ship starts **docked at Alpha Station** in [MvpSectorMap.START_SECTOR] (Alpha), already facing
     * the alpha-belt, with an empty hold and a zero wallet. The script:
     *  - **tick 0** (docked): [MissionOrder.Accept] the golden board mining offer
     *    (`board:alpha-station:mining` — 8 HYDROGEN for 400 credits); the ship is frozen, so the accept
     *    resolves against the docked station's board.
     *  - **outbound leg**: undock and thrust straight toward the alpha-belt while **holding MINE**.
     *    Mining is proximity-gated, so it auto-starts on entering the field's 100 wu circle and gathers
     *    Hydrogen (extracted first, in [com.orbitalfrontier.economy.ResourceType] ordinal order) well past
     *    the 8-unit quota.
     *  - **return leg**: thrust back toward Alpha Station while **holding DOCK and re-issuing TurnIn** —
     *    DOCK is proximity-gated (docks on re-entering the station circle) and the TurnIn no-ops until the
     *    ship is docked with the quota in the hold, at which point it completes the mission and pays out.
     *
     * [Uc12MissionReplayTest] asserts the mission ends COMPLETED with credits risen by exactly 400 and the
     * Hydrogen quota consumed, and that the replay is deterministic. The flight uses the pinned
     * [UC12_FAST_FLIGHT] profile; all geometry is read from the production [MvpSectorMap].
     */
    fun uc12Mission(): Playthrough {
        val outbound = UC12_BELT - UC12_STATION // station -> belt
        val inbound = UC12_STATION - UC12_BELT // belt -> station
        val recorder =
            PlaythroughRecorder(
                name = UC12_MISSION,
                seed = 12L,
                dtSeconds = DT_SECONDS,
                config = UC12_FAST_FLIGHT,
                initialState =
                    SimulationState(
                        fleet =
                            singleShipFleet(
                                kinematics =
                                    ShipKinematics(
                                        position = UC12_STATION,
                                        // Already facing the belt so the outbound leg needs no initial turn.
                                        headingRadians = atan2(outbound.y, outbound.x),
                                    ),
                            ),
                        dockedStation = PoiId("alpha-station"),
                        credits = 0L,
                    ),
            )

        // Tick 0 (docked): accept the board mining mission. The ship is frozen this tick.
        recorder.recordMission(0, MissionOrder.Accept(UC12_BOARD_MINING))

        // Outbound leg: undock on the first flight tick, then thrust toward the belt holding MINE.
        val outThrust = MovementInput(targetDirection = outbound, magnitude = 1f, released = false)
        val outboundTicks = UC12_OUTBOUND_TICKS
        for (i in 0 until outboundTicks) {
            val tick = 1 + i
            recorder.recordMovement(tick, outThrust)
            recorder.recordMineAction(tick, MineAction.MINE)
            if (i == 0) recorder.recordDockAction(tick, DockAction.UNDOCK)
        }

        // Return leg: thrust back toward the station, holding DOCK + re-issuing TurnIn every tick so the
        // mission completes on the first docked tick (both are no-ops until the ship is actually in range
        // / docked, so the exact arrival tick need not be hand-computed).
        val backThrust = MovementInput(targetDirection = inbound, magnitude = 1f, released = false)
        val returnStart = 1 + outboundTicks
        for (i in 0 until UC12_RETURN_TICKS) {
            val tick = returnStart + i
            recorder.recordMovement(tick, backThrust)
            recorder.recordDockAction(tick, DockAction.DOCK)
            recorder.recordMission(tick, MissionOrder.TurnIn(UC12_BOARD_MINING))
        }
        return recorder.build()
    }

    /**
     * UC14 factions & reputation scenario (AC#6) — the same dock → mine → turn-in round trip as
     * [uc12Mission], but recorded as the dedicated faction artifact: the accepted board mining mission is
     * a LEAGUE contract (Alpha is a LEAGUE station, so [com.orbitalfrontier.mission.MissionGenerator]
     * stamps `factionId = league` on it), so completing it grants +10 LEAGUE reputation — clearing the
     * `board:alpha-station:premium` offer's threshold of 10.
     *
     * The ship starts **docked at Alpha Station** with neutral reputation ([Reputation.EMPTY]) and a zero
     * wallet, already facing the alpha-belt. The script (identical recipe to UC12, which is proven to mine
     * the 8-Hydrogen quota and dock back to turn in):
     *  - **tick 0** (docked): accept `board:alpha-station:mining`.
     *  - **outbound**: undock and thrust to the alpha-belt holding MINE (gathers Hydrogen past the quota).
     *  - **return**: thrust back holding DOCK + re-issuing TurnIn until docked, which completes the mission,
     *    pays the 400-credit reward, and grants +10 LEAGUE reputation.
     *
     * [Uc14FactionReplayTest] asserts the AC#6 contract end-to-end: the premium offer is absent at rep 0,
     * the standing rises by exactly the mission-complete delta (+10), and the premium then surfaces — plus
     * determinism. Geometry + flight profile are reused from the UC12 mission fixture (the same constants).
     */
    fun uc14Faction(): Playthrough {
        val outbound = UC12_BELT - UC12_STATION // station -> belt
        val inbound = UC12_STATION - UC12_BELT // belt -> station
        val recorder =
            PlaythroughRecorder(
                name = UC14_FACTION,
                seed = 14L,
                dtSeconds = DT_SECONDS,
                config = UC12_FAST_FLIGHT,
                initialState =
                    SimulationState(
                        fleet =
                            singleShipFleet(
                                kinematics =
                                    ShipKinematics(
                                        position = UC12_STATION,
                                        headingRadians = atan2(outbound.y, outbound.x),
                                    ),
                            ),
                        dockedStation = PoiId("alpha-station"),
                        credits = 0L,
                        // Start neutral — the AC#6 contract is that the run EARNS the reputation that
                        // unlocks the premium offer (it isn't seeded with it).
                        reputation = Reputation.EMPTY,
                    ),
            )

        // Tick 0 (docked): accept the LEAGUE board mining mission. The ship is frozen this tick.
        recorder.recordMission(0, MissionOrder.Accept(UC12_BOARD_MINING))

        // Outbound leg: undock on the first flight tick, then thrust toward the belt holding MINE.
        val outThrust = MovementInput(targetDirection = outbound, magnitude = 1f, released = false)
        for (i in 0 until UC12_OUTBOUND_TICKS) {
            val tick = 1 + i
            recorder.recordMovement(tick, outThrust)
            recorder.recordMineAction(tick, MineAction.MINE)
            if (i == 0) recorder.recordDockAction(tick, DockAction.UNDOCK)
        }

        // Return leg: thrust back, holding DOCK + re-issuing TurnIn each tick so the mission completes on
        // the first docked tick (both no-op until in range / docked, so no exact tick need be computed).
        val backThrust = MovementInput(targetDirection = inbound, magnitude = 1f, released = false)
        val returnStart = 1 + UC12_OUTBOUND_TICKS
        for (i in 0 until UC12_RETURN_TICKS) {
            val tick = returnStart + i
            recorder.recordMovement(tick, backThrust)
            recorder.recordDockAction(tick, DockAction.DOCK)
            recorder.recordMission(tick, MissionOrder.TurnIn(UC12_BOARD_MINING))
        }
        return recorder.build()
    }

    /**
     * UC15 station-building scenario (AC#6): the player starts **docked at Alpha Station** — the one MVP
     * station whose `buildsStations` flag is set — in [MvpSectorMap.START_SECTOR] (Alpha), with
     * [UC15_STARTING_CREDITS] credits and a hold carrying more than the commerce hub's resource bill
     * (IRON_ORE:15 + SILICON:8). The script is a single tick:
     *  - **tick 0** (docked): found a `commerce-hub-i` station ([StationBuildOrder.FoundStation]). Resolved
     *    as the last docked step, this deducts 1500 credits + {IRON_ORE:15, SILICON:8}, allocates station id
     *    0, anchors it in Alpha, and snaps the commerce hub into slot 0.
     *
     * [Uc15StationReplayTest] asserts the AC#6 contract: after the replay the player owns exactly one
     * station (AC#1/#3) whose [com.orbitalfrontier.station.OwnedStation.availableFunctions] contains COMMERCE
     * (AC#2/#6), plus bit-for-bit determinism. The ship is seeded at Alpha Station's authored position so the
     * snapshot is faithful, though a docked ship is frozen regardless. Loadable via
     * `playtest -Dplaythrough.name=uc15-station`.
     */
    fun uc15Station(): Playthrough {
        val recorder =
            PlaythroughRecorder(
                name = UC15_STATION,
                seed = 15L,
                dtSeconds = DT_SECONDS,
                initialState =
                    SimulationState(
                        fleet =
                            singleShipFleet(
                                // Alpha Station's authored position
                                kinematics = ShipKinematics(position = Vec2(0f, 600f)),
                                cargo =
                                    Cargo(
                                        mapOf(ResourceType.IRON_ORE to 20, ResourceType.SILICON to 12),
                                        Cargo.DEFAULT_CAPACITY,
                                    ),
                            ),
                        dockedStation = PoiId("alpha-station"),
                        credits = UC15_STARTING_CREDITS,
                    ),
            )

        // Tick 0 (docked at the build-capable Alpha Station): found a commerce-hub-i station. The build
        // resolves as the last docked step, deducting the cost and adding the owned station (id 0, anchored
        // in Alpha, commerce hub in slot 0).
        recorder.recordStationBuild(0, StationBuildOrder.FoundStation(StationModuleCatalog.COMMERCE_HUB))
        return recorder.build()
    }

    /** Outbound (station → belt) thrust ticks for [uc12Mission] — enough to reach + dwell in the belt. */
    private const val UC12_OUTBOUND_TICKS: Int = 140

    /** Return (belt → station) thrust ticks for [uc12Mission] — enough to fly back, dock, and turn in. */
    private const val UC12_RETURN_TICKS: Int = 165

    /** The alpha-raider-picket zone centre (read from the production map) — the AC#8 ambush point. */
    private val UC13_PICKET_CENTER: Vec2 = Vec2(900f, 0f)

    /**
     * Combat tuning **pinned for the UC13 combat fixture only** so the AC#8 artifact is compact and its
     * outcome deterministic: every hit is funnelled to the HULL (so the RAIDER's 30-HP hull falls in a
     * fixed number of turret hits rather than a stochastic spread across four sections), and the hit radius
     * is widened so the auto-aim turret's un-led shots connect reliably as the raider juke-closes. Pinning
     * per artifact (the UC02 config-pin rationale) means a later combat retune can't silently invalidate
     * this replay. The player damage taken is likewise funnelled to HULL, so "damage recorded" is a single
     * stable section.
     */
    private val UC13_COMBAT_TUNING: CombatParams =
        CombatParams(sectionHitWeights = mapOf(ShipSection.HULL to 1), hitRadius = 60f)

    /** Thrust-in ticks for [uc13Combat] — enough to cross the picket's r260 edge from just outside it. */
    private const val UC13_THRUST_IN_TICKS: Int = 12

    /** Total ticks for [uc13Combat] — the thrust-in plus a long loiter while the turret grinds the raider down. */
    private const val UC13_TOTAL_TICKS: Int = 440

    /**
     * UC13 combat scenario (AC#8): the player starts **in flight** just **west of** the authored
     * alpha-raider-picket zone (centre [UC13_PICKET_CENTER], radius 260) in [MvpSectorMap.START_SECTOR]
     * (Alpha), facing east, with **one crew aboard** (so the built-in auto-aim turret is operable, AC#2) and
     * the last-docked station recorded at Alpha (the respawn point, exercised by the save/reload test). The
     * script:
     *  - **thrust-in**: thrust east for [UC13_THRUST_IN_TICKS] ticks, crossing the picket's r260 edge — the
     *    edge-triggered [com.orbitalfrontier.combat.EncounterSpawner] ambushes the player with a single
     *    RAIDER (seeded by the crossing tick);
     *  - **loiter + fire**: release the stick and hold [FireAction.FIRE] for the rest of the run. The
     *    AGGRESSIVE raider closes to engage range; the crew-gated turret auto-aims and (with the pinned
     *    HULL-funnelled tuning) grinds its 30-HP hull down to destruction, while the raider's return fire
     *    records sectional damage on the player.
     *
     * [Uc13CombatReplayTest] asserts the encounter ends with the hostile **gone** (combat cleared to NONE,
     * a HostileDestroyed having fired), the player **alive** with **recorded** HULL damage, and that the
     * replay is bit-for-bit deterministic. The combat tuning is the pinned [UC13_COMBAT_TUNING]; geometry is
     * read from the production [MvpSectorMap]. Loadable via `playtest -Dplaythrough.name=uc13-combat`.
     */
    fun uc13Combat(): Playthrough {
        // Start just OUTSIDE the r260 picket on the -x side (dist 270), already cruising east at 120 wu/s so a
        // few ticks of eastward thrust carry the ship across the r260 edge and trip the ambush.
        val start = UC13_PICKET_CENTER - Vec2(270f, 0f)
        val fleet =
            singleShipFleet(
                kinematics = ShipKinematics(position = start, velocity = Vec2(120f, 0f), headingRadians = 0f),
            ).let { f -> f.withActive(f.active.withCrew(1)) }

        val recorder =
            PlaythroughRecorder(
                name = UC13_COMBAT,
                seed = 13L,
                dtSeconds = DT_SECONDS,
                combatConfig = UC13_COMBAT_TUNING,
                initialState =
                    SimulationState(
                        fleet = fleet,
                        // The respawn point on destruction (persisted); also lets the save/reload test assert it.
                        lastDockedStation = PoiId("alpha-station"),
                    ),
            )

        val east = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 1f, released = false)
        // Thrust east to cross the picket edge and spawn the raider; FIRE the whole time.
        for (tick in 0 until UC13_THRUST_IN_TICKS) {
            recorder.recordMovement(tick, east)
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        // Loiter (released stick ⇒ drift to near-rest inside the picket) and keep firing: the turret
        // auto-aims and destroys the closing raider over the remaining ticks.
        for (tick in UC13_THRUST_IN_TICKS until UC13_TOTAL_TICKS) {
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        return recorder.build()
    }

    /** The canonical bounty offer id Alpha Station posts (UC41) — `"bounty:<targetZoneId>"`. */
    private val UC41_BOUNTY_ID: MissionId = MissionId("bounty:bounty-alpha-raider")

    /** The authored `bounty-alpha-raider` target zone (read from the production map) — the AC#5 kill site. */
    private val UC41_BOUNTY_ZONE = MvpSectorMap.bountyTargetZone("bounty-alpha-raider")!!

    /**
     * Combat tuning **pinned for the UC41 bounty fixture only** — the same HULL-funnelled, wide-hit-radius
     * recipe [UC13_COMBAT_TUNING] uses so the contracted RAIDER's 30-HP hull falls in a fixed number of
     * turret hits (a compact, deterministic kill) instead of a stochastic spread across four sections.
     * Pinning per artifact (the UC02 config-pin rationale) means a later combat retune can't silently
     * invalidate this replay.
     */
    private val UC41_COMBAT_TUNING: CombatParams =
        CombatParams(sectionHitWeights = mapOf(ShipSection.HULL to 1), hitRadius = 60f)

    /** Thrust-in ticks for [uc41CombatBounty] — enough to cross the bounty zone's r220 edge from just outside. */
    private const val UC41_THRUST_IN_TICKS: Int = 24

    /** Total ticks for [uc41CombatBounty] — the thrust-in plus a long loiter while the turret grinds the raider down. */
    private const val UC41_TOTAL_TICKS: Int = 520

    /**
     * UC41 combat-bounty scenario (AC#5): the full accept → spawn → destroy → payout loop of the combat-bounty
     * mission type, recorded as a deterministic playthrough.
     *
     * The player starts **in flight** just **south of** the authored `bounty-alpha-raider` zone (centre
     * [UC41_BOUNTY_ZONE].center `(0, 1400)`, radius 220) in [MvpSectorMap.START_SECTOR] (Alpha), cruising
     * north at max speed, **in radio range** of Alpha Station (≈530 wu < the 700-wu default radio reach), with
     * **one crew aboard** (so the built-in auto-aim turret is operable, AC#2) and the last-docked station
     * recorded at Alpha (the respawn point). The script:
     *  - **tick 0**: [MissionOrder.Accept] the broadcast bounty offer (`bounty:bounty-alpha-raider`, kill 1) over
     *    the ship radio (UC12 radio path) while still outside the zone — the bounty becomes ACTIVE (AC#1).
     *  - **thrust-in**: thrust north for [UC41_THRUST_IN_TICKS] ticks, crossing the zone's r220 edge — the
     *    edge-triggered [com.orbitalfrontier.combat.EncounterSpawner.missionSpawn] injects the contracted RAIDER
     *    seeded by the crossing tick (AC#2);
     *  - **loiter + fire**: release the stick and hold [FireAction.FIRE] for the rest of the run. The AGGRESSIVE
     *    raider closes; the crew-gated turret auto-aims and (with the pinned HULL-funnelled tuning) grinds its
     *    30-HP hull to destruction, which auto-completes the bounty and pays the reward (AC#3).
     *
     * [Uc41CombatBountyReplayTest] asserts the AC#5 contract: a fight occurred, the bounty ends COMPLETED with
     * `killProgress == killTarget`, credits rose by exactly the reward, reputation is unchanged (the UC43 seam),
     * and the replay is bit-for-bit deterministic. The combat tuning is the pinned [UC41_COMBAT_TUNING]; all
     * geometry is read from the production [MvpSectorMap]. Loadable via `playtest -Dplaythrough.name=uc41-combat-bounty`.
     */
    fun uc41CombatBounty(): Playthrough {
        // Start just OUTSIDE the r220 bounty zone on the -y (south) side, already cruising north at 120 wu/s so a
        // few ticks of northward thrust carry the ship across the r220 edge and trip the bounty spawn. The start
        // is ≈530 wu from Alpha Station — inside the 700-wu radio reach — so the bounty surfaces on the radio.
        val start = UC41_BOUNTY_ZONE.center - Vec2(0f, UC41_BOUNTY_ZONE.radius + 50f)
        val fleet =
            singleShipFleet(
                kinematics = ShipKinematics(position = start, velocity = Vec2(0f, 120f), headingRadians = (Math.PI / 2).toFloat()),
            ).let { f -> f.withActive(f.active.withCrew(1)) }

        val recorder =
            PlaythroughRecorder(
                name = UC41_COMBAT_BOUNTY,
                seed = 41L,
                dtSeconds = DT_SECONDS,
                combatConfig = UC41_COMBAT_TUNING,
                initialState =
                    SimulationState(
                        fleet = fleet,
                        // Start broke so the bounty payout is measured from a known-zero baseline.
                        credits = 0L,
                        // Start neutral — the AC asserts the bounty grants NO reputation (the UC43 seam).
                        reputation = Reputation.EMPTY,
                        // The respawn point on destruction (persisted); also the bounty's issuing station.
                        lastDockedStation = PoiId("alpha-station"),
                    ),
            )

        // Tick 0 (in flight, in radio range, still outside the zone): accept the broadcast bounty.
        recorder.recordMission(0, MissionOrder.Accept(UC41_BOUNTY_ID))

        val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
        // Thrust north to cross the zone edge and spawn the contracted raider; FIRE the whole time.
        for (tick in 0 until UC41_THRUST_IN_TICKS) {
            recorder.recordMovement(tick, north)
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        // Loiter (released stick ⇒ drift to near-rest inside the zone) and keep firing: the turret auto-aims and
        // destroys the closing raider over the remaining ticks, completing the bounty and paying the reward.
        for (tick in UC41_THRUST_IN_TICKS until UC41_TOTAL_TICKS) {
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        return recorder.build()
    }

    /**
     * Combat tuning **pinned for the UC42 loot-&-salvage fixture only**. It reuses the UC13 HULL-funnelled,
     * wide-hit-radius recipe (so the RAIDER's 30-HP hull falls in a fixed number of turret hits — a compact,
     * deterministic kill), and additionally pins a **generous [CombatParams.salvagePickupRadius]** so that
     * wherever inside the picket the raider is destroyed, the loitering player is within pickup range and the
     * wreck is reliably collected within the recorded run. Pinning per artifact (the UC02 config-pin
     * rationale) means a later default-radius retune can't silently invalidate this replay. The loot *table*
     * values are deliberately NOT pinned (archetype-stat precedent): a loot retune regenerates this fixture,
     * exactly as an archetype-HP retune already would.
     */
    private val UC42_COMBAT_TUNING: CombatParams =
        CombatParams(sectionHitWeights = mapOf(ShipSection.HULL to 1), hitRadius = 60f, salvagePickupRadius = 600f)

    /** Thrust-in ticks for [uc42LootSalvage] — enough to cross the picket's r260 edge from just outside it. */
    private const val UC42_THRUST_IN_TICKS: Int = 12

    /** Total ticks for [uc42LootSalvage] — the thrust-in plus a long loiter while the turret kills, then collect. */
    private const val UC42_TOTAL_TICKS: Int = 440

    /**
     * UC42 loot-&-salvage scenario (AC#5): the player flies into the natural `alpha-raider-picket` zone,
     * destroys the ambushing RAIDER, and the wreck it drops (UC42 AC#1) is collected (AC#2) — credits to the
     * wallet and ore into cargo.
     *
     * Mirrors [uc13Combat]'s geometry exactly (the production [MvpSectorMap.ENCOUNTER_ZONES] natural zone,
     * centre [UC13_PICKET_CENTER], radius 260) — a **natural** zone, NOT a bounty zone, so the only credit
     * source in the run is salvage and the AC#5 assertion can attribute the gain to the loot table alone (the
     * UC41 bounty pitfall). The player starts just west of the picket cruising east with **one crew aboard**
     * (so the auto-aim turret is operable), thrusts in for [UC42_THRUST_IN_TICKS] ticks to trip the ambush,
     * then loiters and holds [FireAction.FIRE] for the rest of the run while the turret grinds the raider's
     * 30-HP hull to destruction; the wreck spawns at the kill position and the (generously-pinned) pickup
     * radius collects it on the next tick.
     *
     * [Uc42LootSalvageReplayTest] asserts the AC#5 contract: a wreck existed at some tick and the world is
     * clear of salvage at the end, the final credits and cargo equal the values [com.orbitalfrontier.combat
     * .LootTable.roll] yields for the kill (derived, no magic literals), and the replay is bit-for-bit
     * deterministic. The combat tuning is the pinned [UC42_COMBAT_TUNING]; all geometry is read from the
     * production [MvpSectorMap]. Loadable via `playtest -Dplaythrough.name=uc42-loot-salvage`.
     */
    fun uc42LootSalvage(): Playthrough {
        // Start just OUTSIDE the r260 picket on the -x side (dist 270), already cruising east at 120 wu/s so a
        // few ticks of eastward thrust carry the ship across the r260 edge and trip the ambush (identical to UC13).
        val start = UC13_PICKET_CENTER - Vec2(270f, 0f)
        val fleet =
            singleShipFleet(
                kinematics = ShipKinematics(position = start, velocity = Vec2(120f, 0f), headingRadians = 0f),
            ).let { f -> f.withActive(f.active.withCrew(1)) }

        val recorder =
            PlaythroughRecorder(
                name = UC42_LOOT_SALVAGE,
                seed = 42L,
                dtSeconds = DT_SECONDS,
                combatConfig = UC42_COMBAT_TUNING,
                initialState =
                    SimulationState(
                        fleet = fleet,
                        // Start broke so the salvage payout is measured from a known-zero baseline.
                        credits = 0L,
                        // The respawn point on destruction (persisted); the raider never reaches the player's hull
                        // first under the pinned tuning, but the field mirrors UC13's start for parity.
                        lastDockedStation = PoiId("alpha-station"),
                    ),
            )

        val east = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 1f, released = false)
        // Thrust east to cross the picket edge and spawn the raider; FIRE the whole time.
        for (tick in 0 until UC42_THRUST_IN_TICKS) {
            recorder.recordMovement(tick, east)
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        // Loiter (released stick ⇒ drift to near-rest inside the picket) and keep firing: the turret auto-aims
        // and destroys the closing raider; its wreck is then collected by the pinned pickup radius.
        for (tick in UC42_THRUST_IN_TICKS until UC42_TOTAL_TICKS) {
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        return recorder.build()
    }

    /** The Gamma Verge sector the UC43 fixture roams (the Independents' home turf). */
    private val UC43_SECTOR: SectorId = SectorId("gamma")

    /** The authored `gamma-independent-marauder` zone (read from the production map) — the AC#5 kill site. */
    private val UC43_MARAUDER_ZONE =
        MvpSectorMap.encounterZones(UC43_SECTOR).first { it.id == "gamma-independent-marauder" }

    /**
     * Combat tuning **pinned for the UC43 combat-reputation fixture only** — the same HULL-funnelled,
     * wide-hit-radius recipe [UC13_COMBAT_TUNING] uses so the faction marauder's 30-HP hull falls in a
     * fixed number of turret hits (a compact, deterministic kill) instead of a stochastic spread across
     * four sections. Pinning per artifact (the UC02 config-pin rationale) means a later combat retune can't
     * silently invalidate this replay.
     */
    private val UC43_COMBAT_TUNING: CombatParams =
        CombatParams(sectionHitWeights = mapOf(ShipSection.HULL to 1), hitRadius = 60f)

    /** Thrust-in ticks for [uc43CombatReputation] — enough to cross the marauder zone's r260 edge from just outside. */
    private const val UC43_THRUST_IN_TICKS: Int = 12

    /** Total ticks for [uc43CombatReputation] — the thrust-in plus a long loiter while the turret grinds the marauder down. */
    private const val UC43_TOTAL_TICKS: Int = 440

    /**
     * UC43 combat-reputation scenario (AC#5): destroying a faction-affiliated hostile sours the player's
     * standing with that faction through the combat→reputation seam (the `Reputation.with` site ADR 0013
     * predicted, ADR 0031).
     *
     * The player starts **in flight in [UC43_SECTOR] (Gamma Verge)** just **west of** the authored
     * `gamma-independent-marauder` zone (centre [UC43_MARAUDER_ZONE].center `(700, 700)`, radius 260),
     * facing east, **starting neutral** with the Independents, with **one crew aboard** (so the built-in
     * auto-aim turret is operable) and the last-docked station recorded at the Gamma junkyard (the respawn
     * point). Geometrically identical to [uc13Combat], but in **Gamma** — a sector no other committed fixture
     * roams, so the new natural zone can never perturb an existing replay (the UC43 fixture-stability top
     * risk). The script:
     *  - **thrust-in**: thrust east for [UC43_THRUST_IN_TICKS] ticks, crossing the zone's r260 edge — the
     *    edge-triggered [com.orbitalfrontier.combat.EncounterSpawner] ambushes the player with a single
     *    [com.orbitalfrontier.combat.HostileArchetypes.INDEPENDENT_MARAUDER] (seeded by the crossing tick);
     *  - **loiter + fire**: release the stick and hold [FireAction.FIRE] for the rest of the run. The
     *    AGGRESSIVE marauder closes; the crew-gated turret auto-aims and (with the pinned HULL-funnelled
     *    tuning) grinds its 30-HP hull to destruction. The kill folds [com.orbitalfrontier.faction
     *    .ReputationParams.combatKillDelta] into the player's Independents standing via the lockstep
     *    [com.orbitalfrontier.combat.CombatReputation.applyKills] mirror in the [com.orbitalfrontier.sim.Simulation].
     *
     * [Uc43CombatReputationReplayTest] asserts a faction ship was destroyed, the Independents standing
     * dropped by exactly `combatKillDelta` (derived, no magic literal), the drop persists across a snapshot
     * round-trip (AC#5), and the replay is bit-for-bit deterministic. The combat tuning is the pinned
     * [UC43_COMBAT_TUNING]; all geometry is read from the production [MvpSectorMap]. Loadable via
     * `playtest -Dplaythrough.name=uc43-combat-reputation`.
     */
    fun uc43CombatReputation(): Playthrough {
        // Start just OUTSIDE the r260 marauder zone on the -x side (dist 270), already cruising east at
        // 120 wu/s so a few ticks of eastward thrust carry the ship across the r260 edge and trip the ambush
        // (identical geometry to UC13, but in Gamma).
        val start = UC43_MARAUDER_ZONE.center - Vec2(270f, 0f)
        val fleet =
            singleShipFleet(
                kinematics = ShipKinematics(position = start, velocity = Vec2(120f, 0f), headingRadians = 0f),
            ).let { f -> f.withActive(f.active.withCrew(1)) }

        val recorder =
            PlaythroughRecorder(
                name = UC43_COMBAT_REPUTATION,
                seed = 43L,
                dtSeconds = DT_SECONDS,
                combatConfig = UC43_COMBAT_TUNING,
                initialState =
                    SimulationState(
                        // The whole run takes place in Gamma — where the faction marauder zone lives.
                        currentSector = UC43_SECTOR,
                        fleet = fleet,
                        // Start neutral so the post-kill standing is measured from a known-zero baseline.
                        reputation = Reputation.EMPTY,
                        // The respawn point on destruction (persisted); the marauder never reaches the
                        // player's hull first under the pinned tuning, but the field mirrors the start sector.
                        lastDockedStation = PoiId("gamma-junkyard"),
                    ),
            )

        val east = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 1f, released = false)
        // Thrust east to cross the zone edge and spawn the marauder; FIRE the whole time.
        for (tick in 0 until UC43_THRUST_IN_TICKS) {
            recorder.recordMovement(tick, east)
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        // Loiter (released stick ⇒ drift to near-rest inside the zone) and keep firing: the turret auto-aims
        // and destroys the closing marauder over the remaining ticks, souring the Independents standing.
        for (tick in UC43_THRUST_IN_TICKS until UC43_TOTAL_TICKS) {
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        return recorder.build()
    }

    /** The authored multi-archetype `gamma-marauder-pack` zone (read from the production map) — the UC45 fight site. */
    private val UC45_PACK_ZONE =
        MvpSectorMap.encounterZones(SectorId("gamma")).first { it.id == "gamma-marauder-pack" }

    /**
     * Combat tuning **pinned for the UC45 richer-AI fixture only**. Unlike the other combat fixtures it does
     * NOT funnel all damage to the HULL (condition #3): the RNG-weighted hit pool spans HULL **and** ENGINE,
     * so the player's recorded damage is genuinely multi-section and the new AI is distinguishable from plain
     * HULL-funnelled fire. Two deliberate choices make the lone-turret-vs-three-pack fight clear deterministically:
     *
     *  - **TURRET and WEAPON carry zero RNG weight.** A stray RNG hit on the player's TURRET could make it the
     *    weakest section, at which point the weakest-section-targeting
     *    [com.orbitalfrontier.combat.HostileArchetypes.PRECISION_RAIDER] would funnel its **direct** hits into
     *    the turret mount and disable the player's only auto-aim weapon, stalling the fight. With TURRET (and
     *    WEAPON) out of the RNG pool the precision raider instead locks onto the ENGINE — the initial weakest
     *    section by the ascending-name tie-break — which is harmless to a loitering player, so the weakest-section
     *    behaviour still manifests visibly (recorded ENGINE damage) while the turret stays operable.
     *  - **A wide [CombatParams.hitRadius].** Once a damaged
     *    [com.orbitalfrontier.combat.HostileArchetypes.REGROUP_MARAUDER] crosses its flee threshold it backs off
     *    to its `regroupRange` (700 wu) — beyond the turret's range — and an engine-slowed player cannot chase
     *    it. The generous hit radius lets the auto-aim turret's un-led shots still connect against the standoff-
     *    parking marauder so the encounter actually clears within a bounded run, rather than stalemating on an
     *    uncatchable straggler.
     *
     * Pinning per artifact (the UC02 config-pin rationale) means a later combat retune can't silently invalidate
     * this replay. The HULL is weighted heaviest so the turret grinds the pack down; all numbers are `[TUNE]`.
     */
    private val UC45_COMBAT_TUNING: CombatParams =
        CombatParams(
            sectionHitWeights = mapOf(ShipSection.HULL to 9, ShipSection.ENGINE to 1),
            hitRadius = 200f,
        )

    /** Thrust-in ticks for [uc45EnemyAi] — enough to cross the pack zone's r260 edge from just outside. */
    private const val UC45_THRUST_IN_TICKS: Int = 16

    /** Total ticks for [uc45EnemyAi] — the thrust-in plus a long loiter while the turret grinds the pack down. */
    private const val UC45_TOTAL_TICKS: Int = 700

    /**
     * UC45 richer-enemy-AI scenario (AC#5): the player flies into the authored multi-archetype
     * `gamma-marauder-pack` zone (centre [UC45_PACK_ZONE].center `(-700, -300)`, radius 260) in **Gamma
     * Verge**, spawning a three-archetype pack — an [com.orbitalfrontier.combat.HostileArchetypes
     * .INDEPENDENT_MARAUDER], a [com.orbitalfrontier.combat.HostileArchetypes.REGROUP_MARAUDER] and a
     * [com.orbitalfrontier.combat.HostileArchetypes.PRECISION_RAIDER] — and holds FIRE while the crew-gated
     * auto-aim turret destroys them.
     *
     * The player starts in flight just **west of** the zone, cruising east at max speed with **one crew
     * aboard** (turret operable) and the last-docked station recorded at the Gamma junkyard (the respawn
     * point). It thrusts east for [UC45_THRUST_IN_TICKS] ticks to cross the r260 edge and trip the ambush,
     * then loiters and holds [FireAction.FIRE] for the rest of the run. The empty loadout means the player's
     * progression level is 0, so the zone's `ByProgression` scaling adds no reinforcements — the pack is
     * exactly its three authored archetypes (a bounded, deterministic fight).
     *
     * Authored in **Gamma** and geometrically disjoint from the existing `gamma-independent-marauder` zone
     * (and from the UC43 fixture's NE-only flight path), so it perturbs no existing replay (CONDITION #1).
     * The combat tuning is the pinned [UC45_COMBAT_TUNING] (multi-section weights, so the retreat-and-regroup
     * and weakest-section behaviours manifest); all geometry is read from the production [MvpSectorMap].
     * Loadable via `playtest -Dplaythrough.name=uc45-enemy-ai`.
     */
    fun uc45EnemyAi(): Playthrough {
        // Start just OUTSIDE the r260 pack zone on the -x side (dist 270), already cruising east at 120 wu/s so
        // a few ticks of eastward thrust carry the ship across the r260 edge and trip the ambush.
        val start = UC45_PACK_ZONE.center - Vec2(270f, 0f)
        val fleet =
            singleShipFleet(
                kinematics = ShipKinematics(position = start, velocity = Vec2(120f, 0f), headingRadians = 0f),
            ).let { f -> f.withActive(f.active.withCrew(1)) }

        val recorder =
            PlaythroughRecorder(
                name = UC45_ENEMY_AI,
                seed = 45L,
                dtSeconds = DT_SECONDS,
                combatConfig = UC45_COMBAT_TUNING,
                initialState =
                    SimulationState(
                        // The whole run takes place in Gamma — where the multi-archetype pack zone lives.
                        currentSector = SectorId("gamma"),
                        fleet = fleet,
                        // The respawn point on destruction (persisted); mirrors the start sector.
                        lastDockedStation = PoiId("gamma-junkyard"),
                    ),
            )

        val east = MovementInput(targetDirection = Vec2(1f, 0f), magnitude = 1f, released = false)
        // Thrust east to cross the pack zone edge and spawn the pack; FIRE the whole time.
        for (tick in 0 until UC45_THRUST_IN_TICKS) {
            recorder.recordMovement(tick, east)
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        // Loiter (released stick ⇒ drift to near-rest inside the zone) and keep firing: the turret auto-aims
        // and grinds the multi-hostile pack down over the remaining ticks.
        for (tick in UC45_THRUST_IN_TICKS until UC45_TOTAL_TICKS) {
            recorder.recordFireAction(tick, FireAction.FIRE)
        }
        return recorder.build()
    }
}
