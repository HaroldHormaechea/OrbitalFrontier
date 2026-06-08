package com.orbitalfrontier.crew

/**
 * Pure derived crew-gating for turrets (UC11 AC#3). A turret needs a minimum number of crew assigned
 * to operate; below that it is **inoperable**. This is a side-effect-free derivation — a function of
 * `(crew, requiredCrew)` with no I/O and no engine types — so it is fully JVM-testable (UC11 AC#5)
 * and a recorded playthrough can assert the operability flag (UC11 AC#6).
 *
 * **Turrets and combat do not exist yet.** UC11 models operability as a *pure derived flag only*; it
 * builds no combat. The future combat model (UC13) consumes this flag. The `(crew, requiredCrew)`
 * signature is the deliberate stable seam UC13 swaps real per-turret crew-requirement logic into —
 * UC13 will compute `requiredCrew` per turret rather than using the single authored MVP constant.
 */
object TurretOperability {
    /**
     * Minimum crew for a turret to be operable in the MVP (UC11 AC#3). Authored at 1 — at or below the
     * starter ship's crew capacity (2), so the **first** hire flips a turret from inoperable to
     * operable. Superseded per-turret by UC13. [TUNE]
     */
    const val MVP_TURRET_CREW_REQUIREMENT: Int = 1

    /**
     * True when [crew] is sufficient to operate a turret requiring [requiredCrew] crew. A non-positive
     * [requiredCrew] means the turret needs no crew (always operable); otherwise it is operable iff
     * `crew >= requiredCrew`.
     */
    fun turretsOperable(
        crew: Int,
        requiredCrew: Int = MVP_TURRET_CREW_REQUIREMENT,
    ): Boolean = requiredCrew <= 0 || crew >= requiredCrew
}
