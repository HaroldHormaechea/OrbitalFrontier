package com.orbitalfrontier.world

import com.orbitalfrontier.combat.EncounterZone
import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.combat.HostileSpawn
import com.orbitalfrontier.combat.SpawnScaling
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.economy.TradeOffer
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Factions
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.Shipyard

/**
 * The hand-authored 3-sector MVP map (UC03 AC#1; docs/design/world-and-sector.md, ADR 0004).
 *
 * This is the **single source of truth** for the MVP world, consumed both by the live game
 * ([com.orbitalfrontier.screen.PlayScreen]) and — via [START_SECTOR] as the default current sector —
 * by the deterministic test harness, so live and replay agree on the same topology.
 *
 * Topology: three sectors (Alpha, Beta, Gamma) wired into a **triangle** — every sector has two
 * gates, one to each of the others, with **reciprocal** links (validated by [SectorWorld]). Content
 * clusters at each sector's centre (origin); gates sit out toward the edge of the content area so the
 * player flies *out from* the cluster to jump.
 *
 * Sizing: [CONTENT_EXTENT_WORLD_UNITS] is a soft content **radius** of 1800 wu, i.e. ~3600 wu across
 * — about **30 s to cross at the ship's max speed** (120 wu/s, `ShipMovementParams.maxSpeed`). These
 * are authored tunables, not derived constants (see docs/design/world-and-sector.md).
 *
 * When seed-based procedural generation arrives (design note "Open questions"), it replaces this
 * authored data behind the same [SectorWorld] type without touching consumers.
 */
object MvpSectorMap {
    /** The canonical sector a new game / a fresh simulation starts in. */
    val START_SECTOR: SectorId = SectorId("alpha")

    /**
     * Canonical active-scan reference point in [START_SECTOR] (the sector centre, origin) that Alpha's
     * three hidden contacts are spaced against (UC10): `alpha-derelict` at 300 wu (inside base range),
     * `alpha-smuggler` at 600 wu (inside the SCANNER_I-upgraded range only), and `alpha-ghost` at
     * 800 wu (outside even the upgraded range). The recorded scan playthrough scans from here.
     */
    val SCAN_POINT: Vec2 = Vec2(0f, 0f)

    /** Soft content radius (world-units) of each MVP sector; ~30 s to cross at max speed. [TUNE] */
    const val CONTENT_EXTENT_WORLD_UNITS: Float = 1800f

    /**
     * The authored **natural encounter** zones of the MVP map — patches of hostile space the player can
     * cross into and be ambushed (edge-triggered, [EncounterSpawner]):
     *  - `alpha-raider-picket` (UC13) — an unaligned [HostileArchetypes.RAIDER] east of Alpha's centre,
     *    which the AC#8 recorded playthrough flies into, fires on and destroys. Alpha's **only** natural
     *    zone (the UC42 replay relies on `encounterZones(alpha).single()`).
     *  - `gamma-independent-marauder` (UC43) — a faction-affiliated [HostileArchetypes.INDEPENDENT_MARAUDER]
     *    in **Gamma Verge**, the Independents' home turf (the Gamma Junkyard is theirs), so destroying it
     *    sours the player's Independents standing. Authored in **Gamma** deliberately: **no committed
     *    fixture roams Gamma** (only the UC03 jump leaves Alpha, and it goes to Beta), and the natural-spawn
     *    check filters by the player's current sector — so this zone can never perturb any existing replay
     *    (the UC43 fixture-stability top risk), while the UC43 replay starts in Gamma to fly into it.
     *  - `beta-regroup-picket` (UC45) — Beta's first natural zone, a single
     *    [HostileArchetypes.REGROUP_MARAUDER] NE of Beta's centre at `(600, 700)` r260, disjoint from the
     *    `y≈0` eastward corridor the UC03 jump flies down (≈700 wu off it) and from beta-station/belt/gate,
     *    so it perturbs no existing replay.
     *  - `gamma-marauder-pack` (UC45) — a **multi-archetype, progression-scaled** Gamma zone at
     *    `(-700, -300)` r260 (AC#1/#3/#5): an [HostileArchetypes.INDEPENDENT_MARAUDER] alongside a
     *    [HostileArchetypes.REGROUP_MARAUDER] and a [HostileArchetypes.PRECISION_RAIDER], scaled by
     *    progression. Disjoint from `gamma-independent-marauder` (700,700) (~1720 wu away) and from the
     *    UC43 fixture's NE-only flight path, and the only zone the UC45 multi-hostile fixture flies into.
     *
     * Each zone is tagged with the sector's String id so `combat` needs no `world` dependency. [TUNE]
     */
    val ENCOUNTER_ZONES: List<EncounterZone> =
        listOf(
            EncounterZone(
                id = "alpha-raider-picket",
                sectorId = "alpha",
                center = Vec2(900f, 0f),
                radius = 260f,
                archetypeId = HostileArchetypes.RAIDER.id,
                hostileCount = 1,
            ),
            // UC43: a faction-affiliated marauder zone in Gamma (NE of the centre), disjoint from the Gamma
            // Junkyard (-500,200) and both Gamma gates (south, y≈-1126). In Gamma because no committed
            // fixture is ever in Gamma, so the per-sector natural-spawn check never sees this zone during
            // an existing replay. Within the 1800-wu content radius (|center| ≈ 990) so it stays in-bounds.
            EncounterZone(
                id = "gamma-independent-marauder",
                sectorId = "gamma",
                center = Vec2(700f, 700f),
                radius = 260f,
                archetypeId = HostileArchetypes.INDEPENDENT_MARAUDER.id,
                hostileCount = 1,
            ),
            // UC45: Beta's first natural zone. Single REGROUP_MARAUDER at (600,700) r260 — ~700 wu off the
            // y≈0 corridor the UC03 jump flies down (CONDITION #1: disjoint, so UC03 never trips it), and
            // clear of beta-station (300,-300), beta-belt (-500,500) and the beta→gamma gate (≈650,1126).
            EncounterZone(
                id = "beta-regroup-picket",
                sectorId = "beta",
                center = Vec2(600f, 700f),
                radius = 260f,
                archetypeId = HostileArchetypes.REGROUP_MARAUDER.id,
                hostileCount = 1,
            ),
            // UC45 (AC#1/#3/#5): the multi-archetype, progression-scaled Gamma zone the UC45 fixture flies
            // into. Composition mixes an aggressive marauder with a retreat-and-regroup and a weakest-section
            // targeter; ByProgression scales in extra REGROUP_MARAUDER reinforcements. Center (-700,-300)
            // r260 is ~1720 wu from gamma-independent-marauder (700,700) and clear of UC43's NE-only path
            // (CONDITION #1: disjoint). |center| ≈ 762 wu, inside the 1800-wu content radius.
            EncounterZone(
                id = "gamma-marauder-pack",
                sectorId = "gamma",
                center = Vec2(-700f, -300f),
                radius = 260f,
                composition =
                    listOf(
                        HostileSpawn(HostileArchetypes.INDEPENDENT_MARAUDER.id, 1),
                        HostileSpawn(HostileArchetypes.REGROUP_MARAUDER.id, 1),
                        HostileSpawn(HostileArchetypes.PRECISION_RAIDER.id, 1),
                    ),
                scaling =
                    SpawnScaling.ByProgression(
                        reinforcementArchetypeId = HostileArchetypes.REGROUP_MARAUDER.id,
                        hostilesPerLevel = 1,
                        maxBonusHostiles = 2,
                    ),
            ),
        )

    /** The authored encounter zones in [sectorId] (UC13) — what the screen checks for an edge-crossing. */
    fun encounterZones(sectorId: SectorId): List<EncounterZone> = ENCOUNTER_ZONES.filter { it.sectorId == sectorId.value }

    /**
     * The authored **bounty target zones** (UC41) — the dedicated encounter zones a combat-bounty contract
     * spawns its hostiles in. Kept in a SEPARATE list from [ENCOUNTER_ZONES] (these are NOT natural
     * encounters: the orchestrator edge-spawns them only while the matching bounty is ACTIVE), and each is
     * authored **geometrically disjoint** from every natural zone so a bounty kill is never confused with a
     * natural-encounter kill. The MVP map has one: a single [HostileArchetypes.RAIDER] in open space in
     * Alpha (well north of the start cluster, disjoint from the `alpha-raider-picket` natural zone east of
     * centre), targeted by Alpha Station's bounty. [TUNE]
     */
    val BOUNTY_TARGET_ZONES: List<EncounterZone> =
        listOf(
            EncounterZone(
                id = "bounty-alpha-raider",
                sectorId = "alpha",
                center = Vec2(0f, 1400f),
                radius = 220f,
                archetypeId = HostileArchetypes.RAIDER.id,
                hostileCount = 1,
            ),
        )

    /** The authored bounty target zone with [zoneId] (UC41), or null if no such zone is authored. */
    fun bountyTargetZone(zoneId: String): EncounterZone? = BOUNTY_TARGET_ZONES.firstOrNull { it.id == zoneId }

    /**
     * The authored **bounty contracts** (UC41) — which station posts which bounty. The MVP map has one:
     * Alpha Station contracts a kill-1 bounty on the `bounty-alpha-raider` zone. The generated offer is
     * credited to the issuing station's own faction (the Trade League), and its kill quota matches the
     * target zone's `hostileCount` so the contract is always completable. [TUNE]
     */
    val BOUNTY_CONTRACTS: List<BountyContract> =
        listOf(
            BountyContract(
                issuingStation = PoiId("alpha-station"),
                targetZoneId = "bounty-alpha-raider",
                killTarget = 1,
            ),
        )

    /** The bounty contracts posted by the station with [stationId] (UC41); empty if it posts none. */
    fun bountyContracts(stationId: PoiId): List<BountyContract> = BOUNTY_CONTRACTS.filter { it.issuingStation == stationId }

    /** Distance (world-units) of each gate from its sector centre — out toward the content edge. [TUNE] */
    private const val GATE_ORBIT_RADIUS: Float = 1300f

    /** Per-gate trigger radius (world-units); authored here, not hard-coded in traversal. [TUNE] */
    private const val GATE_TRIGGER_RADIUS: Float = 80f

    /** Docking-range radius (world-units) of each authored station. [TUNE] */
    private const val STATION_DOCKING_RADIUS: Float = 100f

    /** Mining-range radius (world-units) of each authored asteroid field. [TUNE] */
    private const val ASTEROID_MINING_RADIUS: Float = 100f

    private val ALPHA = SectorId("alpha")
    private val BETA = SectorId("beta")
    private val GAMMA = SectorId("gamma")

    /**
     * Build a fresh, validated [SectorWorld] for the MVP map. Cheap to call; each call re-validates
     * the (small) authored graph and fails fast if it were ever edited into an inconsistent state.
     */
    fun build(): SectorWorld =
        SectorWorld(
            listOf(
                Sector(
                    id = ALPHA,
                    displayName = "Alpha Reach",
                    contentExtent = CONTENT_EXTENT_WORLD_UNITS,
                    pois =
                        listOf(
                            gate("alpha-to-beta", angleDegrees = 0f, dest = BETA, destGate = "beta-to-alpha"),
                            gate("alpha-to-gamma", angleDegrees = 120f, dest = GAMMA, destGate = "gamma-to-alpha"),
                            // Alpha Station (DEALER): trade desk + a tier-I outfitting desk + a shipyard
                            // selling the courier hull (UC09 AC#3/#5), hiring crew (UC11 AC#2), and the
                            // one MVP station that lets the player build a personal station (UC15 AC#1).
                            station(
                                id = "alpha-station",
                                displayName = "Alpha Station",
                                position = Vec2(0f, 600f),
                                market = ALPHA_MARKET,
                                outfitMarket = ALPHA_OUTFIT,
                                shipyard = Shipyard.of(listOf(ShipRoster.SWIFT.id)),
                                hiresCrew = true,
                                // Alpha + Beta belong to the Trade League (UC14): a board mining mission
                                // here credits LEAGUE reputation, which unlocks Alpha's gated premium offer.
                                factionId = Factions.LEAGUE.id,
                                // The start-sector station is the MVP's build-capable station (UC15): the
                                // player can found / expand a personal station while docked here.
                                buildsStations = true,
                            ),
                            // A rich field whose total deposits (70 units) exceed DEFAULT_CAPACITY (50),
                            // so mining it to a full hold still leaves the field partially depleted (UC06).
                            asteroidField(
                                "alpha-belt",
                                Vec2(-600f, -400f),
                                mapOf(
                                    ResourceType.HYDROGEN to 20,
                                    ResourceType.WATER_ICE to 15,
                                    ResourceType.IRON_ORE to 25,
                                    ResourceType.COPPER to 10,
                                ),
                            ),
                            // Three no-transponder hidden contacts (UC10), authored at deliberately-spaced
                            // distances from the canonical SCAN_POINT (the sector centre, origin) so a scan
                            // exercises every reveal boundary against the starter ship's sensor range:
                            //   * alpha-derelict @ (300, 0)  — d = 300 wu: inside the base range (500), so a
                            //     scan on the un-upgraded starter reveals it (UC10 AC#2/#3).
                            //   * alpha-smuggler @ (-600, 0) — d = 600 wu: outside base (500) but inside the
                            //     SCANNER_I-upgraded range (650), so only an upgraded scan reveals it — the
                            //     sensor-upgrade payoff (UC10 AC#3).
                            //   * alpha-ghost    @ (0, -800) — d = 800 wu: outside even the upgraded range
                            //     (650), so no scan from the scan point ever reveals it (UC10 AC#6).
                            hiddenContact("alpha-derelict", Vec2(300f, 0f)),
                            hiddenContact("alpha-smuggler", Vec2(-600f, 0f)),
                            hiddenContact("alpha-ghost", Vec2(0f, -800f)),
                        ),
                ),
                Sector(
                    id = BETA,
                    displayName = "Beta Expanse",
                    contentExtent = CONTENT_EXTENT_WORLD_UNITS,
                    pois =
                        listOf(
                            gate("beta-to-alpha", angleDegrees = 180f, dest = ALPHA, destGate = "alpha-to-beta"),
                            gate("beta-to-gamma", angleDegrees = 60f, dest = GAMMA, destGate = "gamma-to-beta"),
                            // Beta Station (DEALER): trade desk + a tier-II outfitting desk + a shipyard
                            // selling the miner hull (UC09 AC#3/#5).
                            station(
                                id = "beta-station",
                                displayName = "Beta Station",
                                position = Vec2(300f, -300f),
                                market = BETA_MARKET,
                                outfitMarket = BETA_OUTFIT,
                                shipyard = Shipyard.of(listOf(ShipRoster.PROSPECTOR.id)),
                                // Beta is the Trade League's second core station (UC14).
                                factionId = Factions.LEAGUE.id,
                            ),
                            // A modest second field (26 units total) of tech-input resources.
                            asteroidField(
                                "beta-belt",
                                Vec2(-500f, 500f),
                                mapOf(
                                    ResourceType.SILICON to 12,
                                    ResourceType.ALUMINUM to 8,
                                    ResourceType.TITANIUM to 6,
                                ),
                            ),
                            // UC54 — the three additional POI types, clustered in Beta's DEEP SOUTH (y ≈ -1000),
                            // provably DISJOINT from every committed fixture's Beta path: the UC03 jump flies the
                            // y≈0 corridor east from beta-to-alpha (-1300,0); beta-station (300,-300), beta-belt
                            // (-500,500) and the beta-regroup-picket zone (600,700) all sit at y ≥ -300. The
                            // nearest of those to this cluster is ~1100 wu away — far outside any new POI's
                            // radius — so existing replays touch these zero times (the zero-fixture-regen lever;
                            // see docs/adr/0042-additional-poi-types.md). All within the 1800-wu content radius.
                            //   * beta-derelict @ (-600,-1000): scan-only wreck (UC10 detection), scavengeable.
                            //   * beta-distress @ (-300,-1050): broadcasting beacon; outside→inside triggers the event.
                            //   * beta-hazard   @ (-750,-700): broadcasting debris field; per-tick fuel drain inside.
                            derelict("beta-derelict", Vec2(-600f, -1000f)),
                            distressSignal("beta-distress", Vec2(-300f, -1050f)),
                            hazardZone("beta-hazard", Vec2(-750f, -700f)),
                        ),
                ),
                Sector(
                    id = GAMMA,
                    displayName = "Gamma Verge",
                    contentExtent = CONTENT_EXTENT_WORLD_UNITS,
                    pois =
                        listOf(
                            gate("gamma-to-beta", angleDegrees = 240f, dest = BETA, destGate = "beta-to-gamma"),
                            gate("gamma-to-alpha", angleDegrees = 300f, dest = ALPHA, destGate = "alpha-to-gamma"),
                            // Gamma Verge hosts the sector's JUNKYARD (UC09 AC#4): the only place the
                            // player can remove + sell used upgrades and refit. It also stocks a couple of
                            // tier-I parts so a refit (remove then re-install) can happen on the spot. No
                            // trade desk and no shipyard.
                            station(
                                id = "gamma-junkyard",
                                displayName = "Gamma Junkyard",
                                position = Vec2(-500f, 200f),
                                kind = StationKind.JUNKYARD,
                                outfitMarket = GAMMA_JUNKYARD_OUTFIT,
                                // UC47: the junkyard's discounted buy-used desk — a small subset of the
                                // catalog the budget player can acquire cheaply.
                                usedPartMarket = GAMMA_USED_PARTS,
                                // The Gamma junkyard is run by the unaligned Independents (UC14).
                                factionId = Factions.INDEPENDENTS.id,
                            ),
                        ),
                ),
            ),
        )

    private fun gate(
        id: String,
        angleDegrees: Float,
        dest: SectorId,
        destGate: String,
    ): JumpGate =
        JumpGate(
            id = PoiId(id),
            position = Vec2.fromAngle(Math.toRadians(angleDegrees.toDouble()).toFloat(), GATE_ORBIT_RADIUS),
            triggerRadius = GATE_TRIGGER_RADIUS,
            link = GateLink(destinationSector = dest, destinationGate = PoiId(destGate)),
        )

    /**
     * Alpha Station's fixed trade desk (UC08). Authored so cross-station arbitrage is possible
     * (AC#4): Titanium **sells** here for 50 — higher than Beta's Titanium **buy** of 40 — so a player
     * mining/buying Titanium at Beta profits selling it here. Conversely Iron Ore sells low here (8)
     * but buys cheap (10), feeding the opposite leg (sell it at Beta for 15). Each offer keeps
     * `0 <= sellPrice <= buyPrice` (a station never pays more to buy back than it charges to sell).
     */
    private val ALPHA_MARKET: StationMarket =
        StationMarket(
            mapOf(
                ResourceType.HYDROGEN to TradeOffer(buyPrice = 6, sellPrice = 4),
                ResourceType.WATER_ICE to TradeOffer(buyPrice = 5, sellPrice = 3),
                ResourceType.IRON_ORE to TradeOffer(buyPrice = 10, sellPrice = 8),
                ResourceType.COPPER to TradeOffer(buyPrice = 14, sellPrice = 10),
                ResourceType.SILICON to TradeOffer(buyPrice = 18, sellPrice = 14),
                ResourceType.TITANIUM to TradeOffer(buyPrice = 60, sellPrice = 50),
            ),
        )

    /**
     * Beta Station's fixed trade desk (UC08). The arbitrage counterpart to [ALPHA_MARKET]: Iron Ore
     * **sells** here for 15 — higher than Alpha's Iron Ore **buy** of 10 — so the Alpha→Beta iron run
     * profits; Titanium buys cheap here (40) to feed the Beta→Alpha titanium run. Prices differ from
     * Alpha's on every shared good so buy-low/sell-high always exists (AC#4).
     */
    private val BETA_MARKET: StationMarket =
        StationMarket(
            mapOf(
                ResourceType.HYDROGEN to TradeOffer(buyPrice = 8, sellPrice = 6),
                ResourceType.IRON_ORE to TradeOffer(buyPrice = 18, sellPrice = 15),
                ResourceType.COPPER to TradeOffer(buyPrice = 12, sellPrice = 9),
                ResourceType.SILICON to TradeOffer(buyPrice = 16, sellPrice = 12),
                ResourceType.ALUMINUM to TradeOffer(buyPrice = 11, sellPrice = 8),
                ResourceType.TITANIUM to TradeOffer(buyPrice = 40, sellPrice = 32),
            ),
        )

    /**
     * Alpha's tier-I outfitting desk (UC09 AC#3): an engine tune, a cargo pod, a fuel tank and a
     * scanner — entry-level parts to start specializing the starter ship. [TUNE]
     */
    private val ALPHA_OUTFIT: OutfitMarket =
        OutfitMarket.of(
            listOf(
                UpgradeCatalog.ENGINE_TUNE_I,
                UpgradeCatalog.CARGO_POD_I,
                UpgradeCatalog.FUEL_TANK_I,
                UpgradeCatalog.SCANNER_I,
            ),
        )

    /** Beta's tier-II outfitting desk (UC09 AC#3): the bigger engine + cargo upgrades. [TUNE] */
    private val BETA_OUTFIT: OutfitMarket =
        OutfitMarket.of(
            listOf(
                UpgradeCatalog.ENGINE_TUNE_II,
                UpgradeCatalog.CARGO_POD_II,
                UpgradeCatalog.CARGO_POD_I,
            ),
        )

    /** The Gamma junkyard's small refit stock (UC09 AC#4): a couple of tier-I parts to re-fit on site. [TUNE] */
    private val GAMMA_JUNKYARD_OUTFIT: OutfitMarket =
        OutfitMarket.of(
            listOf(
                UpgradeCatalog.ENGINE_TUNE_I,
                UpgradeCatalog.CARGO_POD_I,
            ),
        )

    /**
     * The Gamma junkyard's **buy-used** desk (UC47 AC#1): a subset of tier-I parts offered at a discount
     * to the budget player. Authored independently of [GAMMA_JUNKYARD_OUTFIT] (the full-price refit
     * stock); each is also a catalogued [UpgradeCatalog] part so its used price derives from the catalog
     * price × the discount fraction. [TUNE]
     */
    private val GAMMA_USED_PARTS: OutfitMarket =
        OutfitMarket.of(
            listOf(
                UpgradeCatalog.ENGINE_TUNE_I,
                UpgradeCatalog.CARGO_POD_I,
                UpgradeCatalog.SCANNER_I,
                UpgradeCatalog.FUEL_TANK_I,
            ),
        )

    private fun station(
        id: String,
        displayName: String,
        position: Vec2,
        market: StationMarket = StationMarket.EMPTY,
        kind: StationKind = StationKind.DEALER,
        outfitMarket: OutfitMarket = OutfitMarket.EMPTY,
        shipyard: Shipyard = Shipyard.EMPTY,
        hiresCrew: Boolean = false,
        factionId: FactionId? = null,
        buildsStations: Boolean = false,
        usedPartMarket: OutfitMarket = OutfitMarket.EMPTY,
    ): Station =
        Station(
            id = PoiId(id),
            position = position,
            displayName = displayName,
            dockingRadius = STATION_DOCKING_RADIUS,
            market = market,
            kind = kind,
            outfitMarket = outfitMarket,
            shipyard = shipyard,
            hiresCrew = hiresCrew,
            factionId = factionId,
            buildsStations = buildsStations,
            usedPartMarket = usedPartMarket,
        )

    private fun asteroidField(
        id: String,
        position: Vec2,
        deposits: Map<ResourceType, Int>,
    ): AsteroidField =
        AsteroidField(
            id = PoiId(id),
            position = position,
            miningRadius = ASTEROID_MINING_RADIUS,
            deposits = deposits,
        )

    /** A no-transponder [HiddenContact] (UC10) at [position], registering as a running ship once scanned. */
    private fun hiddenContact(
        id: String,
        position: Vec2,
    ): HiddenContact =
        HiddenContact(
            id = PoiId(id),
            position = position,
        )

    /** A scan-only [Derelict]/wreck (UC54) at [position] with the default scavenge radius. */
    private fun derelict(
        id: String,
        position: Vec2,
    ): Derelict =
        Derelict(
            id = PoiId(id),
            position = position,
        )

    /** A broadcasting [DistressSignal] (UC54) at [position] with the default trigger radius. */
    private fun distressSignal(
        id: String,
        position: Vec2,
    ): DistressSignal =
        DistressSignal(
            id = PoiId(id),
            position = position,
        )

    /** A broadcasting [HazardZone] (UC54) at [position] with the default radius + fuel-drain rate. */
    private fun hazardZone(
        id: String,
        position: Vec2,
    ): HazardZone =
        HazardZone(
            id = PoiId(id),
            position = position,
        )
}
