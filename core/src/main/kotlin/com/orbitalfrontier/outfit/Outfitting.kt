package com.orbitalfrontier.outfit

/**
 * The player's outfitting intent for one action (UC09 AC#2/#3/#4) — the outfitting analogue of
 * [com.orbitalfrontier.economy.TradeOrder], a `sealed` hierarchy (coding-guidelines § O) so a future
 * order kind plugs in a new subtype rather than editing a central `when`.
 *
 * [None] is idle. [BuyInstall] buys an upgrade from the docked station's outfit market and installs it
 * into a free slot. [RemoveSell] removes the part at a slot **and sells it for scrap** — only at a
 * junkyard (AC#4). [Outfitting.resolve] gates and clamps every case, so a caller can request freely.
 */
sealed interface OutfitOrder {
    /** No outfitting this action — [Outfitting.resolve] returns its inputs unchanged. */
    data object None : OutfitOrder

    /** Buy [upgradeId] from the docked station and install it into the lowest free slot of its category. */
    data class BuyInstall(val upgradeId: UpgradeId) : OutfitOrder

    /** Remove (and sell, at a junkyard) whatever part occupies ([category], [slotIndex]). */
    data class RemoveSell(val category: SlotCategory, val slotIndex: Int) : OutfitOrder
}

/**
 * The outcome of a single [Outfitting.resolve] call — the new credit balance, the new loadout, and
 * whether anything changed.
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes): "can't afford / no free slot / not offered / nothing to remove /
 * not a junkyard" are all normal no-ops reported via [changed] = false (with [credits]/[loadout]
 * unchanged), not errors. The caller re-derives the ship's capacities from the new [loadout] (the
 * Δ-capacity propagation, UC09 AC#2 — see [com.orbitalfrontier.ship.OwnedShip.withLoadout]).
 */
data class OutfitResult(
    val credits: Long,
    val loadout: Loadout,
    val changed: Boolean,
)

/**
 * Pure, deterministic outfitting (UC09 AC#2/#3/#4/#7) — the economy analogue of
 * [com.orbitalfrontier.economy.Trading]. A side-effect-free function of (credits, loadout, ship slot
 * layout, station outfit desk, junkyard-or-not, order): identical inputs always yield an identical
 * result, with no I/O and no engine types, so it slots into the deterministic simulation/replay path
 * and is fully JVM-unit-testable (UC09 AC#7). It does **not** mutate anything — the caller applies the
 * [OutfitResult] and re-derives capacities via [com.orbitalfrontier.ship.OwnedShip.withLoadout].
 *
 * It takes the **decoupled pieces** it needs ([outfitMarket], [isJunkyard], [slotCounts]) rather than a
 * world `Station`, mirroring how [com.orbitalfrontier.economy.Trading] takes a `StationMarket` — so the
 * `outfit` package stays free of world/engine coupling. The device and the headless sim both build
 * those pieces from the docked station + active ship type and call this same resolver, so live and
 * replayed outfitting are identical.
 *
 * All money math is [Long] so a large balance can never overflow.
 */
object Outfitting {
    /**
     * Fraction of an upgrade's catalog price refunded when a used part is sold at a junkyard (UC09
     * AC#4) — a used part is worth less than new. An authored balancing tunable. [TUNE]
     */
    const val USED_PART_REFUND_FRACTION: Float = 0.5f

    /**
     * Resolve a single outfit [order] against the player's [credits] and active ship's [loadout],
     * given the active ship type's [slotCounts], the docked station's [outfitMarket], and whether that
     * station [isJunkyard].
     *
     * Returns the inputs **unchanged** (`changed = false`) on any no-op (see [OutfitResult]).
     *
     * - **BuyInstall:** gated on the upgrade being catalogued, **offered** by [outfitMarket] (AC#3),
     *   affordable (`credits >= price`), and there being a free slot of its category within
     *   [slotCounts]. On success the part is installed into the lowest free slot and its price deducted.
     * - **RemoveSell:** gated on [isJunkyard] (AC#4 — a no-op at a dealer) and a part actually occupying
     *   the slot. On success the slot is freed and [USED_PART_REFUND_FRACTION] of the part's price is
     *   refunded.
     */
    fun resolve(
        credits: Long,
        loadout: Loadout,
        slotCounts: Map<SlotCategory, Int>,
        outfitMarket: OutfitMarket,
        isJunkyard: Boolean,
        order: OutfitOrder,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
    ): OutfitResult {
        val unchanged = OutfitResult(credits, loadout, false)
        return when (order) {
            OutfitOrder.None -> unchanged
            is OutfitOrder.BuyInstall -> resolveBuyInstall(credits, loadout, slotCounts, outfitMarket, order, catalog, unchanged)
            is OutfitOrder.RemoveSell -> resolveRemoveSell(credits, loadout, isJunkyard, order, catalog, unchanged)
        }
    }

    private fun resolveBuyInstall(
        credits: Long,
        loadout: Loadout,
        slotCounts: Map<SlotCategory, Int>,
        outfitMarket: OutfitMarket,
        order: OutfitOrder.BuyInstall,
        catalog: UpgradeCatalog,
        unchanged: OutfitResult,
    ): OutfitResult {
        val upgrade = catalog.upgrade(order.upgradeId) ?: return unchanged // not catalogued
        if (!outfitMarket.offers(order.upgradeId)) return unchanged // not stocked here (AC#3)
        if (credits < upgrade.price) return unchanged // can't afford

        val slotCount = slotCounts[upgrade.category] ?: 0
        return when (val install = loadout.install(upgrade.category, slotCount, order.upgradeId)) {
            is InstallResult.Installed -> OutfitResult(credits - upgrade.price, install.loadout, true)
            InstallResult.NoFreeSlot -> unchanged // category full
        }
    }

    private fun resolveRemoveSell(
        credits: Long,
        loadout: Loadout,
        isJunkyard: Boolean,
        order: OutfitOrder.RemoveSell,
        catalog: UpgradeCatalog,
        unchanged: OutfitResult,
    ): OutfitResult {
        if (!isJunkyard) return unchanged // selling used parts is junkyard-only (AC#4)
        return when (val removal = loadout.remove(order.category, order.slotIndex)) {
            is RemoveResult.Removed -> {
                val price = catalog.upgrade(removal.removed)?.price ?: 0L
                val refund = (price * USED_PART_REFUND_FRACTION).toLong()
                OutfitResult(credits + refund, removal.loadout, true)
            }
            RemoveResult.EmptySlot -> unchanged // nothing in that slot
        }
    }
}
