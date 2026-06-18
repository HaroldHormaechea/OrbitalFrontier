package com.orbitalfrontier.render

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionId
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionSource
import com.orbitalfrontier.mission.MissionStatus
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.world.PoiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Pure (libGDX-free, JVM-only) coverage for the expanded flight HUD's view-model (UC34).
 *
 * The HUD readout maths — the objective line ("display == turn-in" cargo read), the
 * credits/cargo/sector/heading assembly, and the line-length cap the variable readouts ellipsize to —
 * lives in [HudViewModel] / [HudLayout] precisely so it can be unit-tested without a GL context
 * (ADR 0001). [HudRenderer] is draw-only; its GL-bound wiring is pinned by the source-anchored
 * [com.orbitalfrontier.screen.Uc34ExpandedHudGuardTest].
 *
 * ACs covered here:
 *  - **AC#1** — credits, cargo fill (used/total) and the current sector name are assembled into the model
 *    alongside speed/heading/fuel.
 *  - **AC#2** — the objective line shows target + progress for the active mission, and is `null`
 *    (hidden) when no mission is active.
 *  - **AC#4** — [HudViewModel.build] reads the live simulation state each call, so the readouts update.
 *  - small-screen pitfall — [HudLayout.ellipsize] caps a long variable line to [HudLayout.MAX_LINE_CHARS].
 */
class HudViewModelTest {
    // --- AC#2: MINING objective — "<resource> <held>/<quota>", held clamped to the quota ------------

    @Test
    fun `mining objective shows held over quota using the resource display name`() {
        val objective = HudViewModel.objectiveLine(miningMission(quota = 6), cargoOf(ResourceType.TITANIUM to 4))
        // Display name ("Titanium"), not the enum name ("TITANIUM"); held is the in-hold quota resource.
        assertEquals("Titanium 4/6", objective)
    }

    @Test
    fun `mining held is clamped to the quota when the hold carries more than the quota`() {
        // The displayed progress is min(held, quota) — the SAME read Missions uses to gate a turn-in, so
        // the readout and the turn-in condition never disagree (display math == turn-in math).
        val objective = HudViewModel.objectiveLine(miningMission(quota = 6), cargoOf(ResourceType.TITANIUM to 9))
        assertEquals("Titanium 6/6", objective)
    }

    @Test
    fun `mining held is zero when the quota resource is not in the hold`() {
        val objective = HudViewModel.objectiveLine(miningMission(quota = 6), Cargo.empty())
        assertEquals("Titanium 0/6", objective)
    }

    // --- AC#2: COURIER objective — "<pickup>→<dest>", narrowing to "→<dest>" once picked up ----------

    @Test
    fun `courier objective shows the full pickup-arrow-destination route before pickup`() {
        val objective = HudViewModel.objectiveLine(courierMission("ALPHA", "BETA", pickedUp = false), Cargo.empty())
        assertEquals("ALPHA${ARROW}BETA", objective)
        assertTrue("courier route uses the bundled arrow glyph", objective!!.contains(ARROW))
    }

    @Test
    fun `courier objective narrows to arrow-destination once the parcel is aboard`() {
        val objective = HudViewModel.objectiveLine(courierMission("ALPHA", "BETA", pickedUp = true), Cargo.empty())
        assertEquals("${ARROW}BETA", objective)
    }

    // --- AC#2: the objective line hides when no mission is active ----------------------------------

    @Test
    fun `objectiveLine is null for a null mission`() {
        assertNull(HudViewModel.objectiveLine(null, Cargo.empty()))
    }

    @Test
    fun `build hides the objective when the log has no active mission`() {
        // EMPTY log -> no active mission -> objective null.
        assertNull(model(missionLog = MissionLog.EMPTY).objective)
        // A log whose only mission is terminal (COMPLETED) is not ACTIVE, so still no objective.
        val completed = miningMission(quota = 6, status = MissionStatus.COMPLETED)
        assertNull(model(missionLog = MissionLog(accepted = listOf(completed))).objective)
    }

    @Test
    fun `build surfaces the first active mission's objective`() {
        val mission = miningMission(quota = 6)
        val model = model(missionLog = MissionLog(accepted = listOf(mission)), cargo = cargoOf(ResourceType.TITANIUM to 2))
        assertEquals("Titanium 2/6", model.objective)
    }

    // --- AC#1: credits / cargo fill / sector are assembled alongside speed/heading/fuel ------------

    @Test
    fun `build assembles credits, cargo fill and sector name`() {
        val model =
            model(
                credits = 12_345L,
                cargo = Cargo(contents = mapOf(ResourceType.IRON_ORE to 7, ResourceType.COPPER to 3), capacity = 50),
                sectorName = "Tycho Reach",
            )
        assertEquals(12_345L, model.credits)
        assertEquals("cargo used = sum of all held units", 10, model.cargoUsed)
        assertEquals(50, model.cargoCapacity)
        assertEquals("Tycho Reach", model.sectorName)
    }

    @Test
    fun `build normalises heading radians to a whole-degree bearing wrapped into zero to 360`() {
        assertEquals(0, model(headingRadians = 0f).headingDegrees)
        assertEquals(180, model(headingRadians = PI.toFloat()).headingDegrees)
        // A negative bearing wraps up into [0, 360): -PI/2 -> 270 degrees.
        assertEquals(270, model(headingRadians = -(PI.toFloat() / 2f)).headingDegrees)
    }

    @Test
    fun `build passes speed, fuel and status flags straight through`() {
        val model =
            model(
                speed = 42f,
                fuelLevel = 8f,
                fuelCapacity = 20f,
                lowFuel = true,
                inCombat = true,
            )
        assertEquals(42f, model.speed, 1e-4f)
        assertEquals(8f, model.fuelLevel, 1e-4f)
        assertEquals(20f, model.fuelCapacity, 1e-4f)
        assertTrue(model.lowFuel)
        assertTrue(model.inCombat)
    }

    // --- AC#4: each build() call reflects the current simulation state (live rebuild) --------------

    @Test
    fun `build reflects the live state on every call`() {
        val first =
            model(
                credits = 100L,
                cargo = cargoOf(ResourceType.TITANIUM to 1),
                sectorName = "Sector A",
                missionLog = MissionLog(accepted = listOf(miningMission(quota = 6))),
            )
        // The sim advances: more credits, more cargo, a different sector, mining progressed.
        val second =
            model(
                credits = 250L,
                cargo = cargoOf(ResourceType.TITANIUM to 4),
                sectorName = "Sector B",
                missionLog = MissionLog(accepted = listOf(miningMission(quota = 6))),
            )
        assertEquals(100L, first.credits)
        assertEquals("Titanium 1/6", first.objective)
        assertEquals("Sector A", first.sectorName)

        assertEquals(250L, second.credits)
        assertEquals("Titanium 4/6", second.objective)
        assertEquals("Sector B", second.sectorName)
    }

    // --- small-screen pitfall: a long variable line is capped to MAX_LINE_CHARS via ellipsize ------

    @Test
    fun `ellipsize leaves a short line untouched`() {
        val line = StringBuilder("SEC Tycho Reach")
        HudLayout.ellipsize(line)
        assertEquals("SEC Tycho Reach", line.toString())
    }

    @Test
    fun `ellipsize leaves a line at exactly the cap untouched`() {
        val exact = "X".repeat(HudLayout.MAX_LINE_CHARS)
        val line = StringBuilder(exact)
        HudLayout.ellipsize(line)
        assertEquals(exact, line.toString())
    }

    @Test
    fun `ellipsize truncates an over-long line to the cap with an ASCII ellipsis`() {
        val original = "OBJ ${"Titanium".repeat(8)}" // well over the cap
        val line = StringBuilder(original)
        HudLayout.ellipsize(line)
        assertTrue("result is within the line-length cap", line.length <= HudLayout.MAX_LINE_CHARS)
        assertEquals("result is exactly the cap when truncated", HudLayout.MAX_LINE_CHARS, line.length)
        assertTrue("an over-long line ends with the ASCII ellipsis", line.toString().endsWith("..."))
        // The kept prefix is the original head (cap minus the 3 ellipsis chars).
        assertTrue(line.toString().startsWith(original.substring(0, HudLayout.MAX_LINE_CHARS - 3)))
    }

    private companion object {
        /** The rightwards arrow used by the courier route (U+2192; in [GameFont.REQUIRED_GLYPHS]). */
        const val ARROW = '→'

        fun miningMission(
            quota: Int,
            resource: ResourceType = ResourceType.TITANIUM,
            status: MissionStatus = MissionStatus.ACTIVE,
        ): Mission =
            Mission(
                id = MissionId("board:alpha-station:mining"),
                type = MissionType.MINING,
                source = MissionSource.BOARD,
                status = status,
                rewardCredits = 100L,
                quotaResource = resource,
                quotaUnits = quota,
            )

        fun courierMission(
            pickup: String,
            destination: String,
            pickedUp: Boolean,
            status: MissionStatus = MissionStatus.ACTIVE,
        ): Mission =
            Mission(
                id = MissionId("radio:beta-station"),
                type = MissionType.COURIER,
                source = MissionSource.RADIO,
                status = status,
                rewardCredits = 100L,
                pickup = PoiId(pickup),
                destination = PoiId(destination),
                pickedUp = pickedUp,
            )

        fun cargoOf(
            vararg held: Pair<ResourceType, Int>,
            capacity: Int = 50,
        ): Cargo = Cargo(contents = held.toMap(), capacity = capacity)

        /** Assemble a model with sensible defaults, overriding only the fields a test cares about. */
        fun model(
            speed: Float = 0f,
            headingRadians: Float = 0f,
            fuelLevel: Float = 10f,
            fuelCapacity: Float = 20f,
            lowFuel: Boolean = false,
            inCombat: Boolean = false,
            credits: Long = 0L,
            cargo: Cargo = Cargo.empty(),
            sectorName: String = "Sector",
            missionLog: MissionLog = MissionLog.EMPTY,
        ): HudViewModel =
            HudViewModel.build(
                speed = speed,
                headingRadians = headingRadians,
                fuelLevel = fuelLevel,
                fuelCapacity = fuelCapacity,
                lowFuel = lowFuel,
                inCombat = inCombat,
                credits = credits,
                cargo = cargo,
                sectorName = sectorName,
                missionLog = missionLog,
            )
    }
}
