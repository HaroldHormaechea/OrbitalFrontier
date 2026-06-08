package com.orbitalfrontier.ship

import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.economy.FuelParams

/**
 * Composes fuel level with movement tuning (UC07 AC#3) **without touching** [ShipMovementModel]: it
 * derives an effective [ShipMovementParams] whose forward and reverse speed caps are scaled by the
 * current fuel [Fuel.speedFactor], then the unchanged model runs against those params.
 *
 * Lives in `ship` (not `economy`) so the dependency points `ship → economy` only — no cycle, and the
 * movement model itself stays fuel-agnostic (SRP / Open-Closed). The device loop and the test sim
 * harness both call [effectiveParams], so live and replayed speed limiting are identical.
 *
 * **Byte-identical at full fuel:** at or above the low-fuel threshold [Fuel.speedFactor] is exactly
 * `1.0f`, and this returns [base] **unchanged** (same instance) — so existing movement fixtures
 * replay bit-for-bit and only a low tank alters motion (UC07 composition guarantee).
 */
object FuelLimitedMovement {
    /**
     * [base] with [ShipMovementParams.maxSpeed] and [ShipMovementParams.maxReverseSpeed] scaled by the
     * fuel speed factor. Returns [base] itself when the factor is exactly `1.0f` (full-enough tank).
     */
    fun effectiveParams(
        base: ShipMovementParams,
        fuel: Fuel,
        params: FuelParams,
    ): ShipMovementParams {
        val factor = fuel.speedFactor(params)
        if (factor == 1.0f) return base
        return base.copy(
            maxSpeed = base.maxSpeed * factor,
            maxReverseSpeed = base.maxReverseSpeed * factor,
        )
    }
}
