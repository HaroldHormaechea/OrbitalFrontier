package com.orbitalfrontier.outfit

/**
 * A station's outfitting desk: the set of [UpgradeId]s it **offers for install** (UC09 AC#3).
 *
 * The economy analogue of [com.orbitalfrontier.economy.StationMarket] — pure, immutable authored data
 * that rides with the world ([com.orbitalfrontier.world.Station.outfitMarket]) rather than the save
 * (ADR 0007 / ADR 0008). It lists only **which** parts a dealer stocks; the **price** of each lives on
 * the [Upgrade] in the [UpgradeCatalog] (so a retune is one place). [Outfitting.resolve] gates a
 * BuyInstall on membership here, so a station only fits the parts it advertises.
 *
 * A part **absent** from [offered] is simply not installable at this station ([offers] returns false).
 * The default [EMPTY] (offers nothing) is the right read for a station with no outfitting desk.
 */
data class OutfitMarket(
    val offered: Set<UpgradeId>,
) {
    /** Whether this station offers [id] for install. */
    fun offers(id: UpgradeId): Boolean = id in offered

    companion object {
        /** A station with no outfitting desk (offers nothing); the default for an authored station. */
        val EMPTY: OutfitMarket = OutfitMarket(emptySet())

        /** Convenience: an outfit market offering [ids] (a value class can't be a vararg, so a list). */
        fun of(ids: List<UpgradeId>): OutfitMarket = OutfitMarket(ids.toSet())
    }
}
