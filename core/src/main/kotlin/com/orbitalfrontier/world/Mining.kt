package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.MiningParams
import com.orbitalfrontier.economy.ResourceType

/**
 * The player's intent for the mine control on a given frame (UC06 AC#2) — the mining analogue of
 * [DockAction].
 *
 * [NONE] is the common per-frame case (the mine button is not held); [MINE] is the held action the
 * play screen feeds in each frame while the context mine button is pressed. Mining is continuous
 * (extracts a little each tick while held), so unlike a dock tap there is no separate stop action —
 * releasing the button simply feeds [NONE].
 */
enum class MineAction {
    NONE,
    MINE,
}

/**
 * The result of a single mining tick (UC06 AC#2/#4/#5) — the updated cargo, the updated field
 * depletion map, and how many units were mined this tick.
 *
 * All three are pure values: the caller (the play screen on device, the replay harness in tests)
 * folds them back into its mutable state. [minedUnits] is 0 on a no-op tick (nothing in range, hold
 * full, field empty, or [MineAction.NONE]), in which case [cargo]/[fieldDepletion] are the inputs
 * unchanged — letting the caller cheaply detect "nothing happened".
 */
data class MiningResult(
    val cargo: Cargo,
    val fieldDepletion: Map<PoiId, Map<ResourceType, Int>>,
    val minedUnits: Int,
)

/**
 * Pure, deterministic asteroid-mining resolution (UC06 AC#2/#6) — the mining analogue of [Docking].
 *
 * Both functions are side-effect-free functions of (world, current sector, ship position, …):
 * identical inputs always yield an identical result, with no I/O and no engine types, so they slot
 * into the deterministic simulation stepper and are fully JVM-unit-testable (UC06 AC#6). They do
 * **not** mutate anything — the caller applies the [MiningResult].
 *
 * Mining is **proximity + held action**, never automatic: [availableField] reports whether a field
 * is in range so the UI can offer a mine prompt, and [resolve] only extracts when the player is
 * actually holding [MineAction.MINE].
 *
 * **Extraction order is a fixed code invariant** — resources are taken in [ResourceType] *ordinal*
 * order (not configurable, see [MiningParams]) so a given (field, cargo, params) always extracts the
 * same units in the same order, making mining replay-deterministic.
 */
object Mining {
    /**
     * The asteroid field the ship can currently mine in [currentSector], or null if none is in range.
     *
     * A field is minable when the ship is inside its [AsteroidField.miningRadius] circle. When several
     * fields overlap the ship, the **nearest** wins; ties break by the sector's authored POI order
     * (deterministic by construction), so the same input always selects the same field.
     */
    fun availableField(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
    ): AsteroidField? {
        val sector = world.sector(currentSector)
        return sector.asteroidFields
            .filter { (shipPosition - it.position).length <= it.miningRadius }
            .minByOrNull { (shipPosition - it.position).length }
    }

    /**
     * Resolve a single mining tick from the player's [action].
     *
     * Returns the inputs **unchanged** (a no-op, `minedUnits = 0`) when there is nothing to do: the
     * action is [MineAction.NONE], the cargo is already full, no field is in range, or the in-range
     * field has nothing left. Otherwise it extracts up to [MiningParams.extractionUnitsPerTick] units
     * *total* from the in-range field, walking [ResourceType] in ordinal order and threading a running
     * remaining-capacity (so the hold never overfills) and the field's running remaining amounts (so a
     * deposit is never over-mined). Extraction stops as soon as the per-tick budget is spent, the
     * cargo fills, or the field empties. The field's new remaining amounts are written back into a
     * copy of [fieldDepletion] keyed by the field's [PoiId] (UC06 AC#4); the rest of the map is
     * untouched.
     *
     * @param fieldDepletion remaining units per field id; an **absent** field is pristine (its
     *   [AsteroidField.deposits] are used as the starting remaining).
     */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        cargo: Cargo,
        fieldDepletion: Map<PoiId, Map<ResourceType, Int>>,
        action: MineAction,
        params: MiningParams,
    ): MiningResult {
        val unchanged = MiningResult(cargo, fieldDepletion, 0)
        if (action == MineAction.NONE) return unchanged
        if (cargo.isFull) return unchanged

        val field = availableField(world, currentSector, shipPosition) ?: return unchanged

        // Remaining amounts for this field: a prior depletion entry, else the pristine authored deposits.
        val remaining = (fieldDepletion[field.id] ?: field.deposits).toMutableMap()
        if (remaining.values.sum() <= 0) return unchanged // field already empty

        var budget = params.extractionUnitsPerTick
        var workingCargo = cargo
        var totalMined = 0

        // Fixed extraction order = ResourceType ordinal order (declaration order). Deterministic.
        for (type in ResourceType.entries) {
            if (budget <= 0 || workingCargo.isFull) break
            val available = remaining[type] ?: 0
            if (available <= 0) continue

            val want = minOf(budget, available, workingCargo.remainingCapacity)
            if (want <= 0) continue

            val transfer = workingCargo.add(type, want)
            val accepted = transfer.acceptedUnits
            if (accepted <= 0) continue

            workingCargo = transfer.cargo
            remaining[type] = available - accepted
            budget -= accepted
            totalMined += accepted
        }

        if (totalMined == 0) return unchanged

        // Write the field's new remaining amounts back; the rest of the depletion map is untouched.
        val updatedDepletion = fieldDepletion + (field.id to remaining.toMap())
        return MiningResult(workingCargo, updatedDepletion, totalMined)
    }
}
