package com.orbitalfrontier.combat

/**
 * The pure, libGDX-free **consequence summary** of a ship destruction (UC33): exactly what the
 * game-over / destruction screen reports to the player — how much cargo was jettisoned, any
 * credit/insurance penalty, and where they respawn. It carries no Scene2D or GL types, so a JVM unit
 * test can drive a destruction through [Respawn] and assert this value directly (UC33 AC#5), and the
 * deterministic record/replay harness stays untouched (it builds the same value from the same
 * [Respawn.RespawnResult]).
 *
 * In the MVP the only real cost of destruction is the cargo slice (no permadeath, no credit loss —
 * "insurance covered", see ADR 0022), so [creditPenalty] is a constant `0` produced by [from]. It is
 * modelled explicitly rather than omitted so the screen can state the insurance outcome and a future
 * balancing pass ([CombatParams] `[TUNE]`) can introduce a real penalty without changing this shape.
 */
data class DestructionSummary(
    /** Cargo units jettisoned on destruction — `Respawn.RespawnResult.unitsLost` (UC33 AC#2). */
    val cargoUnitsLost: Int,
    /** Credit/insurance penalty charged on destruction. Constant `0` in the MVP (insurance covered). */
    val creditPenalty: Long,
    /** Human-readable name of the station/point the player respawns at (UC33 AC#2). */
    val respawnLocationName: String,
) {
    companion object {
        /** The MVP destruction credit penalty: `0` — insurance covers it, no permadeath (ADR 0022). */
        const val INSURANCE_COVERED_PENALTY: Long = 0L

        /**
         * Build the consequence summary from a resolved [Respawn.RespawnResult] and the already-resolved
         * [respawnLocationName] (UC33). Pure: the cargo loss comes straight from the respawn result and
         * the credit penalty is the constant insurance-covered `0`, so live play and the replay harness
         * derive an identical summary from identical inputs.
         */
        fun from(
            result: Respawn.RespawnResult,
            respawnLocationName: String,
        ): DestructionSummary =
            DestructionSummary(
                cargoUnitsLost = result.unitsLost,
                creditPenalty = INSURANCE_COVERED_PENALTY,
                respawnLocationName = respawnLocationName,
            )
    }
}
