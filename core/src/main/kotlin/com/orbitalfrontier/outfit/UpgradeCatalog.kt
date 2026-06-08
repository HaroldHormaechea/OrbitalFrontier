package com.orbitalfrontier.outfit

/**
 * The data-driven master list of all installable [Upgrade]s in the MVP (UC09 AC#2/#3).
 *
 * Authored constant data, reconstructed at runtime (never row-persisted) — mirroring how station
 * markets ride with the world rather than the save (ADR 0007). A [Loadout] stores only an
 * [UpgradeId] per slot; [upgrade] resolves it back to the full [Upgrade] here, so a price/stat retune
 * touches this one file and a saved loadout referencing an upgrade that was later removed simply
 * resolves to null and is skipped on load (never stranded — see [com.orbitalfrontier.save
 * .SqlDelightGameStateRepository]).
 *
 * **Which station offers which upgrade is a separate concern** ([com.orbitalfrontier.world.Station
 * .outfitMarket]); this catalog is the universe of parts, not what any one dealer stocks. Prices and
 * stat deltas are provisional **[TUNE]** placeholders (the design note flags balancing as open).
 */
class UpgradeCatalog(upgrades: List<Upgrade>) {
    /** All catalogued upgrades, in authored order. */
    val all: List<Upgrade> = upgrades.toList()

    private val byId: Map<UpgradeId, Upgrade> =
        LinkedHashMap<UpgradeId, Upgrade>(upgrades.size).apply {
            for (upgrade in upgrades) {
                require(put(upgrade.id, upgrade) == null) { "duplicate UpgradeId: ${upgrade.id.value}" }
            }
        }

    /** The upgrade with [id], or null if it is not catalogued (e.g. a saved id from a removed part). */
    fun upgrade(id: UpgradeId): Upgrade? = byId[id]

    /** The catalogued upgrades whose [Upgrade.category] is [category], in authored order. */
    fun upgradesIn(category: SlotCategory): List<Upgrade> = all.filter { it.category == category }

    companion object {
        // ---- MVP upgrade ids (authored slugs; the persisted ship_upgrade.upgrade_id values). ----
        val ENGINE_TUNE_I: UpgradeId = UpgradeId("engine-tune-i")
        val ENGINE_TUNE_II: UpgradeId = UpgradeId("engine-tune-ii")
        val CARGO_POD_I: UpgradeId = UpgradeId("cargo-pod-i")
        val CARGO_POD_II: UpgradeId = UpgradeId("cargo-pod-ii")
        val FUEL_TANK_I: UpgradeId = UpgradeId("fuel-tank-i")
        val SCANNER_I: UpgradeId = UpgradeId("scanner-i")

        /**
         * The MVP catalog (UC09). A small, data-driven set spanning the categories the MVP wires up:
         * engines (+max speed/accel), cargo (+capacity), fuel tanks (+capacity), and a sensor scanner
         * (+scan range). Weapons/comms/hull/crew categories exist on ships but ship no upgrades yet —
         * combat and crew land in later UCs. All numbers are **[TUNE]** placeholders.
         */
        val MVP: UpgradeCatalog =
            UpgradeCatalog(
                listOf(
                    Upgrade(
                        id = ENGINE_TUNE_I,
                        category = SlotCategory.ENGINES,
                        displayName = "Engine Tune I",
                        price = 300,
                        statDeltas = StatDelta(maxSpeed = 20f, maxAcceleration = 12f),
                    ),
                    Upgrade(
                        id = ENGINE_TUNE_II,
                        category = SlotCategory.ENGINES,
                        displayName = "Engine Tune II",
                        price = 700,
                        statDeltas = StatDelta(maxSpeed = 45f, maxAcceleration = 28f),
                    ),
                    Upgrade(
                        id = CARGO_POD_I,
                        category = SlotCategory.CARGO,
                        displayName = "Cargo Pod I",
                        price = 250,
                        statDeltas = StatDelta(cargoCapacity = 25),
                    ),
                    Upgrade(
                        id = CARGO_POD_II,
                        category = SlotCategory.CARGO,
                        displayName = "Cargo Pod II",
                        price = 600,
                        statDeltas = StatDelta(cargoCapacity = 60),
                    ),
                    Upgrade(
                        id = FUEL_TANK_I,
                        category = SlotCategory.FUEL_TANK,
                        displayName = "Fuel Tank I",
                        price = 220,
                        statDeltas = StatDelta(fuelCapacity = 50f),
                    ),
                    Upgrade(
                        id = SCANNER_I,
                        category = SlotCategory.SENSORS,
                        displayName = "Scanner I",
                        price = 180,
                        statDeltas = StatDelta(scanRange = 150f),
                    ),
                ),
            )
    }
}
