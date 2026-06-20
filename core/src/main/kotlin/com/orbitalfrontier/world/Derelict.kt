package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * A **derelict / wreck** POI (UC54 AC#1) — a scavengeable hulk the player flies up to and salvages for
 * resources (docs/adr/0042-additional-poi-types.md; docs/design/world-and-sector.md).
 *
 * Like [HiddenContact] a derelict is a [Poi] and a [Contact] (so it shows on the minimap once known) but
 * deliberately **not** a [Transponder]: a wreck does not broadcast, so it is **scan-only** — it stays
 * invisible until an active scan (UC10) within sensor range uncovers it, exactly like a hidden contact.
 * [com.orbitalfrontier.world.Scanning] reveals any non-[Transponder] [Contact] in range, so derelicts and
 * hidden contacts share that one detection path rather than forking it.
 *
 * Flying within [salvageRadius] of [position] and issuing a [com.orbitalfrontier.world.ScavengeAction.SCAVENGE]
 * rolls the derelict's loot (the shared [com.orbitalfrontier.combat.LootTable.DERELICT] profile, keyed
 * `"derelict:$id"`) into the hold via the shared [com.orbitalfrontier.combat.Salvage.fillCargo] helper and
 * marks the derelict **consumed** ([WorldState.consumedPois]) — a scavenged wreck stays empty across
 * save/reload (UC54 AC#4). The roll uses a fresh [com.orbitalfrontier.common.DeterministicRng] namespace so
 * it adds zero draws to any existing combat/salvage stream (the zero-fixture-regen lever).
 *
 * Pure data — no engine types — so derelicts are part of the JVM-testable world model (ADR 0001) and the
 * salvage logic that reads them ([com.orbitalfrontier.world.DerelictSalvage]) stays unit-testable on the JVM.
 */
data class Derelict(
    override val id: PoiId,
    override val position: Vec2,
    /** Radius (world-units) of the circle around [position] within which the ship can scavenge. */
    val salvageRadius: Float = DEFAULT_SALVAGE_RADIUS,
) : Poi, Contact {
    override val contactKind: ContactKind get() = ContactKind.DERELICT

    init {
        require(salvageRadius > 0f) { "Derelict $id salvageRadius must be positive: $salvageRadius" }
    }

    companion object {
        /** Default scavenge-range radius (world-units); comparable to a station's docking radius. [TUNE] */
        const val DEFAULT_SALVAGE_RADIUS: Float = 100f
    }
}
