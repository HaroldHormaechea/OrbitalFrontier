package com.orbitalfrontier.station

import com.orbitalfrontier.economy.Cargo

/**
 * One selectable entry on the station build/edit screen (UC51 AC#1) — a module-and-action choice with
 * its cost preview and whether the player can currently afford it.
 *
 * Pure data: [label] is the human-readable choice, [order] the [StationBuildOrder] a CONFIRM tap fires
 * (a [StationBuildOrder.FoundStation] to found a new station or a [StationBuildOrder.BuildModule] to
 * expand an owned one), [cost] the module's [StationBuildCost] (shown as the preview), and [affordable]
 * a snapshot of whether the live credits + cargo cover it — the *gate* still lives in the pure
 * [StationBuilder], so [affordable] only drives the UI, never the actual build outcome.
 */
data class StationBuildOption(
    val label: String,
    val order: StationBuildOrder,
    val cost: StationBuildCost,
    val affordable: Boolean,
)

/**
 * Pure build/edit-screen state for the station-building UI (UC51 AC#1) — the model behind
 * [com.orbitalfrontier.screen.StationBuildScreen], replacing UC15's direct default-build action.
 *
 * From the module [StationModuleCatalog], the player's [StationRegistry], their live credits + active
 * ship cargo, and whether the docked station is build-capable, it produces the list of build options:
 * one **found-station** option per catalogued module, then one **expansion** option per (owned station ×
 * catalogued module). Each option carries its cost preview and an affordability flag derived from the
 * SAME pure [StationBuildCost.canAfford] the builder deducts against (so the preview never disagrees
 * with the charge). When the station is not build-capable the menu is empty — building is offered only
 * where [com.orbitalfrontier.world.Station.buildsStations] is set, exactly as the builder gates it.
 *
 * Pure (no engine types) so the build UI's logic is fully JVM-testable (ADR 0001); the screen is a thin
 * view over this model.
 */
object StationBuildMenu {
    /**
     * The build options offered at a docked station (UC51 AC#1). Empty when [buildsStations] is false.
     * Otherwise: a [StationBuildOrder.FoundStation] option for each module in [catalog] (in authored
     * order), followed by a [StationBuildOrder.BuildModule] expansion option for each owned station in
     * [registry] crossed with each catalogued module. Affordability is evaluated against [credits] and
     * [cargo] per option.
     */
    fun options(
        catalog: StationModuleCatalog,
        registry: StationRegistry,
        credits: Long,
        cargo: Cargo,
        buildsStations: Boolean,
    ): List<StationBuildOption> {
        if (!buildsStations) return emptyList()
        val found =
            catalog.all.map { module ->
                StationBuildOption(
                    label = "Found Station — ${module.displayName}",
                    order = StationBuildOrder.FoundStation(module.id),
                    cost = module.cost,
                    affordable = module.cost.canAfford(credits, cargo),
                )
            }
        val expand =
            registry.stations.flatMap { station ->
                catalog.all.map { module ->
                    StationBuildOption(
                        label = "Expand ${OwnedStationProjection.displayNameFor(station.id)} — ${module.displayName}",
                        order = StationBuildOrder.BuildModule(station.id, module.id),
                        cost = module.cost,
                        affordable = module.cost.canAfford(credits, cargo),
                    )
                }
            }
        return found + expand
    }
}
