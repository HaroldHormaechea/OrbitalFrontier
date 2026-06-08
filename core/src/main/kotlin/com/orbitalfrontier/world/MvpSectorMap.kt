package com.orbitalfrontier.world

import com.orbitalfrontier.combat.EncounterZone
import com.orbitalfrontier.combat.HostileArchetypes
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.economy.TradeOffer
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
     * The one authored **natural encounter** zone of the MVP map (UC13) — a patch of hostile space in
     * the START_SECTOR (Alpha), east of the centre, so the player flying out from the start cluster
     * crosses into it and is ambushed (edge-triggered, [EncounterSpawner]). A single [HostileArchetypes
     * .RAIDER] spawns, which the AC#8 recorded playthrough flies into, fires on and destroys. Tagged with
     * the sector's String id so `combat` needs no `world` dependency. [TUNE]
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
        )

    /** The authored encounter zones in [sectorId] (UC13) — what the screen checks for an edge-crossing. */
    fun encounterZones(sectorId: SectorId): List<EncounterZone> = ENCOUNTER_ZONES.filter { it.sectorId == sectorId.value }

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
                            // selling the courier hull (UC09 AC#3/#5) and hiring crew (UC11 AC#2).
                            station(
                                id = "alpha-station",
                                displayName = "Alpha Station",
                                position = Vec2(0f, 600f),
                                market = ALPHA_MARKET,
                                outfitMarket = ALPHA_OUTFIT,
                                shipyard = Shipyard.of(listOf(ShipRoster.SWIFT.id)),
                                hiresCrew = true,
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

    private fun station(
        id: String,
        displayName: String,
        position: Vec2,
        market: StationMarket = StationMarket.EMPTY,
        kind: StationKind = StationKind.DEALER,
        outfitMarket: OutfitMarket = OutfitMarket.EMPTY,
        shipyard: Shipyard = Shipyard.EMPTY,
        hiresCrew: Boolean = false,
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
}
