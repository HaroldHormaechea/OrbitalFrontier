package com.orbitalfrontier.station

import com.orbitalfrontier.economy.ResourceType

/**
 * The data-driven master list of all buildable [StationModule]s in the MVP (UC15 AC#1/#2).
 *
 * Authored constant data, reconstructed at runtime (never row-persisted) — the
 * [com.orbitalfrontier.outfit.UpgradeCatalog] analogue, and mirroring how station markets / ship
 * rosters ride with the authored world rather than the save (ADR 0007 / ADR 0008). An [OwnedStation]
 * stores only a [StationModuleId] per slot; [module] resolves it back to the full [StationModule]
 * here, so a cost/function retune touches this one file and a saved station referencing a module that
 * was later removed resolves to null and is skipped on load (never stranded — see
 * [com.orbitalfrontier.save.SqlDelightGameStateRepository]).
 *
 * Costs and the module set are provisional **[TUNE]** placeholders — the design note
 * (`docs/design/station-building.md`) flags the module catalog and balancing as open.
 */
class StationModuleCatalog(modules: List<StationModule>) {
    /** All catalogued modules, in authored order. */
    val all: List<StationModule> = modules.toList()

    private val byId: Map<StationModuleId, StationModule> =
        LinkedHashMap<StationModuleId, StationModule>(modules.size).apply {
            for (module in modules) {
                require(put(module.id, module) == null) { "duplicate StationModuleId: ${module.id.value}" }
            }
        }

    /** The module with [id], or null if it is not catalogued (e.g. a saved id from a removed module). */
    fun module(id: StationModuleId): StationModule? = byId[id]

    companion object {
        // ---- MVP module ids (authored slugs; the persisted station_module.module_type values). ----
        val COMMERCE_HUB: StationModuleId = StationModuleId("commerce-hub-i")
        val RETROFIT_BAY: StationModuleId = StationModuleId("retrofit-bay-i")

        /**
         * The MVP catalog (UC15). A small, data-driven set covering the two MVP station functions: a
         * commerce hub (a player-owned trade hub) and a retrofit bay (a player-owned outfitting/refit
         * bay). Each is built for a mix of credits and mined resources. All numbers are **[TUNE]**
         * placeholders for balancing.
         */
        val MVP: StationModuleCatalog =
            StationModuleCatalog(
                listOf(
                    StationModule(
                        id = COMMERCE_HUB,
                        function = StationFunction.COMMERCE,
                        displayName = "Commerce Hub",
                        cost =
                            StationBuildCost(
                                credits = 1500,
                                resources = mapOf(ResourceType.IRON_ORE to 15, ResourceType.SILICON to 8),
                            ),
                    ),
                    StationModule(
                        id = RETROFIT_BAY,
                        function = StationFunction.RETROFIT,
                        displayName = "Retrofit Bay",
                        cost =
                            StationBuildCost(
                                credits = 2000,
                                resources = mapOf(ResourceType.TITANIUM to 6, ResourceType.ALUMINUM to 10),
                            ),
                    ),
                ),
            )
    }
}
