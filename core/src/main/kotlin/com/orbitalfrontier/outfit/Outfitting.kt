package com.orbitalfrontier.outfit

import com.orbitalfrontier.economy.FactionPricing
import com.orbitalfrontier.economy.PricingParams
import com.orbitalfrontier.faction.FactionId
import com.orbitalfrontier.faction.Reputation
import com.orbitalfrontier.faction.StandingGate
import com.orbitalfrontier.world.PoiId

/**
 * The player's outfitting intent for one action (UC09 AC#2/#3/#4; UC47) — the outfitting analogue of
 * [com.orbitalfrontier.economy.TradeOrder], a `sealed` hierarchy (coding-guidelines § O) so a future
 * order kind plugs in a new subtype rather than editing a central `when`.
 *
 * [None] is idle. [BuyInstall] buys a **new** upgrade from the docked station's outfit market and
 * installs it into a free slot. [BuyUsed] buys a **discounted used** copy from a junkyard's used-part
 * market and installs it the same way (UC47). [RemoveSell] removes the part at a slot **and sells it for
 * scrap** — only at a junkyard (AC#4). [Outfitting.resolve] gates and clamps every case, so a caller can
 * request freely.
 */
sealed interface OutfitOrder {
    /** No outfitting this action — [Outfitting.resolve] returns its inputs unchanged. */
    data object None : OutfitOrder

    /** Buy [upgradeId] from the docked station and install it into the lowest free slot of its category. */
    data class BuyInstall(val upgradeId: UpgradeId) : OutfitOrder

    /**
     * Buy a **used** copy of [upgradeId] from the docked junkyard's used-part market at the discounted
     * price and install it into the lowest free slot of its category (UC47 AC#1/#2). Beyond the
     * BuyInstall gates it additionally requires the junkyard to still have **available** baseline stock
     * of the part (baseline minus the player's persisted purchases) — see [Outfitting.resolve].
     */
    data class BuyUsed(val upgradeId: UpgradeId) : OutfitOrder

    /** Remove (and sell, at a junkyard) whatever part occupies ([category], [slotIndex]). */
    data class RemoveSell(val category: SlotCategory, val slotIndex: Int) : OutfitOrder
}

/**
 * The outcome of a single [Outfitting.resolve] call — the new credit balance, the new loadout, whether
 * anything changed, and the (possibly mutated) junkyard used-part [junkyardStock].
 *
 * A small explicit result type (coding-guidelines § error-handling: prefer explicit returns over
 * exceptions for expected outcomes): "can't afford / no free slot / not offered / nothing to remove /
 * not a junkyard / out of used stock" are all normal no-ops reported via [changed] = false (with
 * [credits]/[loadout]/[junkyardStock] unchanged), not errors. The caller re-derives the ship's
 * capacities from the new [loadout] (the Δ-capacity propagation, UC09 AC#2 — see
 * [com.orbitalfrontier.ship.OwnedShip.withLoadout]).
 *
 * **[junkyardStock] has NO convenience default — by design (the anti-exploit invariant, UC47 AC#3).**
 * Every construction site must pass it explicitly, so the compiler guarantees the depletion is threaded
 * through on every path. [resolve] builds the `unchanged` result, and every non-BuyUsed success, from
 * the **passed-in** stock (carried through untouched); ONLY a successful [OutfitOrder.BuyUsed] mutates
 * it (via [JunkyardStock.withPurchase]). This is what prevents a None / BuyInstall / RemoveSell tick from
 * silently wiping the depletion — which would let a reload restock cheap parts.
 */
data class OutfitResult(
    val credits: Long,
    val loadout: Loadout,
    val changed: Boolean,
    val junkyardStock: JunkyardStock,
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
     * given the active ship type's [slotCounts], the docked station's new-part [outfitMarket] and
     * used-part [usedPartMarket], whether that station [isJunkyard], the durable junkyard used-part
     * depletion [junkyardStock], the docked [stationId] (for the per-junkyard stock key), and the
     * used-part tunables [usedPartParams].
     *
     * Returns the inputs **unchanged** (`changed = false`, [junkyardStock] carried through untouched) on
     * any no-op (see [OutfitResult]).
     *
     * - **BuyInstall:** gated on the upgrade being catalogued, **offered** by [outfitMarket] (AC#3),
     *   affordable (`credits >= price`), and there being a free slot of its category within
     *   [slotCounts]. On success the part is installed into the lowest free slot and its price deducted.
     * - **BuyUsed (UC47):** gated on [isJunkyard], a non-null [stationId], the part being catalogued,
     *   **offered** by [usedPartMarket], having **available** baseline stock
     *   (`baselineStock − purchased > 0`), affordable at the **used** ([UsedPartPricing.usedPrice])
     *   price, and a free slot. On success the part installs via the SAME [Loadout.install] path as
     *   BuyInstall (only price + stock differ — AC#2), the used price is deducted, and the depletion
     *   grows by 1 ([JunkyardStock.withPurchase]). This is the **only** path that mutates [junkyardStock].
     * - **RemoveSell:** gated on [isJunkyard] (AC#4 — a no-op at a dealer) and a part actually occupying
     *   the slot. On success the slot is freed and [USED_PART_REFUND_FRACTION] of the part's price is
     *   refunded.
     *
     * **Anti-exploit invariant (AC#3):** every non-BuyUsed result is built from the **passed-in**
     * [junkyardStock], so a None / BuyInstall / RemoveSell tick can never silently reset the depletion;
     * the non-defaulted [OutfitResult.junkyardStock] makes the compiler enforce that every site threads it.
     */
    fun resolve(
        credits: Long,
        loadout: Loadout,
        slotCounts: Map<SlotCategory, Int>,
        outfitMarket: OutfitMarket,
        isJunkyard: Boolean,
        order: OutfitOrder,
        catalog: UpgradeCatalog = UpgradeCatalog.MVP,
        usedPartMarket: OutfitMarket = OutfitMarket.EMPTY,
        junkyardStock: JunkyardStock = JunkyardStock.EMPTY,
        stationId: PoiId? = null,
        usedPartParams: UsedPartParams = UsedPartParams(),
        // UC48: the docked station's faction + the player's standing + the pricing tunables. Neutral
        // defaults (no faction, EMPTY standing, default params) ⇒ no gate and an exactly-1.0 price
        // multiplier, so every pre-UC48 caller, test, and fixture stays byte-identical.
        factionId: FactionId? = null,
        reputation: Reputation = Reputation.EMPTY,
        pricingParams: PricingParams = PricingParams(),
    ): OutfitResult {
        // The unchanged result carries the INPUT junkyardStock through untouched — the anti-exploit anchor.
        val unchanged = OutfitResult(credits, loadout, false, junkyardStock)
        return when (order) {
            OutfitOrder.None -> unchanged
            is OutfitOrder.BuyInstall ->
                resolveBuyInstall(
                    credits, loadout, slotCounts, outfitMarket, order, catalog,
                    factionId, reputation, pricingParams, unchanged,
                )
            is OutfitOrder.BuyUsed ->
                resolveBuyUsed(
                    credits, loadout, slotCounts, usedPartMarket, isJunkyard, order, catalog,
                    junkyardStock, stationId, usedPartParams, factionId, reputation, pricingParams, unchanged,
                )
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
        factionId: FactionId?,
        reputation: Reputation,
        pricingParams: PricingParams,
        unchanged: OutfitResult,
    ): OutfitResult {
        val upgrade = catalog.upgrade(order.upgradeId) ?: return unchanged // not catalogued
        if (!outfitMarket.offers(order.upgradeId)) return unchanged // not stocked here (AC#3)
        // UC48 AC#1: reputation gate — below the part's standing requirement at this station's faction is a no-op.
        if (!StandingGate.status(upgrade.unlockThreshold, factionId, reputation).available) return unchanged // locked
        // UC48 AC#2: charge the faction-adjusted effective price (the SAME helper the screen displays).
        val price = FactionPricing.adjustedPrice(upgrade.price, factionId, reputation, pricingParams)
        if (credits < price) return unchanged // can't afford

        val slotCount = slotCounts[upgrade.category] ?: 0
        return when (val install = loadout.install(upgrade.category, slotCount, order.upgradeId)) {
            // Success carries the INPUT depletion through unchanged (this path never buys used).
            is InstallResult.Installed -> OutfitResult(credits - price, install.loadout, true, unchanged.junkyardStock)
            InstallResult.NoFreeSlot -> unchanged // category full
        }
    }

    private fun resolveBuyUsed(
        credits: Long,
        loadout: Loadout,
        slotCounts: Map<SlotCategory, Int>,
        usedPartMarket: OutfitMarket,
        isJunkyard: Boolean,
        order: OutfitOrder.BuyUsed,
        catalog: UpgradeCatalog,
        junkyardStock: JunkyardStock,
        stationId: PoiId?,
        usedPartParams: UsedPartParams,
        factionId: FactionId?,
        reputation: Reputation,
        pricingParams: PricingParams,
        unchanged: OutfitResult,
    ): OutfitResult {
        if (!isJunkyard) return unchanged // buying used parts is junkyard-only (AC#1)
        val station = stationId ?: return unchanged // need a station key to track depletion
        val upgrade = catalog.upgrade(order.upgradeId) ?: return unchanged // not catalogued
        if (!usedPartMarket.offers(order.upgradeId)) return unchanged // this junkyard doesn't stock it used (AC#1)
        // UC48 AC#1: reputation gate applies to used buys too — below the standing requirement is a no-op.
        if (!StandingGate.status(upgrade.unlockThreshold, factionId, reputation).available) return unchanged // locked

        // AC#3: available = deterministic baseline − persisted purchases. Out of stock ⇒ no-op.
        val baseline = UsedPartPricing.baselineStock(station, order.upgradeId, usedPartParams)
        val purchased = junkyardStock.purchasedCount(station, order.upgradeId)
        if (baseline - purchased <= 0) return unchanged // depleted

        // UC48 AC#2: compose-on-base — faction-adjust the catalog price FIRST, then apply the used
        // discount on top (byte-identical at neutral, where adjustedPrice == catalog price).
        val factionBase = FactionPricing.adjustedPrice(upgrade.price, factionId, reputation, pricingParams)
        val price = UsedPartPricing.usedPrice(factionBase, usedPartParams)
        if (credits < price) return unchanged // can't afford the used price

        val slotCount = slotCounts[upgrade.category] ?: 0
        return when (val install = loadout.install(upgrade.category, slotCount, order.upgradeId)) {
            // The ONLY path that mutates the depletion: record this one purchase (AC#2 reuses install).
            is InstallResult.Installed ->
                OutfitResult(credits - price, install.loadout, true, junkyardStock.withPurchase(station, order.upgradeId, 1))
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
                // Success carries the INPUT depletion through unchanged (selling never restocks).
                OutfitResult(credits + refund, removal.loadout, true, unchanged.junkyardStock)
            }
            RemoveResult.EmptySlot -> unchanged // nothing in that slot
        }
    }
}
