package com.orbitalfrontier.power

/**
 * The outcome of one [Brownout.resolve] (UC49) — which [PowerSystem]s stay powered, which were shed,
 * and whether the ship is in brownout this tick, plus the budget figures the HUD reads.
 *
 * Pure render/derive data: it is **never** persisted and never enters `SimulationState` or a recorded
 * playthrough artifact (power is transient — no schema bump, ADR 0037). [HELM] is always in
 * [poweredSystems]; sheddable systems move to [shedSystems] lowest-priority-first when demand exceeds
 * [reactorOutput].
 */
data class BrownoutResult(
    /** Budget demand (units/s): protected base+thrust draw PLUS the sheddable weapons/scanner draws. */
    val totalDemand: Float,
    /** Reactor energy output (units/s) the budget is measured against. */
    val reactorOutput: Float,
    /** Systems that remain powered this tick (always includes [PowerSystem.HELM]). */
    val poweredSystems: Set<PowerSystem>,
    /** Sheddable systems dropped to meet the budget (empty when not in brownout). */
    val shedSystems: Set<PowerSystem>,
    /** True when [totalDemand] exceeds [reactorOutput] this tick (load was, or had to be, shed). */
    val isBrownout: Boolean,
) {
    /** Whether [system] is currently powered (so callers can gate fire/scan on its supply). */
    fun isPowered(system: PowerSystem): Boolean = system in poweredSystems

    companion object {
        /**
         * A no-load, full-power snapshot (every system powered, nothing shed) used as a safe default
         * before the first tick is resolved — e.g. the [com.orbitalfrontier.render.HudViewModel] default.
         */
        val FULL_POWER: BrownoutResult =
            BrownoutResult(
                totalDemand = 0f,
                reactorOutput = PowerParams.DEFAULT_REACTOR_OUTPUT,
                poweredSystems = PowerSystem.entries.toSet(),
                shedSystems = emptySet(),
                isBrownout = false,
            )
    }
}

/**
 * Pure, deterministic power-budget resolver with automatic priority shedding (UC49 — realises the
 * deferred "power budget cap" of docs/design/power-and-energy.md). Engine-free and JVM-testable like
 * the rest of `power/`.
 *
 * The model is **rate-based with a protected floor**: the protected set (HELM + base hotel load, i.e.
 * the same `base + thrust` draw [PowerModel.drawAt] feeds to fuel burn) is *never* shed, so the ship
 * always keeps the power to fly (no-deadlock, AC#4). Sheddable systems carry **budget-only** draws
 * ([PowerParams.weaponsDraw] / [PowerParams.scannerDraw]) that feed THIS budget alone and never
 * [PowerModel.drawAt] / fuel burn — protecting the UC16 25/75 fuel tuning.
 *
 * Determinism / byte-identity: at full power the sheddable draws default 0, so demand ≈ the base+thrust
 * fuel draw, brownout is false and nothing is shed → the combat/scan paths are untouched.
 */
object Brownout {
    /**
     * Resolve the brownout state for one tick from the current thrust state and tuning.
     *
     * Demand = protected draw ([PowerModel.drawAt], base + thrust-when-[thrusting]) + the sheddable
     * weapons/scanner draws. If demand ≤ [PowerParams.reactorOutput] every system stays powered and
     * [BrownoutResult.isBrownout] is false. Otherwise sheddable systems are shed ascending by
     * [PowerSystem.shedPriority] (SCANNER then WEAPONS) until protected + remaining ≤ output, or all
     * sheddable systems are shed. **Degenerate case** (deliberate, documented in ADR 0037): if the
     * protected set alone exceeds output, every sheddable system is shed and `isBrownout = true` even
     * though the budget still can't be met — the protected systems stay powered regardless.
     */
    fun resolve(
        thrusting: Boolean,
        params: PowerParams,
    ): BrownoutResult {
        val protectedDraw = PowerModel.drawAt(thrusting, params) // base + (thrust ? thrustDraw : 0)
        // Sheddable demand by system — BUDGET ONLY, never fed to drawAt / fuel burn.
        val sheddableDraw =
            mapOf(
                PowerSystem.SCANNER to params.scannerDraw,
                PowerSystem.WEAPONS to params.weaponsDraw,
            )
        val totalDemand = protectedDraw + params.scannerDraw + params.weaponsDraw

        val powered = LinkedHashSet<PowerSystem>()
        powered.add(PowerSystem.HELM) // protected floor — always powered.

        if (totalDemand <= params.reactorOutput) {
            // Within budget: every sheddable system stays powered, no brownout.
            powered.addAll(sheddableDraw.keys)
            return BrownoutResult(
                totalDemand = totalDemand,
                reactorOutput = params.reactorOutput,
                poweredSystems = powered,
                shedSystems = emptySet(),
                isBrownout = false,
            )
        }

        // Over budget: shed sheddable systems lowest-priority-first until the budget is met (or all gone).
        val shed = LinkedHashSet<PowerSystem>()
        var remaining = totalDemand
        for (system in sheddableDraw.keys.sortedBy { it.shedPriority }) {
            if (remaining <= params.reactorOutput) break
            remaining -= sheddableDraw.getValue(system)
            shed.add(system)
        }
        // Any sheddable system not shed stays powered.
        for (system in sheddableDraw.keys) {
            if (system !in shed) powered.add(system)
        }
        return BrownoutResult(
            totalDemand = totalDemand,
            reactorOutput = params.reactorOutput,
            poweredSystems = powered,
            shedSystems = shed,
            isBrownout = true,
        )
    }
}
