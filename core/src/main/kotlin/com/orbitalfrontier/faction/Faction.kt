package com.orbitalfrontier.faction

/**
 * A hand-authored faction (UC14 AC#1) — pure authored data describing one of the powers that own
 * stations in the sector. Catalogued in [Factions] and referenced by [id] from a
 * [com.orbitalfrontier.world.Station] (`Station.factionId`) and from a gated mission's
 * `unlockFaction`. Pure value with no engine types, so the whole faction system is JVM-testable
 * (UC14 AC#5) and composes into the immutable world model.
 *
 * [color] is an optional authored display tint (packed RGBA8888, the libGDX convention used elsewhere)
 * for a future faction-coloured UI; it is **not** consumed by any gameplay logic and is `[TUNE]`.
 *
 * Like [com.orbitalfrontier.ship.ShipRoster] / [com.orbitalfrontier.combat.HostileArchetypes], factions
 * are **fixed authored constants reconstructed at runtime**, never row-persisted: a save stores only a
 * [FactionId] slug (on a station's owning faction — itself authored map data — and on the player's
 * per-faction reputation rows), resolving the full faction back here on load, so an authoring retune
 * lives in one place and an unknown saved slug degrades gracefully (skip + WARN).
 */
data class Faction(
    val id: FactionId,
    val displayName: String,
    /** Optional authored display tint (packed RGBA8888); no gameplay effect. [TUNE] */
    val color: Int? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Faction ${id.value} displayName must not be blank" }
    }
}

/**
 * The data-driven catalog of factions in the MVP-plus (UC14 AC#1), mirroring [ShipRoster] /
 * [HostileArchetypes]: authored constant data resolved at runtime, never persisted.
 *
 * The MVP map ([com.orbitalfrontier.world.MvpSectorMap]) wires Alpha + Beta stations to [LEAGUE] and
 * the Gamma junkyard to [INDEPENDENTS]; reputation gates one premium board offer per faction station.
 * [byId] returns null on a miss (an evolved/removed slug) so callers degrade gracefully rather than
 * crashing — the "never stranded" persistence convention.
 */
object Factions {
    /** The settled trade league that runs the core stations (Alpha + Beta). */
    val LEAGUE: Faction =
        Faction(
            id = FactionId("league"),
            displayName = "Trade League",
            color = 0x4A90D9FF.toInt(),
        )

    /** The unaligned fringe operators of the junkyard at Gamma. */
    val INDEPENDENTS: Faction =
        Faction(
            id = FactionId("independents"),
            displayName = "Independents",
            color = 0xC98A2BFF.toInt(),
        )

    /** Every faction in the catalog, in authored order. */
    val all: List<Faction> = listOf(LEAGUE, INDEPENDENTS)

    private val byId: Map<FactionId, Faction> = all.associateBy { it.id }

    /** The faction with [id], or null if it is not in the catalog (e.g. a saved slug since removed). */
    fun byId(id: FactionId): Faction? = byId[id]
}
