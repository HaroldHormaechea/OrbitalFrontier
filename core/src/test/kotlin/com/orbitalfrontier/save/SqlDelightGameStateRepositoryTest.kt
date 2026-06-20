package com.orbitalfrontier.save

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.crew.CrewAssignment
import com.orbitalfrontier.crew.CrewId
import com.orbitalfrontier.crew.CrewMember
import com.orbitalfrontier.crew.CrewOrder
import com.orbitalfrontier.crew.CrewRole
import com.orbitalfrontier.crew.CrewRoster
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.outfit.InstallResult
import com.orbitalfrontier.outfit.Loadout
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.outfit.UpgradeId
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.OwnedShip
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipKinematics
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.station.OwnedStation
import com.orbitalfrontier.station.StationFunction
import com.orbitalfrontier.station.StationId
import com.orbitalfrontier.station.StationModuleCatalog
import com.orbitalfrontier.station.StationRegistry
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorGenerator
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldSeed
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence round-trip + error-path tests for [SqlDelightGameStateRepository], exercised against
 * an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code that runs on the Android driver
 * on device). Covers UC04 AC#1 (first-run "no save"), AC#3 (transactional, corruption-safe writes),
 * AC#6/#7 (full-state save → reload round-trip with EXACT equality, JVM-testable serialization).
 *
 * "App restart" is simulated by constructing a fresh repository over the *same* live driver (the
 * in-memory DB persists for the lifetime of the connection), so a reload genuinely goes back
 * through SQL rather than reading an in-process field.
 *
 * EXACT equality (no tolerance) is the contract here: the repository owns the single Float↔Double
 * conversion at the persistence boundary, and `Float -> Double -> Float` returns the original Float
 * bit-for-bit, so a save → reload of a [WorldState] is value-equal to the original (AC#7).
 */
class SqlDelightGameStateRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier
    private lateinit var logger: CapturingLogger

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
        logger = CapturingLogger()
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
    }

    private fun newRepository() = SqlDelightGameStateRepository(database, logger, com.orbitalfrontier.platform.FixedClock)

    /** The active ship's awkward-Float kinematics shared by every sample state (exact round-trip bait). */
    private val sampleKinematics =
        ShipKinematics(
            position = Vec2(123.456f, -987.654f),
            velocity = Vec2(0.125f, -64.0f),
            headingRadians = 1.5707963f,
            angularVelocity = -0.3333333f,
        )

    /**
     * A non-trivial world state with awkward Float values, to make an exact round-trip meaningful.
     * [cargo] is on the active ship of the (single starter ship) fleet; override it to exercise the
     * hold (UC09 moved cargo/fuel onto [com.orbitalfrontier.ship.OwnedShip], so it can no longer be
     * set via `WorldState.copy`).
     */
    private fun sampleState(cargo: Cargo = Cargo.empty()): WorldState =
        WorldState(
            currentSector = SectorId("beta"),
            fleet = singleShipFleet(kinematics = sampleKinematics, cargo = cargo),
        )

    // --- AC#1: first run has no save ---

    @Test
    fun `a fresh database reports no save`() {
        val repo = newRepository()

        assertNull("loadGameState must be null before anything is saved", repo.loadGameState())
        assertFalse("hasSave must be false before anything is saved", repo.hasSave())
    }

    // --- AC#6/#7: full-state save -> reload round-trip with EXACT equality ---

    @Test
    fun `saved world state round-trips exactly through a reload`() {
        val state = sampleState()
        newRepository().saveGameState(state)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = newRepository().loadGameState()

        // Exact value equality — sector + every ship kinematic field, no tolerance (AC#7).
        assertEquals(state, reloaded)
    }

    // --- UC05 AC#4: dock state is part of game state and round-trips ---

    @Test
    fun `a docked world state round-trips with its station id`() {
        val docked = sampleState().copy(dockedStation = PoiId("alpha-station"))
        newRepository().saveGameState(docked)

        val reloaded = newRepository().loadGameState()

        // Exact equality includes the docked station id (the new v3 docked_station_id column).
        assertEquals(docked, reloaded)
        assertEquals(PoiId("alpha-station"), reloaded?.dockedStation)
    }

    @Test
    fun `an in-flight world state round-trips as not docked (null dock column)`() {
        val inFlight = sampleState() // dockedStation defaults to null (in flight)
        assertNull("precondition: the sample state is in flight", inFlight.dockedStation)
        newRepository().saveGameState(inFlight)

        val reloaded = newRepository().loadGameState()

        assertEquals(inFlight, reloaded)
        assertNull("an in-flight save must reload as not docked", reloaded?.dockedStation)
    }

    @Test
    fun `undocking overwrites a previously docked save back to in flight`() {
        val repo = newRepository()
        repo.saveGameState(sampleState().copy(dockedStation = PoiId("beta-station")))
        // A later in-flight save (the player undocked) clears the dock column on reload.
        repo.saveGameState(sampleState())

        assertNull("the latest in-flight save wins", newRepository().loadGameState()?.dockedStation)
    }

    // --- UC06 AC#4/#5: cargo + field depletion are part of game state and round-trip ---

    @Test
    fun `cargo and field depletion round-trip exactly through a reload`() {
        val state =
            sampleState(
                cargo =
                    Cargo(
                        mapOf(ResourceType.HYDROGEN to 12, ResourceType.IRON_ORE to 7),
                        Cargo.DEFAULT_CAPACITY,
                    ),
            ).copy(
                fieldDepletion =
                    mapOf(
                        // An exhausted resource (0 remaining) and a partially-mined one in one field…
                        PoiId("alpha-belt") to mapOf(ResourceType.HYDROGEN to 0, ResourceType.IRON_ORE to 18),
                        // …and a second, separately-tracked field.
                        PoiId("beta-belt") to mapOf(ResourceType.SILICON to 5),
                    ),
            )
        newRepository().saveGameState(state)

        val reloaded = newRepository().loadGameState()

        // Exact value equality covers cargo (capacity reconstructed at DEFAULT_CAPACITY) + depletion.
        assertEquals(state, reloaded)
        assertEquals(Cargo.DEFAULT_CAPACITY, reloaded?.cargo?.capacity)
    }

    @Test
    fun `an empty hold and no field depletion round-trip as empty`() {
        val state = sampleState() // cargo defaults to an empty hold; fieldDepletion to an empty map
        assertTrue("precondition: the sample hold is empty", state.cargo.contents.isEmpty())
        newRepository().saveGameState(state)

        val reloaded = newRepository().loadGameState()

        assertEquals(state, reloaded)
        assertEquals("an empty hold reloads empty at the default capacity", Cargo.empty(), reloaded?.cargo)
        assertTrue("no depletion rows reload as 'all fields pristine'", reloaded?.fieldDepletion?.isEmpty() == true)
    }

    @Test
    fun `cargo entries with zero units are not persisted`() {
        // Cargo.add never stores zeros, but a hand-built contents map could; the repo skips them.
        val withZero = sampleState(cargo = Cargo(mapOf(ResourceType.COPPER to 0, ResourceType.NICKEL to 4), Cargo.DEFAULT_CAPACITY))
        newRepository().saveGameState(withZero)

        val reloaded = newRepository().loadGameState()

        assertEquals("only the non-zero entry survives", mapOf(ResourceType.NICKEL to 4), reloaded?.cargo?.contents)
    }

    @Test
    fun `the latest cargo snapshot wins (a shrinking hold drops old rows)`() {
        val repo = newRepository()
        repo.saveGameState(
            sampleState(
                cargo = Cargo(mapOf(ResourceType.HYDROGEN to 9, ResourceType.IRON_ORE to 4), Cargo.DEFAULT_CAPACITY),
            ),
        )
        // A later save with fewer resources must not leave stale rows behind (full-snapshot rewrite).
        repo.saveGameState(
            sampleState(cargo = Cargo(mapOf(ResourceType.HYDROGEN to 2), Cargo.DEFAULT_CAPACITY)),
        )

        assertEquals(mapOf(ResourceType.HYDROGEN to 2), newRepository().loadGameState()?.cargo?.contents)
    }

    @Test
    fun `hasSave becomes true once a state is saved`() {
        val repo = newRepository()
        repo.saveGameState(sampleState())

        assertTrue(repo.hasSave())
    }

    @Test
    fun `the latest saved world state wins`() {
        val repo = newRepository()
        repo.saveGameState(sampleState())

        val later =
            WorldState(
                currentSector = SectorId("gamma"),
                fleet =
                    singleShipFleet(
                        kinematics = ShipKinematics(position = Vec2(7f, 8f), velocity = Vec2(-1f, 2f), headingRadians = 0.5f),
                    ),
            )
        repo.saveGameState(later)

        assertEquals(later, newRepository().loadGameState())
    }

    // --- UC09 AC#5/#6: a multi-ship fleet with per-ship loadouts round-trips exactly ---

    private fun loadoutOf(
        category: SlotCategory,
        slotCount: Int,
        id: UpgradeId,
    ): Loadout = (Loadout.EMPTY.install(category, slotCount, id) as InstallResult.Installed).loadout

    @Test
    fun `a multi-ship fleet with per-ship loadouts, cargo, and fuel round-trips exactly`() {
        // Ship 0: the starter, with a cargo pod installed (capacity re-derived to 75), a partial hold,
        // and a partial tank — built via withLoadout so cargo/fuel capacities are the derived stats the
        // repository reconstructs on load (otherwise the round-trip would not be exactly equal).
        val ship0 =
            OwnedShip.fresh(ShipId(0), ShipRoster.STARTER, Vec2(1.5f, -2.5f))
                .withLoadout(loadoutOf(SlotCategory.CARGO, ShipRoster.STARTER.slotCount(SlotCategory.CARGO), UpgradeCatalog.CARGO_POD_I))
                .let {
                    it.copy(
                        cargo = Cargo(mapOf(ResourceType.IRON_ORE to 10), it.cargo.capacity),
                        fuel = Fuel(level = 73.5f, capacity = it.fuel.capacity),
                    )
                }

        // Ship 1: a Swift with an engine installed (engine has no capacity delta), at a different spot.
        val ship1 =
            OwnedShip.fresh(ShipId(1), ShipRoster.SWIFT, Vec2(40f, 60f))
                .withLoadout(
                    loadoutOf(SlotCategory.ENGINES, ShipRoster.SWIFT.slotCount(SlotCategory.ENGINES), UpgradeCatalog.ENGINE_TUNE_I),
                )

        // The Swift is the active ship; credits non-trivial.
        val state =
            WorldState(
                currentSector = SectorId("beta"),
                fleet = Fleet(listOf(ship0, ship1), ShipId(1)),
                credits = 2900L,
            )

        newRepository().saveGameState(state)
        val reloaded = newRepository().loadGameState()

        // EXACT equality covers both ships' kinematics/type/cargo/fuel/loadout + the active id (AC#6).
        assertEquals(state, reloaded)

        // Detail spot-checks on the reconstructed fleet.
        val fleet = reloaded!!.fleet
        assertEquals(2, fleet.ships.size)
        assertEquals(ShipId(1), fleet.activeShipId)
        assertEquals("the starter's cargo pod survives", 1, fleet.ship(ShipId(0))!!.loadout.installedCount(SlotCategory.CARGO))
        assertEquals("cargo capacity re-derived with the pod", Cargo.DEFAULT_CAPACITY + 25, fleet.ship(ShipId(0))!!.cargo.capacity)
        assertEquals("the Swift's engine survives", 1, fleet.ship(ShipId(1))!!.loadout.installedCount(SlotCategory.ENGINES))
        assertEquals(ShipRoster.SWIFT.id, fleet.ship(ShipId(1))!!.type.id)
    }

    // --- UC11 AC#4: per-ship crew counts round-trip (including across a multi-ship fleet) ---

    @Test
    fun `crew counts round-trip exactly across a multi-ship fleet`() {
        // Ship 0: a starter (crew capacity 2) crewed to a full 2. Ship 1: a Swift (capacity 2) with a
        // single crew aboard. withCrew is the single crew-mutation point (clamped to 0..capacity).
        val ship0 = OwnedShip.fresh(ShipId(0), ShipRoster.STARTER, Vec2(1f, 2f)).withCrew(2)
        val ship1 = OwnedShip.fresh(ShipId(1), ShipRoster.SWIFT, Vec2(3f, 4f)).withCrew(1)
        assertEquals("precondition: ship0 crewed to 2", 2, ship0.crew)
        assertEquals("precondition: ship1 crewed to 1", 1, ship1.crew)

        val state =
            WorldState(
                currentSector = SectorId("beta"),
                fleet = Fleet(listOf(ship0, ship1), ShipId(0)),
                credits = 100L,
            )
        newRepository().saveGameState(state)

        val reloaded = newRepository().loadGameState()

        // UC50: this state carries an EMPTY crew roster (count-only), so on load the repository reconciles it
        // up to the per-ship counts — synthesizing 2 + 1 = 3 generic members. The reload GAINS those
        // identities (intended for migrated/count-only saves), so the expectation is reconciled to match.
        // EXACT equality then covers each ship's crew count alongside its kinematics/type/cargo/fuel/loadout.
        val expected = state.copy(crewRoster = state.crewRoster.reconciledToCounts(state.fleet))
        assertEquals(expected, reloaded)
        assertEquals("ship0's full crew survives", 2, reloaded!!.fleet.ship(ShipId(0))!!.crew)
        assertEquals("ship1's partial crew survives", 1, reloaded.fleet.ship(ShipId(1))!!.crew)
        assertEquals(
            "the migrated counts synthesize 3 identities (2 on ship0, 1 on ship1)",
            3,
            reloaded.crewRoster.members.size,
        )
        assertEquals("ship0 gets 2 synthesized members", 2, reloaded.crewRoster.forShip(ShipId(0)).size)
        assertEquals("ship1 gets 1 synthesized member", 1, reloaded.crewRoster.forShip(ShipId(1)).size)
    }

    @Test
    fun `a fresh uncrewed ship round-trips as zero crew`() {
        val state = sampleState() // a single starter ship, crew defaults to 0
        assertEquals("precondition: the sample ship is uncrewed", 0, state.fleet.active.crew)
        newRepository().saveGameState(state)

        val reloaded = newRepository().loadGameState()

        assertEquals(state, reloaded)
        assertEquals("an uncrewed ship reloads with zero crew", 0, reloaded!!.fleet.active.crew)
    }

    // --- UC50 AC#1/AC#4: crew IDENTITIES (name + role) round-trip via the crew_member table ---

    @Test
    fun `a crew roster of named, roled members round-trips exactly through the crew_member table`() {
        // Ship 0 (starter, cap 2) crewed to 2; ship 1 (Swift, cap 2) crewed to 1 — counts that exactly
        // match the roster below, so the load-time reconcile is a no-op and the REAL names/roles survive.
        val ship0 = OwnedShip.fresh(ShipId(0), ShipRoster.STARTER, Vec2(1f, 2f)).withCrew(2)
        val ship1 = OwnedShip.fresh(ShipId(1), ShipRoster.SWIFT, Vec2(3f, 4f)).withCrew(1)
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0)),
                    CrewMember(CrewId(1), "Ben", CrewRole.GUNNER, ShipId(0)),
                    CrewMember(CrewId(2), "Cy", CrewRole.ENGINEER, ShipId(1)),
                ),
            )
        // Precondition: the roster already satisfies the per-ship-count invariant, so it survives verbatim.
        assertEquals("precondition: roster matches ship0 count", 2, roster.forShip(ShipId(0)).size)
        assertEquals("precondition: roster matches ship1 count", 1, roster.forShip(ShipId(1)).size)

        val state =
            WorldState(
                currentSector = SectorId("beta"),
                fleet = Fleet(listOf(ship0, ship1), ShipId(0)),
                credits = 100L,
                crewRoster = roster,
            )
        newRepository().saveGameState(state)
        val reloaded = newRepository().loadGameState()

        // EXACT equality — the named, roled identities survive the round-trip (no synthesis, no reconcile).
        assertEquals(state, reloaded)
        assertEquals("the full roster survives verbatim", roster, reloaded!!.crewRoster)
        assertEquals(
            "Ada keeps her PILOT role on ship0",
            CrewRole.PILOT,
            reloaded.crewRoster.member(CrewId(0))!!.role,
        )
        assertEquals("Cy keeps his name across the round-trip", "Cy", reloaded.crewRoster.member(CrewId(2))!!.name)
    }

    @Test
    fun `a crew reassignment persists across save and reload`() {
        // Ship 0 (starter, cap 2) crewed to 2; ship 1 (Swift, cap 2) crewed to 1 (one free berth).
        val ship0 = OwnedShip.fresh(ShipId(0), ShipRoster.STARTER, Vec2(1f, 2f)).withCrew(2)
        val ship1 = OwnedShip.fresh(ShipId(1), ShipRoster.SWIFT, Vec2(3f, 4f)).withCrew(1)
        val roster =
            CrewRoster(
                listOf(
                    CrewMember(CrewId(0), "Ada", CrewRole.PILOT, ShipId(0)),
                    CrewMember(CrewId(1), "Ben", CrewRole.GUNNER, ShipId(0)),
                    CrewMember(CrewId(2), "Cy", CrewRole.ENGINEER, ShipId(1)),
                ),
            )
        val fleet = Fleet(listOf(ship0, ship1), ShipId(0))

        // Move Ben (id 1) from ship0 to ship1 via the pure resolver — count + identity move together.
        val result = CrewAssignment.resolve(fleet, roster, CrewOrder.Reassign(CrewId(1), ShipId(1)))
        assertTrue("precondition: the reassignment is applied", result.changed)
        assertEquals("ship0 drops to 1 crew", 1, result.fleet.ship(ShipId(0))!!.crew)
        assertEquals("ship1 rises to 2 crew", 2, result.fleet.ship(ShipId(1))!!.crew)

        val state =
            WorldState(
                currentSector = SectorId("beta"),
                fleet = result.fleet,
                credits = 100L,
                crewRoster = result.roster,
            )
        newRepository().saveGameState(state)
        val reloaded = newRepository().loadGameState()

        // EXACT equality — the post-reassignment counts AND the moved identity survive the round-trip.
        assertEquals(state, reloaded)
        assertEquals("Ben is now assigned to ship1", ShipId(1), reloaded!!.crewRoster.member(CrewId(1))!!.assignedShipId)
        assertEquals("Ben keeps his GUNNER role through the move", CrewRole.GUNNER, reloaded.crewRoster.member(CrewId(1))!!.role)
        assertEquals("ship1 now carries 2 roster members", 2, reloaded.crewRoster.forShip(ShipId(1)).size)
    }

    // --- UC15 AC#1/#3/#4: owned stations + their modules persist and round-trip (additive) ---

    @Test
    fun `multiple owned stations with gap-tolerant module slots round-trip exactly`() {
        // Station 0: a commerce hub in slot 0 and a retrofit bay in slot 2 (slot 1 is a deliberate gap).
        // Station 1: a single commerce hub. Two stations exercises AC#3 (multiple owned stations).
        val station0 =
            OwnedStation(
                id = StationId(0),
                sector = SectorId("alpha"),
                modules =
                    mapOf(
                        0 to StationModuleCatalog.COMMERCE_HUB,
                        2 to StationModuleCatalog.RETROFIT_BAY,
                    ),
            )
        val station1 =
            OwnedStation(
                id = StationId(1),
                sector = SectorId("beta"),
                modules = mapOf(0 to StationModuleCatalog.COMMERCE_HUB),
            )
        val state = sampleState().copy(stations = StationRegistry(listOf(station0, station1)))

        newRepository().saveGameState(state)
        val reloaded = newRepository().loadGameState()

        // EXACT equality covers both stations' ids, anchor sectors, and gap-tolerant module slot maps (AC#4).
        assertEquals(state, reloaded)

        // Detail spot-checks on the reconstructed registry.
        val stations = reloaded!!.stations
        assertEquals("both owned stations survive (AC#3)", 2, stations.size)
        val s0 = stations.station(StationId(0))!!
        assertEquals("station 0 keeps its commerce hub in slot 0", StationModuleCatalog.COMMERCE_HUB, s0.moduleAt(0))
        assertNull("station 0's slot 1 stays an empty gap", s0.moduleAt(1))
        assertEquals("station 0 keeps its retrofit bay in slot 2", StationModuleCatalog.RETROFIT_BAY, s0.moduleAt(2))
        assertEquals(
            "station 0 offers both functions (AC#2)",
            setOf(StationFunction.COMMERCE, StationFunction.RETROFIT),
            s0.availableFunctions(),
        )
    }

    @Test
    fun `the latest station snapshot wins — a re-save rewrites a station's modules`() {
        val repo = newRepository()
        val before =
            OwnedStation(id = StationId(0), sector = SectorId("alpha"), modules = mapOf(0 to StationModuleCatalog.COMMERCE_HUB))
        repo.saveGameState(sampleState().copy(stations = StationRegistry(listOf(before))))

        // The same station gains a second module; the per-station module rewrite (delete-then-insert) must
        // leave no stale rows and add the new one.
        val after = before.addModule(StationModuleCatalog.RETROFIT_BAY)
        repo.saveGameState(sampleState().copy(stations = StationRegistry(listOf(after))))

        val reloaded = newRepository().loadGameState()!!.stations.station(StationId(0))!!
        assertEquals("the station now has two modules", 2, reloaded.moduleCount)
        assertEquals("the retrofit bay was added at slot 1", StationModuleCatalog.RETROFIT_BAY, reloaded.moduleAt(1))
    }

    // --- UC15 AC#4: backward compatibility — a pre-UC15 save reads back with zero owned stations ---

    @Test
    fun `a save with no stations reads back with the empty registry (backward-compatible)`() {
        val state = sampleState() // stations defaults to StationRegistry.EMPTY (no owned stations)
        assertTrue("precondition: the sample state owns no stations", state.stations.isEmpty)
        newRepository().saveGameState(state)

        val reloaded = newRepository().loadGameState()

        assertEquals(state, reloaded)
        assertTrue("a station-less save reloads as the empty registry", reloaded!!.stations.isEmpty)
        assertEquals("zero owned stations (additive, no breaking change)", 0, reloaded.stations.size)
    }

    // --- UC53 AC#3: the world seed persists across save/reload AND regenerates an identical world ---

    @Test
    fun `a non-MVP world seed round-trips and regenerates an identical world`() {
        val seed = WorldSeed(987654321L)
        val state = sampleState().copy(worldSeed = seed)
        newRepository().saveGameState(state)

        // Fresh repository over the same DB == app restart; the reload goes back through SQL.
        val reloaded = newRepository().loadGameState()

        // Exact value equality (worldSeed is part of WorldState) — the seed survived the new world_seed column.
        assertEquals(state, reloaded)
        assertEquals("the persisted world seed reloads unchanged (AC#3)", seed, reloaded!!.worldSeed)

        // …and the reloaded seed regenerates a world structurally identical to the original seed's world
        // (the design goal: store the seed, regenerate the graph). SectorWorld has no equals, so compare
        // the data-class sector lists field-by-field.
        val original = SectorGenerator.generate(seed)
        val regenerated = SectorGenerator.generate(reloaded.worldSeed)
        assertEquals(
            "the reloaded seed must regenerate the same world (store-the-seed, regenerate-the-graph)",
            original.sectors,
            regenerated.sectors,
        )
    }

    @Test
    fun `a save with no explicit seed reads back as the MVP seed (backward-compatible)`() {
        val state = sampleState() // worldSeed defaults to WorldSeed.MVP (the authored map)
        assertEquals("precondition: the sample state is the MVP seed", WorldSeed.MVP, state.worldSeed)
        newRepository().saveGameState(state)

        val reloaded = newRepository().loadGameState()

        assertEquals(state, reloaded)
        assertEquals("a seed-less save reloads as WorldSeed.MVP (world_seed DEFAULT 0)", WorldSeed.MVP, reloaded!!.worldSeed)
    }

    // --- AC#3: transactional, corruption-safe write (graceful degradation) ---

    @Test
    fun `a failed write is caught and logged, does not throw, and leaves the prior good save intact`() {
        // 1) Persist a good save through the real driver.
        val good = sampleState()
        val goodRepo = newRepository()
        goodRepo.saveGameState(good)
        assertEquals("precondition: the good save is readable", good, goodRepo.loadGameState())

        // 2) A repository whose writes fail mid-transaction (the upsert throws), sharing the same
        //    underlying connection so we can verify the prior save survived the rollback.
        val failingDatabase = OrbitalFrontier(WriteFailingDriver(driver))
        val failingRepo = SqlDelightGameStateRepository(failingDatabase, logger, com.orbitalfrontier.platform.FixedClock)

        val doomed =
            WorldState(
                currentSector = SectorId("gamma"),
                fleet = singleShipFleet(kinematics = ShipKinematics(position = Vec2(999f, 999f))),
            )
        // Autosave-style: the failure is caught and logged, never propagated.
        failingRepo.saveGameState(doomed)

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
        // 3) The transaction rolled back: the previous good save is untouched (no corruption).
        assertEquals("the prior good save must remain intact", good, goodRepo.loadGameState())
    }

    @Test
    fun `hasSave degrades to false instead of throwing when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail

        assertFalse("an unreadable DB is treated as no save", repo.hasSave())
        assertTrue("the failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    /** Logger that records WARN/ERROR messages so error-path tests can assert on them. */
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun debug(
            tag: String,
            message: String,
        ) = Unit

        override fun info(
            tag: String,
            message: String,
        ) = Unit

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            warnings += message
        }

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            errors += message
        }
    }

    /**
     * [SqlDriver] decorator that delegates reads and transaction control to [delegate] but makes
     * every write ([execute]) throw. Used to induce a mid-transaction failure on the real
     * connection so the repository's catch-log-rollback path (AC#3) is exercised against actual
     * SQLite rather than a hand-rolled fake.
     */
    private class WriteFailingDriver(private val delegate: SqlDriver) : SqlDriver {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = throw RuntimeException("induced write failure")

        override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

        override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

        override fun addListener(
            vararg queryKeys: String,
            listener: Query.Listener,
        ) = delegate.addListener(queryKeys = queryKeys, listener = listener)

        override fun removeListener(
            vararg queryKeys: String,
            listener: Query.Listener,
        ) = delegate.removeListener(queryKeys = queryKeys, listener = listener)

        override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(queryKeys = queryKeys)

        override fun close() = delegate.close()
    }
}
