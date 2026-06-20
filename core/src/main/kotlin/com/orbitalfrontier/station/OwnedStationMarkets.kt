package com.orbitalfrontier.station

import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.economy.TradeOffer
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.UpgradeCatalog

/**
 * The authored desks a player-owned station's modules expose (UC51 AC#3/#5).
 *
 * An owned station's [StationFunction]s map to real, **non-empty** desks so using a built module is a
 * genuine end-to-end action (AC#5): a [StationFunction.COMMERCE] module surfaces [COMMERCE_DESK] (a
 * trade desk the player can buy/sell at) and a [StationFunction.RETROFIT] module surfaces
 * [RETROFIT_DESK] (an outfitting desk that fits the MVP upgrades). Like every station market these are
 * authored constant data **reconstructed on load, never row-persisted** (ADR 0007 / ADR 0008): the
 * owned station persists only its module ids; the desks are re-derived here. All prices / part lists
 * are **[TUNE]** placeholders.
 *
 * Pure (no engine types) so the owned-station model stays JVM-testable (ADR 0001).
 */
object OwnedStationMarkets {
    /**
     * The trade desk a COMMERCE module exposes (AC#3/#5). A modest spread on the common mined goods so
     * the player can sell what they mine at their own outpost; `sellPrice <= buyPrice` per the
     * [TradeOffer] invariant (no money-printing loop). [TUNE]
     */
    val COMMERCE_DESK: StationMarket =
        StationMarket(
            mapOf(
                ResourceType.HYDROGEN to TradeOffer(buyPrice = 7, sellPrice = 5),
                ResourceType.WATER_ICE to TradeOffer(buyPrice = 6, sellPrice = 4),
                ResourceType.IRON_ORE to TradeOffer(buyPrice = 12, sellPrice = 9),
                ResourceType.COPPER to TradeOffer(buyPrice = 15, sellPrice = 11),
                ResourceType.SILICON to TradeOffer(buyPrice = 18, sellPrice = 13),
                ResourceType.ALUMINUM to TradeOffer(buyPrice = 13, sellPrice = 9),
                ResourceType.TITANIUM to TradeOffer(buyPrice = 55, sellPrice = 44),
            ),
        )

    /**
     * The outfitting desk a RETROFIT module exposes (AC#3): the MVP tier-I upgrades, so a retrofit bay
     * is a working refit desk the player can install parts at. The price of each part lives on its
     * [com.orbitalfrontier.outfit.Upgrade] in the catalog, as for every outfit market. [TUNE]
     */
    val RETROFIT_DESK: OutfitMarket =
        OutfitMarket.of(
            listOf(
                UpgradeCatalog.ENGINE_TUNE_I,
                UpgradeCatalog.CARGO_POD_I,
                UpgradeCatalog.FUEL_TANK_I,
                UpgradeCatalog.SCANNER_I,
            ),
        )
}
