package com.orbitalfrontier.combat

/**
 * The authored tuning constants for the combat sim (UC13) — every weapon/damage/AI/respawn number the
 * model needs that is not already on a [HostileArchetype] or derived from ship stats. Pure value data
 * with sensible `[TUNE]` placeholder defaults, passed into [Combat.step] / [Respawn] / the spawner so
 * the device and the replay harness run identical numbers (UC13 AC#7). Defaulted so a caller can use
 * `CombatParams()`; the recorded fixtures pin a known instance.
 *
 * **All values are `[TUNE]` placeholders** held to the 8 ACs (4 sections, nearest-only targeting,
 * forgiving respawn) — balancing is a later pass.
 */
data class CombatParams(
    /** Hit radius (world-units) a projectile must reach to strike a ship — both player and hostiles. [TUNE] */
    val hitRadius: Float = 28f,
    /**
     * Relative weight of each [ShipSection] in the RNG-weighted hit-location pick (UC13 AC#3), keyed by
     * [ShipSection.name] (never ordinal). HULL is weighted heaviest so most fire chips the hull toward
     * destruction, with lighter chances of a component-disabling hit. [TUNE]
     */
    val sectionHitWeights: Map<ShipSection, Int> =
        mapOf(
            ShipSection.HULL to 5,
            ShipSection.ENGINE to 2,
            ShipSection.TURRET to 2,
            ShipSection.WEAPON to 2,
        ),
    /**
     * Engine-damage speed scaling (UC13 AC#3): max speed is multiplied by
     * `minEngineSpeedFactor + (1 - minEngineSpeedFactor) * engineHpFraction`, so a pristine engine is
     * ×1.0 (byte-identical) and a fully-wrecked engine still crawls at [minEngineSpeedFactor]. [TUNE]
     */
    val minEngineSpeedFactor: Float = 0.35f,
    /**
     * Fraction of cargo units lost on destruction-respawn (UC13 AC#5) — the forgiving penalty. 0.25 =
     * a quarter of the hold is jettisoned; no permadeath, no credit loss. [TUNE]
     */
    val respawnCargoLossFraction: Float = 0.25f,
    /** Muzzle offset (world-units) ahead of the firing ship so a shot doesn't self-collide on spawn. [TUNE] */
    val muzzleOffset: Float = 24f,
    /** Distance (world-units) from the player at which natural-encounter hostiles spawn in (UC13). [TUNE] */
    val spawnDistance: Float = 360f,
    /**
     * Radius (world-units) within which the player auto-collects a [SalvageDrop] by flying near it
     * (UC42 AC#2) — proximity pickup, no explicit action. [TUNE]
     */
    val salvagePickupRadius: Float = 64f,
) {
    init {
        require(hitRadius > 0f) { "hitRadius must be positive: $hitRadius" }
        require(spawnDistance > 0f) { "spawnDistance must be positive: $spawnDistance" }
        require(salvagePickupRadius > 0f) { "salvagePickupRadius must be positive: $salvagePickupRadius" }
        require(minEngineSpeedFactor in 0f..1f) { "minEngineSpeedFactor must be in 0..1: $minEngineSpeedFactor" }
        require(respawnCargoLossFraction in 0f..1f) {
            "respawnCargoLossFraction must be in 0..1: $respawnCargoLossFraction"
        }
        require(sectionHitWeights.values.all { it >= 0 } && sectionHitWeights.values.sum() > 0) {
            "sectionHitWeights must be non-negative and sum to a positive total"
        }
    }
}
