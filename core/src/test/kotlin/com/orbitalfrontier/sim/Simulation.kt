package com.orbitalfrontier.sim

import com.orbitalfrontier.combat.Combat
import com.orbitalfrontier.combat.CombatEvent
import com.orbitalfrontier.combat.CombatLimitedMovement
import com.orbitalfrontier.combat.CombatParams
import com.orbitalfrontier.combat.DestroyedHostile
import com.orbitalfrontier.combat.EncounterSpawner
import com.orbitalfrontier.combat.FireAction
import com.orbitalfrontier.combat.PlayerCombatInput
import com.orbitalfrontier.combat.Respawn
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.combat.ShipSection
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.crew.Hiring
import com.orbitalfrontier.economy.FuelBurn
import com.orbitalfrontier.economy.FuelParams
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.Refueling
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.economy.Trading
import com.orbitalfrontier.faction.ReputationGate
import com.orbitalfrontier.faction.ReputationParams
import com.orbitalfrontier.mission.BountyParams
import com.orbitalfrontier.mission.BountyTracking
import com.orbitalfrontier.mission.MissionGenerator
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.mission.MissionParams
import com.orbitalfrontier.mission.MissionResult
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.mission.Missions
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.Outfitting
import com.orbitalfrontier.outfit.ShipStats
import com.orbitalfrontier.platform.Rng
import com.orbitalfrontier.platform.TimeSource
import com.orbitalfrontier.power.PowerParams
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.FleetResolver
import com.orbitalfrontier.ship.FuelLimitedMovement
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipMovementModel
import com.orbitalfrontier.ship.ShipMovementParams
import com.orbitalfrontier.ship.Shipyard
import com.orbitalfrontier.station.StationBuildOrder
import com.orbitalfrontier.station.StationBuilder
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.Docking
import com.orbitalfrontier.world.GateTraversal
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.Mining
import com.orbitalfrontier.world.MiningResult
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.ScanAction
import com.orbitalfrontier.world.Scanning
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.SectorWorld
import com.orbitalfrontier.world.StationKind

/**
 * The single, pure, deterministic fixed-timestep stepper for the game world (UC02 AC#1, UC03 AC#10).
 *
 * One [step] advances [SimulationState] by exactly one fixed tick of [dt] seconds under one frame
 * of [MovementInput]. It contains **no** GL, Android, or Box2D types and performs no I/O, so it
 * runs headlessly on the JVM and is the same code path used by both the live recorder and the
 * headless [com.orbitalfrontier.playthrough.ReplayRunner] — that shared, side-effect-free stepper
 * is what guarantees record/replay parity.
 *
 * Determinism note (ADR 0005 / ADR 0006): replay targets this **pure model**, never the on-device
 * Box2D integrator. Box2D owns position integration on device, but its native step is outside the
 * JVM determinism contract; for CI-grade reproducibility we integrate position inside
 * [ShipMovementModel] (semi-implicit Euler) and assert against that.
 *
 * UC03 jump gates: after each movement update [step] consults the pure [GateTraversal.resolve] —
 * the **same** function [com.orbitalfrontier.screen.PlayScreen] runs on device — against the ship's
 * new position. On a non-null traversal it switches [SimulationState.currentSector] to the
 * destination and relocates the ship to the gate's arrival point **keeping its velocity and
 * heading** (momentum carries through the gate, matching the device's `resetTo`). [world] is
 * injected (defaulting to the MVP map) so the same gate graph drives both the live game and replay.
 *
 * The [rng] and [timeSource] ports are injected and seeded/configured per playthrough. No UC02 sim
 * system consumes them yet (movement is purely deterministic), but they are wired in now so every
 * later system draws randomness and time from the deterministic seam rather than retrofitting it.
 *
 * @param params pinned per-playthrough movement tuning; a recorded playthrough stores the exact
 *   [ShipMovementParams] it ran under so a later tuning change can't silently invalidate old replays.
 * @param world the fixed sector graph traversed by jump gates; defaults to [MvpSectorMap.build].
 */
class Simulation(
    private val rng: Rng,
    private val timeSource: TimeSource,
    private val params: ShipMovementParams = ShipMovementParams(),
    private val movementModel: ShipMovementModel = ShipMovementModel(),
    private val world: SectorWorld = MvpSectorMap.build(),
    private val miningParams: MiningParams = MiningParams(),
    private val powerParams: PowerParams = PowerParams(),
    private val fuelParams: FuelParams = FuelParams(),
    private val missionParams: MissionParams = MissionParams(),
    private val combatParams: CombatParams = CombatParams(),
    private val reputationParams: ReputationParams = ReputationParams(),
    private val bountyParams: BountyParams = BountyParams(),
) {
    /** The seeded randomness source for this run, for sim systems that need it (none in UC02 yet). */
    fun rng(): Rng = rng

    /** Elapsed simulation seconds at [tick], derived purely from the fixed step (no wall clock). */
    fun elapsedSecondsAt(tick: Int): Float = timeSource.secondsAt(tick)

    /**
     * Advance [state] by one fixed tick of [dt] seconds under [input] and the player's per-tick
     * [dockAction], returning the next snapshot with its tick incremented. [dt] must be > 0 and, for
     * a coherent replay, identical to the playthrough's recorded `dtSeconds`.
     *
     * **Docked ⇒ frozen (UC05 AC#2/#6).** While [SimulationState.dockedStation] is non-null the ship
     * is parked at the station: this method **explicitly short-circuits both movement and gate
     * traversal** (the ship neither drifts nor re-triggers a jump) and only advances the tick. The
     * single exception is an explicit [DockAction.UNDOCK], which falls through to the flight path and
     * returns the ship to flight. A [DockAction.DOCK] while already docked is a no-op (it holds).
     *
     * **In flight.** Movement integrates first; the new ship position is checked against
     * [GateTraversal.resolve] in the ship's current sector — a hit relocates the ship to the linked
     * gate's arrival point in the destination sector, preserving velocity and heading (momentum
     * through the gate, UC03 AC#3). [Docking.resolve] then resolves [dockAction] against the
     * resulting sector/position: a [DockAction.DOCK] with a station in range docks the ship (so the
     * *next* tick it is frozen). The default [DockAction.NONE] leaves the dock state unchanged, so
     * the pre-UC05 fixtures (which pass no action) step identically.
     *
     * **Mining (UC06).** After movement/gate/dock resolution, while the ship is **in flight** (it did
     * not dock this tick) the player's [mineAction] is resolved against the post-movement
     * sector/position via the pure [Mining.resolve], threading the resulting [SimulationState.cargo]
     * and [SimulationState.fieldDepletion] into the next snapshot (UC06 AC#2/#4/#5). The default
     * [MineAction.NONE] is a no-op that returns cargo + depletion unchanged, and a freshly-docked
     * tick skips mining entirely, so the pre-UC06 fixtures (which pass no mine action) step
     * **byte-identically**.
     *
     * **Fuel & power (UC07).** [refuelAction] is resolved **first, before the docked-freeze short-
     * circuit**, via the pure [Refueling.resolve] (so a docked ship can convert hydrogen cargo into
     * fuel — refuel-while-docked, UC07 AC#5); the resulting fuel + cargo thread forward whether the
     * ship is frozen or in flight. On the in-flight path the ship then **burns fuel**: the power
     * model's draw at the current thrust state (`thrusting = !released && magnitude > deadzone`, the
     * same gate the device loop uses) is consumed via the shared [FuelBurn.step], and the movement
     * model runs against [FuelLimitedMovement.effectiveParams] — so a low tank scales the speed caps
     * down (UC07 AC#3) while a full-enough tank leaves [params] untouched (the byte-identical
     * guarantee for the pre-UC07 fixtures). The post-burn [SimulationState.fuel] threads into the next
     * snapshot (UC07 AC#6). The default [RefuelAction.NONE] + a full default tank makes every pre-UC07
     * fixture step its movement **byte-identically**.
     *
     * **Trading (UC08).** [tradeOrder] is resolved **while docked**, in the same frozen branch (after
     * the refuel resolves, so a refuel + trade in the same tick compose against the post-refuel cargo):
     * the docked station's authored market is looked up from [world] (mirroring the device's
     * [com.orbitalfrontier.screen.PlayScreen.trade]) and the pure [Trading.resolve] threads the new
     * [SimulationState.credits] + [SimulationState.cargo] into the docked-return snapshot (UC08 AC#3).
     * Trading is implicitly gated on being docked: in flight there is no market, so the in-flight path
     * simply **passes [SimulationState.credits] through unchanged**. The default [TradeOrder.None] is a
     * no-op (credits + cargo unchanged), so every pre-UC08 fixture steps **byte-identically**.
     *
     * **Scanning (UC10).** After mining, while the ship is **in flight** (it did not dock this tick) the
     * player's [scanAction] is resolved via the pure [Scanning.resolve] against the post-movement
     * sector/position. The active ship's effective sensor range is derived from its type + loadout via
     * [ShipStats.scanRange] — the same derivation the device uses — so a sensors upgrade (UC09 SCANNER_I)
     * widens the reveal radius (UC10 AC#3). A [ScanAction.SCAN] unions the in-range hidden contacts into
     * [SimulationState.revealedContacts]; reveal is **monotonic** — ids are never removed, even on leaving
     * range (UC10 AC#4). The default [ScanAction.NONE] returns the same revealed set instance, so every
     * pre-UC10 fixture threads its (empty) revealed set through and steps **byte-identically**.
     */
    fun step(
        state: SimulationState,
        input: MovementInput,
        dt: Float,
        dockAction: DockAction = DockAction.NONE,
        mineAction: MineAction = MineAction.NONE,
        refuelAction: RefuelAction = RefuelAction.NONE,
        tradeOrder: TradeOrder = TradeOrder.None,
        outfitOrder: OutfitOrder = OutfitOrder.None,
        fleetOrder: FleetOrder = FleetOrder.None,
        scanAction: ScanAction = ScanAction.NONE,
        hireOrder: HireOrder = HireOrder.None,
        missionOrder: MissionOrder = MissionOrder.None,
        fireAction: FireAction = FireAction.NONE,
        stationBuildOrder: StationBuildOrder = StationBuildOrder.None,
    ): SimulationState {
        require(dt > 0f) { "dt must be positive: $dt" }

        // Refuel resolves FIRST — before the docked-freeze short-circuit — so a docked ship can
        // convert hydrogen cargo into fuel (refuel-while-docked, UC07 AC#5). NONE is a no-op that
        // returns fuel + cargo unchanged, so pre-UC07 fixtures thread the same values through.
        val refuel = Refueling.resolve(state.fuel, state.cargo, refuelAction, fuelParams)

        // Docked and not explicitly undocking ⇒ frozen: short-circuit movement AND gate traversal.
        // Only the tick advances (plus any refuel just resolved); position, velocity, heading, sector,
        // dock state and field depletion are untouched, so a held-while-docked stretch is bit-for-bit
        // stable (UC05 AC#6) — and burns NO fuel (the station hub, not flight, owns docked time).
        if (state.dockedStation != null && dockAction != DockAction.UNDOCK) {
            // Trading resolves while docked, against the docked station's authored market and the
            // post-refuel cargo (refuel + trade compose). The same market lookup the device's
            // PlayScreen.trade uses; an unresolvable station / no trade desk yields a null market and
            // Trading.resolve no-ops. TradeOrder.None (the default) is a no-op too — credits + cargo
            // thread through unchanged, so a held-while-docked stretch stays bit-for-bit stable.
            val station = world.sector(state.currentSector).station(state.dockedStation)
            val trade = Trading.resolve(state.credits, refuel.cargo, station?.market, tradeOrder)

            // UC09 composition while docked: refuel -> trade -> outfit -> fleet. Outfitting resolves
            // against the active ship's loadout + slot layout and the docked station's outfit desk;
            // FleetResolver against the station's shipyard. Both no-op on their default None order, so a
            // held-while-docked stretch (no orders) stays bit-for-bit stable.
            val active = state.fleet.active
            val outfit =
                Outfitting.resolve(
                    credits = trade.credits,
                    loadout = active.loadout,
                    slotCounts = active.type.slotCounts,
                    outfitMarket = station?.outfitMarket ?: OutfitMarket.EMPTY,
                    isJunkyard = station?.kind == StationKind.JUNKYARD,
                    order = outfitOrder,
                )
            // Fold cargo (trade) + fuel (refuel) onto the active ship, then — only if the fit changed —
            // re-derive capacities via withLoadout (Δ-capacity propagation, UC09 AC#2). Contents/level
            // are preserved (clamped only if a capacity shrank).
            val refittedActive =
                active.copy(cargo = trade.cargo, fuel = refuel.fuel)
                    .let { if (outfit.changed) it.withLoadout(outfit.loadout) else it }
            val fleetAfterOutfit = state.fleet.withActive(refittedActive)

            val fleetResult =
                FleetResolver.resolve(fleetAfterOutfit, outfit.credits, station?.shipyard ?: Shipyard.EMPTY, fleetOrder)

            // Crew hiring (UC11) is the LAST docked step: refuel -> trade -> outfit -> fleet -> hire, so a
            // hire resolves against the post-fleet wallet and the ship that is active AFTER any switch.
            // Capacity is derived from the active ship's type + loadout via ShipStats.crewCapacity — the
            // SAME source PlayScreen.hire uses on device (parity is required for AC#6) — and is gated on
            // the docked station's hiresCrew flag. HireOrder.None (the default) and a non-hiring station
            // both no-op, so a held-while-docked stretch (no hire) stays bit-for-bit stable; crew rides
            // on the active ship, so SimulationState needs no new field.
            val postFleetActive = fleetResult.fleet.active
            val hire =
                Hiring.resolve(
                    credits = fleetResult.credits,
                    currentCrew = postFleetActive.crew,
                    crewCapacity = ShipStats.crewCapacity(postFleetActive.type, postFleetActive.loadout),
                    offersCrew = station?.hiresCrew ?: false,
                    order = hireOrder,
                )
            val fleetAfterHire =
                if (hire.changed) fleetResult.fleet.withActive(postFleetActive.withCrew(hire.crew)) else fleetResult.fleet

            // Missions (UC12) — the LAST docked step (refuel -> trade -> outfit -> fleet -> hire ->
            // mission). The docked station's board offers are regenerated from the static authored world
            // (regenerate-and-filter, ADR 0011), and Missions.resolve handles a board Accept, automatic
            // courier pickup on docking at the pickup station, and a TurnIn (mining quota at any board /
            // courier delivery at the destination) against the post-hire wallet + the active ship's cargo;
            // Missions.advance then decrements any active courier timer for this tick. Both no-op (returning
            // the SAME log / credits / cargo instances) on an empty log + None order, so a held-while-docked
            // stretch stays bit-for-bit stable and pre-UC12 fixtures step byte-identically.
            val dockedStationId = state.dockedStation
            val dockedActive = fleetAfterHire.active
            // UC14: the reputation gate is a SEPARATE filter applied AFTER the takenIds filter (the board
            // site of the three symmetric gating sites — mirroring PlayScreen.stationMissionBoard). It only
            // hides offers the player hasn't unlocked; generation stays a pure function of static state, so a
            // gated `:premium` offer never perturbs the bytes of the mining/courier offers it sits beside.
            val boardOffers =
                MissionGenerator.boardOffers(
                    world,
                    dockedStationId,
                    missionParams,
                    MvpSectorMap.bountyContracts(dockedStationId),
                    bountyParams,
                )
                    .filter { it.id !in state.missions.takenIds }
                    .filter { ReputationGate.isAvailable(it, state.reputation) }
            val missionResolve =
                Missions.resolve(
                    log = state.missions,
                    offers = boardOffers,
                    order = missionOrder,
                    dockedStation = dockedStationId,
                    cargo = dockedActive.cargo,
                    credits = hire.credits,
                    params = missionParams,
                    reputation = state.reputation,
                    reputationParams = reputationParams,
                )
            val missionAdvance =
                Missions.advance(
                    missionResolve.log,
                    missionResolve.credits,
                    missionResolve.cargo,
                    missionParams,
                    missionResolve.reputation,
                    reputationParams,
                )
            // Fold the post-mission cargo back onto the active ship only when it actually changed (a mining
            // turn-in consumes the quota / a resource bonus lands); otherwise keep the same instance.
            val fleetAfterMission =
                if (missionAdvance.cargo !== dockedActive.cargo) {
                    fleetAfterHire.withActive(dockedActive.copy(cargo = missionAdvance.cargo))
                } else {
                    fleetAfterHire
                }

            // Station building (UC15) — the LAST docked step (refuel -> trade -> outfit -> fleet -> hire ->
            // mission -> build). Resolved against the docked station's build-capability (Station.buildsStations)
            // and the current sector (where a newly-founded station is anchored), drawing build resources from
            // the active ship's (post-mission) hold and the post-mission wallet — the SAME composition
            // PlayScreen.build runs on device. A no-op (idle / not build-capable / unknown module / unaffordable
            // / not owned) returns the SAME registry / credits / cargo instances, so a held-while-docked stretch
            // stays bit-for-bit stable and pre-UC15 fixtures step byte-identically.
            val buildActive = fleetAfterMission.active
            val build =
                StationBuilder.resolve(
                    registry = state.stations,
                    credits = missionAdvance.credits,
                    cargo = buildActive.cargo,
                    buildsStations = station?.buildsStations ?: false,
                    sector = state.currentSector,
                    order = stationBuildOrder,
                )
            // Fold the post-build cargo back onto the active ship only when it actually changed (resources
            // were spent); otherwise keep the same instance (byte-identical on a no-op build).
            val fleetAfterBuild =
                if (build.changed && build.cargo !== buildActive.cargo) {
                    fleetAfterMission.withActive(buildActive.copy(cargo = build.cargo))
                } else {
                    fleetAfterMission
                }

            return state.copy(
                tick = state.tick + 1,
                fleet = fleetAfterBuild,
                credits = build.credits,
                missions = missionAdvance.log,
                // UC15: the player's owned stations after this tick's build step (the SAME instance as
                // state.stations on a no-op, so a held-while-docked stretch is byte-identical).
                stations = build.registry,
                // UC14: the per-faction standing after this tick's resolve + advance. A faction mission
                // turn-in moved it; otherwise it is the SAME instance as state.reputation (byte-identical).
                reputation = missionAdvance.reputation,
                // UC13: a docked ship's "last docked station" is where it is parked — the respawn point on
                // destruction (set on dock, retained after undock). Combat never runs on the docked-freeze
                // path (encounter zones sit in open space, away from stations), so combat threads through
                // unchanged here.
                lastDockedStation = state.dockedStation,
            )
        }

        // In flight: burn fuel for this tick's power draw, then derive the fuel-limited movement params.
        // "Thrusting" matches the device loop's gate exactly so live and replayed burn agree (UC07 AC#2).
        val thrusting = !input.released && input.magnitude > params.inputDeadzone
        val burnedFuel = FuelBurn.step(refuel.fuel, thrusting, powerParams, dt)
        // UC09: derive the active ship's effective movement params from its type + engine loadout FIRST
        // (ShipStats), then apply the fuel-limited scaling on top. For the starter ship with an empty
        // loadout ShipStats returns `params` unchanged (===), so the pre-UC09 fixtures stay byte-
        // identical; an engine upgrade / faster hull raises the speed+accel caps before fuel limiting.
        val active = state.fleet.active
        val shipParams = ShipStats.effectiveMovementParams(params, active.type, active.loadout)
        // Byte-identical at a full-enough tank: effectiveParams returns `shipParams` unchanged (factor
        // 1.0), so the movement model steps exactly as before; only a low tank scales the caps (AC#3).
        val fuelLimitedParams = FuelLimitedMovement.effectiveParams(shipParams, burnedFuel, fuelParams)
        // UC13: engine damage scales the speed caps on top of fuel limiting — the SAME composition the
        // device's PlayScreen runs. With a pristine engine (the common case, incl. every pre-UC13 fixture)
        // CombatLimitedMovement returns `fuelLimitedParams` unchanged (factor 1.0, same instance), so
        // movement stays byte-identical; only engine damage taken in combat reduces effective speed (AC#3).
        val effectiveParams =
            CombatLimitedMovement.effectiveParams(
                fuelLimitedParams,
                active.sectionDamage,
                ShipStats.sectionHp(active.type, active.loadout, ShipSection.ENGINE),
                combatParams,
            )

        val movedShip = movementModel.update(state.ship, input, effectiveParams, dt)
        val traversal = GateTraversal.resolve(world, state.currentSector, movedShip.position)
        val nextSector = traversal?.destinationSector ?: state.currentSector
        // Keep velocity & heading; only the position and sector change on a jump (momentum carries).
        val nextShip = if (traversal == null) movedShip else movedShip.copy(position = traversal.arrivalPosition)
        // Resolve the dock action against the post-movement sector/position. DOCK with a station in
        // range docks; UNDOCK clears the dock; NONE leaves it unchanged (the common pre-UC05 path).
        val nextDocked = Docking.resolve(world, nextSector, state.dockedStation, nextShip.position, dockAction)

        // Mining only while in flight (not docked this tick). Operates on the post-refuel cargo so a
        // refuel + mine in the same tick composes correctly; a NONE action returns cargo + depletion
        // unchanged, so non-mining playthroughs thread the same defaults through and step identically.
        val mining =
            if (nextDocked == null) {
                Mining.resolve(
                    world = world,
                    currentSector = nextSector,
                    shipPosition = nextShip.position,
                    cargo = refuel.cargo,
                    fieldDepletion = state.fieldDepletion,
                    action = mineAction,
                    params = miningParams,
                )
            } else {
                MiningResult(refuel.cargo, state.fieldDepletion, 0)
            }

        // Scanning only while in flight (UC10). The active ship's effective sensor range is derived from
        // its type + loadout via ShipStats (the SAME derivation the device uses), so a sensors upgrade
        // (UC09 SCANNER_I) widens what a scan reveals (AC#3). Scanning.resolve unions the in-range hidden
        // contacts into the revealed set on a ScanAction.SCAN and returns the SAME set instance otherwise
        // — so a NONE scan (the common path) threads the prior set through unchanged and is monotonic
        // (revealed ids never re-hide, AC#4). A freshly-docked tick skips scanning, like mining.
        val revealedContacts =
            if (nextDocked == null) {
                val scanRange = ShipStats.scanRange(active.type, active.loadout)
                Scanning.resolve(
                    world = world,
                    currentSector = nextSector,
                    shipPosition = nextShip.position,
                    scanRange = scanRange,
                    revealed = state.revealedContacts,
                    action = scanAction,
                )
            } else {
                state.revealedContacts
            }

        // Missions (UC12) in flight: a radio Accept is resolved against the in-range radio broadcast
        // offers, regenerated from the static authored world (regenerate-and-filter, ADR 0011); a TurnIn /
        // board Accept no-ops in flight (no docked station, no board here). A freshly-docked tick skips the
        // radio resolve — like mining/scanning — but the courier timer still advances. Missions.advance
        // then decrements any active courier timer this tick. Both no-op (returning the SAME log / credits /
        // cargo instances) on an empty log + None order, so pre-UC12 fixtures thread the same defaults
        // through and step byte-identically.
        val missionResolve =
            if (nextDocked == null) {
                // UC14: the reputation gate — the SEPARATE filter applied AFTER the takenIds filter (the radio
                // site of the three symmetric gating sites — mirroring PlayScreen's radio-offer surfacing).
                val radioOffers =
                    MissionGenerator.radioOffers(
                        world,
                        nextSector,
                        nextShip.position,
                        missionParams,
                        MvpSectorMap.BOUNTY_CONTRACTS,
                        bountyParams,
                    )
                        .filter { it.id !in state.missions.takenIds }
                        .filter { ReputationGate.isAvailable(it, state.reputation) }
                Missions.resolve(
                    log = state.missions,
                    offers = radioOffers,
                    order = missionOrder,
                    dockedStation = null,
                    cargo = mining.cargo,
                    credits = state.credits,
                    params = missionParams,
                    reputation = state.reputation,
                    reputationParams = reputationParams,
                )
            } else {
                MissionResult(state.missions, state.credits, mining.cargo, state.reputation, false)
            }
        val missionAdvance =
            Missions.advance(
                missionResolve.log,
                missionResolve.credits,
                missionResolve.cargo,
                missionParams,
                missionResolve.reputation,
                reputationParams,
            )

        // UC42 collect: proximity salvage pickup — the lockstep mirror of PlayScreen.collectSalvage,
        // which runs AFTER movement/mining/missions and BEFORE the combat branch (so a tick that ends an
        // encounter can't skip it). In flight only (a freshly-docked tick hands off to the hub on device,
        // like mining/scanning). Operates on the post-mission cargo + wallet; credits + cargo thread into
        // the combat fold and the returns below. A NONE/empty salvage list is a same-instance no-op, so
        // pre-UC42 fixtures step byte-identically.
        var salvage = state.salvage
        var nextSalvageId = state.nextSalvageId
        var collectedCargo = missionAdvance.cargo
        var collectedCredits = missionAdvance.credits
        if (nextDocked == null) {
            val collect =
                Salvage.collect(salvage, nextShip.position, collectedCargo, collectedCredits, combatParams.salvagePickupRadius)
            if (collect.collectedAny) {
                salvage = collect.drops
                collectedCargo = collect.cargo
                collectedCredits = collect.credits
            }
        }

        // UC09: fold this tick's kinematics + cargo + fuel back onto the active ship in the fleet.
        // copy() (not withLoadout) — the loadout is unchanged in flight, so capacities stay as derived.
        // UC12/UC42: the cargo is the post-mission, post-salvage-collect cargo (same instance unless a
        // mission or a salvage pickup moved units — same-instance keeps it byte-identical on a no-op tick).
        val updatedActive = state.fleet.active.copy(kinematics = nextShip, cargo = collectedCargo, fuel = burnedFuel)

        // --- UC13 combat. Runs in flight only (a freshly-docked tick skips it, like mining/scanning) —
        // the authored encounter zones sit in open space, away from stations. Edge-triggered natural spawn
        // on the outside->inside crossing of a zone in this sector, then THE shared pure [Combat.step]
        // folds the player's new section damage back onto the active ship and runs [Respawn] on
        // destruction — the same functions PlayScreen.runCombat runs on device, so live and replayed combat
        // match (AC#7). With combat NONE + FireAction.NONE + no crossing every call returns the SAME
        // instances, advances no RNG and emits no events, so pre-UC13 fixtures step byte-identically.
        var combat = state.combat
        val lastDockedStation = nextDocked ?: state.lastDockedStation
        var combatFleet = state.fleet.withActive(updatedActive)
        // UC41: the mission log / wallet / standing after this tick's bounty-kill fold. Seeded from the
        // post-mission-resolve values and threaded into BOTH the destroyed-path and normal-path returns so
        // a bounty completion (auto-pay) persists exactly as on device. Same instances when no bounty kill
        // landed this tick — so pre-UC41 fixtures stay byte-identical.
        var bountyLog = missionAdvance.log
        // UC42: seed the wallet from the post-salvage-collect credits (so a bounty payout this tick stacks
        // on top of any salvage credits just collected — distinct sources, no double-count). Same value as
        // missionAdvance.credits when nothing was collected this tick.
        var bountyCredits = collectedCredits
        var bountyReputation = missionAdvance.reputation

        if (nextDocked == null) {
            // Edge-triggered natural spawn: previous (pre-movement) position outside -> new (post-gate)
            // position inside an authored zone in this sector. Seeded by the sim [tick] so the replay
            // reproduces the identical encounter; the spawner no-ops while a fight is already active.
            if (!combat.active) {
                for (zone in MvpSectorMap.encounterZones(nextSector)) {
                    val spawned =
                        EncounterSpawner.naturalSpawn(combat, zone, state.ship.position, nextShip.position, state.tick, combatParams)
                    if (spawned !== combat) {
                        combat = spawned
                        break
                    }
                }
            }

            // UC41 (a): edge-triggered BOUNTY spawn — the lockstep mirror of PlayScreen.runCombat. For each
            // ACTIVE bounty whose target zone is in this sector, the SAME outside->inside crossing
            // (pre-movement position outside, post-gate position inside) injects the contracted hostiles via
            // [EncounterSpawner.missionSpawn], seeded by [tick] (the sim spawn-seed convention; the device
            // uses combatSpawnTick), suppressed while a fight is already active. The spawned zoneId equals
            // the bounty's targetZoneId, so kills attribute back to it.
            if (!combat.active) {
                for (bounty in bountyLog.activeMissions) {
                    if (bounty.type != MissionType.BOUNTY) continue
                    val zoneId = bounty.targetZoneId ?: continue
                    val zone = MvpSectorMap.bountyTargetZone(zoneId) ?: continue
                    if (zone.sectorId != nextSector.value) continue
                    if (zone.contains(state.ship.position) || !zone.contains(nextShip.position)) continue
                    val spawned =
                        EncounterSpawner.missionSpawn(
                            combat,
                            zoneId,
                            zone.archetypeId,
                            zone.hostileCount,
                            nextShip.position,
                            state.tick,
                            combatParams,
                        )
                    if (spawned !== combat) {
                        combat = spawned
                        break
                    }
                }
            }

            if (combat.active) {
                val combatShip = combatFleet.active
                val playerInput =
                    PlayerCombatInput(
                        kinematics = combatShip.kinematics,
                        weapons = ShipStats.weaponLoadout(combatShip.type, combatShip.loadout),
                        maxSectionHp = ShipStats.sectionHpMap(combatShip.type, combatShip.loadout),
                        crew = combatShip.crew,
                        sectionDamage = combatShip.sectionDamage,
                    )
                // UC41 (b): capture the encounter's zone id BEFORE the step (a destroyed/cleared encounter
                // resets combat to NONE), then fold this tick's hostile kills into any matching ACTIVE
                // bounty — the lockstep mirror of PlayScreen.stepCombatOnce. Applied BEFORE the destruction
                // return so a kill on the death tick still counts; same instances when no kill landed.
                val preStepZoneId = combat.zoneId
                // UC42: snapshot the hostiles BEFORE the step — a HostileDestroyed event carries only the
                // id, and the cull removes the hostile, so the kill position + archetype (the salvage spawn
                // inputs) must be read from this pre-step list. Mirrors PlayScreen.stepCombatOnce.
                val preStepHostiles = combat.hostiles
                val result = Combat.step(combat, playerInput, fireAction, combatParams, dt)
                combat = result.combat

                val killsThisTick = result.events.count { it is CombatEvent.HostileDestroyed }
                if (killsThisTick > 0) {
                    val bounty =
                        BountyTracking.applyKills(
                            bountyLog,
                            preStepZoneId,
                            killsThisTick,
                            bountyCredits,
                            combatShip.cargo,
                            bountyReputation,
                            reputationParams,
                        )
                    if (bounty.changed) {
                        bountyLog = bounty.log
                        bountyCredits = bounty.credits
                        bountyReputation = bounty.reputation
                    }

                    // UC42 (a): spawn one salvage wreck per kill at the hostile's pre-step position, loot
                    // rolled deterministically from the zone + hostile id (independent of the combat RNG).
                    // Threaded into the salvage vars, which feed BOTH the destroyed-path and normal-path
                    // returns below. The lockstep mirror of PlayScreen.stepCombatOnce.
                    val byId = preStepHostiles.associateBy { it.id }
                    val destroyed =
                        result.events.asSequence()
                            .filterIsInstance<CombatEvent.HostileDestroyed>()
                            .mapNotNull { event -> byId[event.id] }
                            .map { hostile -> DestroyedHostile(hostile.id, hostile.archetypeId, hostile.kinematics.position) }
                            .toList()
                    val spawned = Salvage.spawn(salvage, nextSalvageId, preStepZoneId, destroyed)
                    salvage = spawned.drops
                    nextSalvageId = spawned.nextSalvageId
                }

                if (result.destroyed) {
                    // Forgiving respawn (AC#5, no permadeath): relocate to the last docked station's
                    // location, lighten the hold, fully repair, clear combat. With no last dock, respawn in
                    // place (never stranded) — the same [Respawn] the device runs.
                    val (respawnSector, respawnPosition) =
                        resolveRespawnLocation(lastDockedStation, combatShip.kinematics.position)
                    val respawn = Respawn.respawn(respawnPosition, combatShip.cargo, combatParams)
                    val respawnedShip =
                        combatShip
                            .withSectionDamage(respawn.sectionDamage)
                            .copy(cargo = respawn.cargo, kinematics = respawn.kinematics)
                    return state.copy(
                        tick = state.tick + 1,
                        fleet = combatFleet.withActive(respawnedShip),
                        currentSector = respawnSector ?: nextSector,
                        dockedStation = null,
                        fieldDepletion = mining.fieldDepletion,
                        // UC41: bounty-folded wallet/log/standing (a kill on the death tick still pays out).
                        credits = bountyCredits,
                        revealedContacts = revealedContacts,
                        missions = bountyLog,
                        // UC14/UC41: standing after this tick's mission resolve/advance + bounty fold.
                        reputation = bountyReputation,
                        combat = respawn.combat,
                        // UC42: the wrecks after this tick's collect + spawn (a kill on the death tick still
                        // drops its salvage; the player just respawned elsewhere can't reach it). Transient.
                        salvage = salvage,
                        nextSalvageId = nextSalvageId,
                        lastDockedStation = lastDockedStation,
                    )
                }

                // Fold the player's new section damage onto the active ship (=== check skips a no-op tick).
                if (result.sectionDamage !== combatShip.sectionDamage) {
                    combatFleet = combatFleet.withActive(combatShip.withSectionDamage(result.sectionDamage))
                }
            }
        }

        return state.copy(
            tick = state.tick + 1,
            fleet = combatFleet,
            currentSector = nextSector,
            dockedStation = nextDocked,
            fieldDepletion = mining.fieldDepletion,
            // In flight there is no station market, so trading is a no-op; the wallet threads through
            // unchanged unless a courier just expired this tick (penalty) or a bounty just paid out (UC41).
            credits = bountyCredits,
            // Revealed hidden contacts after this tick's scan (UC10): grown by a SCAN, otherwise the
            // prior set threaded through unchanged (monotonic).
            revealedContacts = revealedContacts,
            // The mission log after this tick's resolve + advance (UC12) + bounty kill fold (UC41); the
            // SAME instance on a no-op tick.
            missions = bountyLog,
            // UC14/UC41: the per-faction standing after this tick (SAME instance on a no-op tick — a courier
            // expiry via advance can move it; the bounty fold threads it unchanged today, the UC43 seam).
            reputation = bountyReputation,
            // UC13: the live encounter (NONE same-instance on a no-op tick) and the persisted respawn point.
            combat = combat,
            // UC42: the wrecks after this tick's collect + spawn (SAME instances on a no-op tick). Transient.
            salvage = salvage,
            nextSalvageId = nextSalvageId,
            lastDockedStation = lastDockedStation,
        )
    }

    /**
     * The respawn sector + position for a destroyed player (UC13 AC#5): the last docked station's
     * location if [stationId] is still in the authored [world], else `(null, fallback)` — respawn in
     * place when there is no recorded dock. Mirrors `PlayScreen.resolveRespawnLocation`.
     */
    private fun resolveRespawnLocation(
        stationId: PoiId?,
        fallback: Vec2,
    ): Pair<SectorId?, Vec2> {
        if (stationId != null) {
            for (sector in world.sectors) {
                val station = sector.station(stationId)
                if (station != null) return sector.id to station.position
            }
        }
        return null to fallback
    }
}
