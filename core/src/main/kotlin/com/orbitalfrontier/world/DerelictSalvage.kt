package com.orbitalfrontier.world

import com.orbitalfrontier.combat.CargoFill
import com.orbitalfrontier.combat.LootTable
import com.orbitalfrontier.combat.Salvage
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo

/**
 * The player's intent for the scavenge control on a given frame (UC54) — the scavenge analogue of
 * [ScanAction] / [MineAction] / [DockAction].
 *
 * [NONE] is the common per-frame case (no scavenge requested); [SCAVENGE] is the discrete edge-triggered
 * action the play screen feeds in when the context SCAVENGE button is tapped near a derelict.
 */
enum class ScavengeAction {
    NONE,
    SCAVENGE,
}

/**
 * The result of [DerelictSalvage.resolve] (UC54 AC#2/#4): the [cargo] after the salvage fill, the updated
 * [consumedPois] set, how many units were [acceptedUnits], whether a hold-full [overflow] left some behind,
 * and the [scavenged] derelict id (null on a no-op). A no-op returns the SAME cargo + consumed instances.
 */
data class DerelictSalvageResult(
    val cargo: Cargo,
    val consumedPois: Set<PoiId>,
    val acceptedUnits: Int,
    val overflow: Boolean,
    val scavenged: PoiId?,
)

/**
 * **The** shared, pure derelict-scavenging resolver (UC54 AC#2) — the single source of truth the device loop
 * ([com.orbitalfrontier.screen.PlayScreen]) and the headless replay mirror
 * ([com.orbitalfrontier.sim.Simulation]) both call, so live and replayed scavenging are byte-identical
 * (project rule #1, the lockstep contract). Engine-free and seed-deterministic, so it is JVM-testable and
 * replay-stable (UC54 AC#4).
 *
 * On a [ScavengeAction.SCAVENGE], the nearest **un-consumed** [Derelict] within its
 * [Derelict.salvageRadius] of the ship is salvaged: its loot is rolled from the shared
 * [LootTable.DERELICT] profile keyed `"derelict:$id"` (a fresh [com.orbitalfrontier.common.DeterministicRng]
 * namespace — zero draws added to any combat/salvage stream, the zero-fixture-regen lever), poured into the
 * hold via the **shared** [Salvage.fillCargo] helper (the same multi-resource, capacity-respecting,
 * declaration-order fill salvage pickup uses — no duplication, challenger Condition 2), and the derelict is
 * marked **consumed** so it stays empty across save/reload (AC#4). A [ScavengeAction.NONE], or no un-consumed
 * derelict in range, is a strict **no-op** returning the SAME cargo + consumed instances (byte-identical).
 *
 * A derelict reached by an explicit SCAVENGE is always consumed (deterministic "picked clean"); resources
 * beyond the hold's remaining capacity are flagged via [DerelictSalvageResult.overflow] (drives the reused
 * "CARGO FULL" cue), exactly like salvage pickup.
 */
object DerelictSalvage {
    /** Resolve a scavenge against the post-movement [shipPosition] in [currentSector]. */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        cargo: Cargo,
        consumedPois: Set<PoiId>,
        action: ScavengeAction,
    ): DerelictSalvageResult {
        if (action != ScavengeAction.SCAVENGE) {
            return DerelictSalvageResult(cargo, consumedPois, acceptedUnits = 0, overflow = false, scavenged = null)
        }
        val target =
            world.sector(currentSector).derelicts
                .asSequence()
                .filter { it.id !in consumedPois }
                .filter { (shipPosition - it.position).length <= it.salvageRadius }
                .minByOrNull { (shipPosition - it.position).length }
                ?: return DerelictSalvageResult(cargo, consumedPois, acceptedUnits = 0, overflow = false, scavenged = null)

        val loot = LootTable.roll(LootTable.DERELICT, "derelict:${target.id.value}")
        val fill: CargoFill = Salvage.fillCargo(cargo, loot.resources)
        return DerelictSalvageResult(
            cargo = fill.cargo,
            consumedPois = consumedPois + target.id,
            acceptedUnits = fill.acceptedUnits,
            overflow = fill.leftover.isNotEmpty(),
            scavenged = target.id,
        )
    }
}
