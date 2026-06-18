package com.orbitalfrontier.render

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionLog
import com.orbitalfrontier.mission.MissionType
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * The pure, engine-free snapshot the [HudRenderer] draws for one frame (UC34).
 *
 * Every flight readout the player needs to decide without opening a menu — speed, heading, fuel,
 * credits, cargo fill, current sector and the active-mission objective — flattened into a plain value
 * with no libGDX types, so the readout maths (heading normalisation, the objective line, the
 * display==turn-in cargo read) is fully JVM-unit-testable (ADR 0001) instead of buried in GL-bound draw
 * code. [build] assembles it each tick from the live simulation state, so the HUD updates live
 * (UC34 AC#4); [HudRenderer] only formats and draws the already-computed fields.
 */
data class HudViewModel(
    val speed: Float,
    val headingDegrees: Int,
    val fuelLevel: Float,
    val fuelCapacity: Float,
    val lowFuel: Boolean,
    val inCombat: Boolean,
    val credits: Long,
    val cargoUsed: Int,
    val cargoCapacity: Int,
    val sectorName: String,
    /** The active mission's objective content (no label/ellipsis), or null when no mission is active. */
    val objective: String?,
) {
    companion object {
        /** The rightwards arrow used in the courier route (in [GameFont.REQUIRED_GLYPHS], so it renders). */
        private const val ARROW = '→' // →

        /**
         * Assemble the per-frame view-model from the live simulation state. [headingRadians] is converted
         * to a normalised whole-degree bearing here (pure), and the objective line is derived from the
         * first active mission and the live cargo via [objectiveLine].
         */
        fun build(
            speed: Float,
            headingRadians: Float,
            fuelLevel: Float,
            fuelCapacity: Float,
            lowFuel: Boolean,
            inCombat: Boolean,
            credits: Long,
            cargo: Cargo,
            sectorName: String,
            missionLog: MissionLog,
        ): HudViewModel =
            HudViewModel(
                speed = speed,
                headingDegrees = normalizeDegrees(headingRadians),
                fuelLevel = fuelLevel,
                fuelCapacity = fuelCapacity,
                lowFuel = lowFuel,
                inCombat = inCombat,
                credits = credits,
                cargoUsed = cargo.usedUnits,
                cargoCapacity = cargo.capacity,
                sectorName = sectorName,
                objective = objectiveLine(missionLog.activeMissions.firstOrNull(), cargo),
            )

        /**
         * The active-mission objective line (UC34 AC#2), or null when there is no active mission (the
         * line is then hidden). Pure — a function of the mission and the current cargo only:
         *  - **MINING** → `"<resource> <held>/<quota>"`, where `held` is the quota resource currently in
         *    the hold clamped to the quota. This is the SAME read [com.orbitalfrontier.mission.Missions]
         *    uses to gate a mining turn-in, so the displayed progress and the turn-in condition never
         *    disagree (display math == turn-in math).
         *  - **COURIER** → the parcel route using the bundled [ARROW] glyph: `"<pickup>→<dest>"` before
         *    pickup, narrowing to `"→<dest>"` once the (virtual) parcel is aboard.
         */
        fun objectiveLine(
            mission: Mission?,
            cargo: Cargo,
        ): String? {
            if (mission == null) return null
            return when (mission.type) {
                MissionType.MINING -> {
                    val resource = mission.quotaResource ?: return null
                    val held = (cargo.contents[resource] ?: 0).coerceAtMost(mission.quotaUnits)
                    "${resource.displayName} $held/${mission.quotaUnits}"
                }
                MissionType.COURIER -> {
                    val destination = mission.destination?.value ?: "?"
                    if (mission.pickedUp) {
                        "$ARROW$destination"
                    } else {
                        val pickup = mission.pickup?.value ?: "?"
                        "$pickup$ARROW$destination"
                    }
                }
            }
        }

        /** Radians → a whole-degree bearing normalised to [0, 360). */
        private fun normalizeDegrees(radians: Float): Int {
            var degrees = (radians * 180f / PI.toFloat()).roundToInt() % 360
            if (degrees < 0) degrees += 360
            return degrees
        }
    }
}
